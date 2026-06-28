package cn.lettle.letisland.security;

import cn.lettle.letisland.database.DatabaseManager;
import cn.lettle.letisland.log.LogManager;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 安全防护管理器
 *
 * 提供三层防护：
 *   1. IP 黑名单：命中过的 IP 在封禁期内直接拒绝连接
 *   2. 频率限制：同一 IP 在 60 秒内连接超过阈值则临时封禁
 *   3. 用户名检测：已知扫描器名单 + 可疑用户名正则，命中即封禁 IP
 *
 * 封禁记录持久化到 SQLite（security_blocks 表），重启后仍生效。
 * 所有拦截行为通过 LogManager 记录到 plugin_logs（log_type = SECURITY_BLOCK）。
 *
 * 线程安全：ipAttempts 与 blockedIPs 使用 ConcurrentHashMap，
 * 单个 IP 的窗口操作在对应 deque 上同步以保证 prune+add+count 原子性。
 */
public class SecurityManager {

    /** 滑动窗口时长（毫秒） */
    private static final long WINDOW_MS = TimeUnit.MINUTES.toMillis(1);

    private final JavaPlugin plugin;
    private final DatabaseManager databaseManager;
    private final LogManager logManager;

    // ===== 配置项 =====
    private boolean enabled;
    private int maxAttemptsPerMinute;
    private long blockDurationMillis;
    private boolean usernameCheckEnabled;
    private final List<String> knownScanners = new ArrayList<>();
    private final List<Pattern> suspiciousPatterns = new ArrayList<>();

    // ===== 运行时状态 =====
    /** IP -> 最近 60 秒内的连接时间戳队列 */
    private final Map<String, Deque<Long>> ipAttempts = new ConcurrentHashMap<>();
    /** IP -> 解封时间戳（毫秒），0 表示永久封禁 */
    private final Map<String, Long> blockedIPs = new ConcurrentHashMap<>();
    private final Map<String, String> blockReasons = new ConcurrentHashMap<>();

    public SecurityManager(@NotNull JavaPlugin plugin, @NotNull DatabaseManager databaseManager,
                           @NotNull LogManager logManager) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
        this.logManager = logManager;
        loadConfig();
        loadBlockedIPsFromDB();

        // 每 5 分钟清理一次过期封禁与空窗口，避免内存泄漏
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::cleanupExpired,
                6000L, 6000L);
    }

    // ==================== 配置加载 ====================

    public void loadConfig() {
        FileConfiguration config = plugin.getConfig();
        enabled = config.getBoolean("security.enabled", true);
        maxAttemptsPerMinute = config.getInt("security.max-attempts-per-minute", 5);
        blockDurationMillis = TimeUnit.MINUTES.toMillis(
                config.getInt("security.block-duration-minutes", 60));
        usernameCheckEnabled = config.getBoolean("security.username-check", true);

        knownScanners.clear();
        knownScanners.addAll(config.getStringList("security.known-scanners"));

        suspiciousPatterns.clear();
        List<String> rawPatterns = config.getStringList("security.suspicious-username-patterns");
        for (String raw : rawPatterns) {
            try {
                suspiciousPatterns.add(Pattern.compile(raw));
            } catch (PatternSyntaxException e) {
                plugin.getLogger().warning("安全系统: 无效的正则表达式已跳过: " + raw +
                        " (" + e.getDescription() + ")");
            }
        }

        plugin.getLogger().info("安全防护系统已加载: " +
                "启用=" + enabled +
                ", 频率阈值=" + maxAttemptsPerMinute + "/分钟" +
                ", 封禁时长=" + (blockDurationMillis / 60000) + "分钟" +
                ", 已知扫描器=" + knownScanners.size() +
                ", 可疑正则=" + suspiciousPatterns.size());
    }

    /** 从数据库加载未过期的封禁记录到内存 */
    private void loadBlockedIPsFromDB() {
        String sql = "SELECT ip, reason, blocked_until FROM security_blocks WHERE blocked_until > CURRENT_TIMESTAMP;";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            int count = 0;
            while (rs.next()) {
                String ip = rs.getString("ip");
                String reason = rs.getString("reason");
                long until = rs.getTimestamp("blocked_until").getTime();
                blockedIPs.put(ip, until);
                blockReasons.put(ip, reason);
                count++;
            }
            if (count > 0) {
                plugin.getLogger().info("安全系统: 从数据库恢复了 " + count + " 条未过期封禁记录");
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("安全系统: 加载封禁记录失败: " + e.getMessage());
        }
    }

    // ==================== 核心检查逻辑 ====================

    /**
     * 检查一次登录尝试
     *
     * @return 非 null 表示拦截（包含踢出消息）；null 表示放行
     */
    @Nullable
    public CheckResult checkLogin(@NotNull String ip, @NotNull String name, @Nullable UUID uuid) {
        if (!enabled) {
            return null;
        }

        // 1. 已在黑名单中 -> 直接拒绝
        Long unblockAt = blockedIPs.get(ip);
        if (unblockAt != null) {
            long now = System.currentTimeMillis();
            if (now < unblockAt) {
                long remainMin = Math.max(1, (unblockAt - now) / 60_000);
                String reason = "IP 已被封禁（剩余 " + remainMin + " 分钟）；原因: " +
                        blockReasons.getOrDefault(ip, "未知");
                logBlock(uuid, name, ip, "黑名单拦截: " + blockReasons.getOrDefault(ip, "未知"));
                return new CheckResult(CheckResultType.BLACKLISTED, reason);
            } else {
                // 已过期，清理
                blockedIPs.remove(ip);
                blockReasons.remove(ip);
                removeBlockFromDB(ip);
            }
        }

        // 2. 用户名检测（先于频率限制，避免名单命中后还要继续累积计数）
        if (usernameCheckEnabled) {
            // 已知扫描器名单（精确匹配，不区分大小写）
            for (String scanner : knownScanners) {
                if (name.equalsIgnoreCase(scanner)) {
                    String reason = "已知扫描机器人: " + name;
                    blockIP(ip, reason);
                    logBlock(uuid, name, ip, reason);
                    return new CheckResult(CheckResultType.SCANNER_NAME,
                            "§c检测到已知扫描行为，你的 IP 已被封禁");
                }
            }
            // 可疑用户名正则
            for (Pattern p : suspiciousPatterns) {
                if (p.matcher(name).matches()) {
                    String reason = "可疑用户名匹配 [" + p.pattern() + "]: " + name;
                    blockIP(ip, reason);
                    logBlock(uuid, name, ip, reason);
                    return new CheckResult(CheckResultType.SUSPICIOUS_NAME,
                            "§c用户名可疑，你的 IP 已被临时封禁");
                }
            }
        }

        // 3. 频率限制
        int attempts = recordAndCountAttempt(ip);
        if (attempts > maxAttemptsPerMinute) {
            String reason = "连接频率超限: " + attempts + " 次/分钟（阈值 " + maxAttemptsPerMinute + "）";
            blockIP(ip, reason);
            logBlock(uuid, name, ip, reason);
            return new CheckResult(CheckResultType.RATE_LIMITED,
                    "§c连接过于频繁，IP 已被临时封禁");
        }

        return null; // 放行
    }

    /**
     * 记录一次连接尝试并返回当前窗口内的总次数（原子操作）
     */
    private int recordAndCountAttempt(@NotNull String ip) {
        long now = System.currentTimeMillis();
        Deque<Long> deque = ipAttempts.computeIfAbsent(ip, k -> new ConcurrentLinkedDeque<>());
        synchronized (deque) {
            // 移除窗口外的旧时间戳
            while (!deque.isEmpty() && now - deque.peekFirst() > WINDOW_MS) {
                deque.pollFirst();
            }
            deque.addLast(now);
            return deque.size();
        }
    }

    // ==================== 封禁管理 ====================

    /** 自动封禁（使用配置的默认时长） */
    public void blockIP(@NotNull String ip, @NotNull String reason) {
        long until = System.currentTimeMillis() + blockDurationMillis;
        blockedIPs.put(ip, until);
        blockReasons.put(ip, reason);
        persistBlock(ip, reason, until);
    }

    /** 手动封禁指定时长（minutes <= 0 表示永久） */
    public void manualBlock(@NotNull String ip, @NotNull String reason, int minutes) {
        long until = minutes <= 0 ? 0 : System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(minutes);
        blockedIPs.put(ip, until);
        blockReasons.put(ip, reason);
        persistBlock(ip, reason, until);
    }

    /** 解除封禁 */
    public boolean unblockIP(@NotNull String ip) {
        Long removed = blockedIPs.remove(ip);
        blockReasons.remove(ip);
        removeBlockFromDB(ip);
        return removed != null;
    }

    public boolean isBlocked(@NotNull String ip) {
        Long until = blockedIPs.get(ip);
        if (until == null) return false;
        if (until == 0) return true; // 永久封禁
        if (System.currentTimeMillis() < until) return true;
        blockedIPs.remove(ip);
        blockReasons.remove(ip);
        return false;
    }

    // ==================== 查询（供命令使用） ====================

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @NotNull
    public List<BlockEntry> getBlockedIPs() {
        List<BlockEntry> result = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Long> entry : blockedIPs.entrySet()) {
            String ip = entry.getKey();
            long until = entry.getValue();
            String reason = blockReasons.getOrDefault(ip, "未知");
            result.add(new BlockEntry(ip, reason, until, until == 0 || until > now));
        }
        return result;
    }

    /** 当前正在被频率追踪的 IP 数量 */
    public int getTrackedIPCount() {
        return ipAttempts.size();
    }

    /** 当前有效封禁数量 */
    public int getActiveBlockCount() {
        int count = 0;
        long now = System.currentTimeMillis();
        for (Long until : blockedIPs.values()) {
            if (until == 0 || until > now) count++;
        }
        return count;
    }

    // ==================== 私有工具 ====================

    private void logBlock(@Nullable UUID uuid, @NotNull String name, @NotNull String ip, @NotNull String reason) {
        // 异步写入 plugin_logs（log_type = SECURITY_BLOCK）
        logManager.logSecurityBlock(uuid, name, ip, reason);
        // 同时在控制台打印，方便管理员实时查看
        plugin.getLogger().warning("[安全拦截] IP=" + ip + " 用户名=" + name + " 原因=" + reason);
    }

    private void persistBlock(@NotNull String ip, @NotNull String reason, long untilMillis) {
        Timestamp until = untilMillis == 0
                ? new Timestamp(Long.MAX_VALUE)
                : new Timestamp(untilMillis);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String sql = """
                    INSERT INTO security_blocks (ip, reason, blocked_until)
                    VALUES (?, ?, ?)
                    ON CONFLICT(ip) DO UPDATE SET
                        reason = excluded.reason,
                        blocked_until = excluded.blocked_until;
                    """;
            try (Connection conn = databaseManager.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, ip);
                ps.setString(2, reason);
                ps.setTimestamp(3, until);
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().warning("安全系统: 持久化封禁记录失败: " + e.getMessage());
            }
        });
    }

    private void removeBlockFromDB(@NotNull String ip) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (Connection conn = databaseManager.getConnection();
                 PreparedStatement ps = conn.prepareStatement("DELETE FROM security_blocks WHERE ip = ?;")) {
                ps.setString(1, ip);
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().warning("安全系统: 删除封禁记录失败: " + e.getMessage());
            }
        });
    }

    /** 定时清理过期封禁与空窗口 */
    private void cleanupExpired() {
        long now = System.currentTimeMillis();
        // 清理过期封禁
        blockedIPs.entrySet().removeIf(e -> e.getValue() != 0 && e.getValue() <= now);
        // 清理空的或全部过期的频率窗口
        ipAttempts.entrySet().removeIf(e -> {
            Deque<Long> deque = e.getValue();
            synchronized (deque) {
                while (!deque.isEmpty() && now - deque.peekFirst() > WINDOW_MS) {
                    deque.pollFirst();
                }
                return deque.isEmpty();
            }
        });
    }

    // ==================== 数据类 ====================

    /** 检查结果 */
    public record CheckResult(@NotNull CheckResultType type, @NotNull String message) {}

    public enum CheckResultType {
        BLACKLISTED, SCANNER_NAME, SUSPICIOUS_NAME, RATE_LIMITED
    }

    /** 封禁条目（供命令展示） */
    public record BlockEntry(@NotNull String ip, @NotNull String reason, long untilMillis, boolean active) {}
}
