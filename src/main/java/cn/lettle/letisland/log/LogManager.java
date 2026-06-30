package cn.lettle.letisland.log;

import cn.lettle.letisland.database.DatabaseManager;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 日志管理器
 * 统一记录玩家登录登出、图鉴解锁、敏感操作等日志到 SQLite
 * 所有写入操作异步执行，避免阻塞主线程
 */
public class LogManager {

    private final JavaPlugin plugin;
    private final DatabaseManager databaseManager;

    public LogManager(@NotNull JavaPlugin plugin, @NotNull DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
    }

    // ==================== 日志写入（异步） ====================

    /**
     * 记录玩家登录
     */
    public void logLogin(@NotNull UUID playerId, @NotNull String playerName, @Nullable String ip) {
        insertAsync(LogType.LOGIN, playerId, playerName, ip, null);
    }

    /**
     * 记录玩家登出
     */
    public void logLogout(@NotNull UUID playerId, @NotNull String playerName, @Nullable String ip) {
        insertAsync(LogType.LOGOUT, playerId, playerName, ip, null);
    }

    /**
     * 记录鱼类图鉴首次解锁
     */
    public void logCodexFish(@NotNull UUID playerId, @NotNull String fishId,
                             @NotNull String fishName, double weight) {
        String detail = "首次钓到[" + fishName + "](id:" + fishId + "), 重量: " + weight + "kg";
        insertAsync(LogType.CODEX_FISH, playerId, getPlayerName(playerId), null, detail);
    }

    /**
     * 记录称号解锁
     */
    public void logCodexTitle(@NotNull UUID playerId, @NotNull String titleId,
                              @NotNull String titleName) {
        String detail = "解锁称号[" + titleName + "](id:" + titleId + ")";
        insertAsync(LogType.CODEX_TITLE, playerId, getPlayerName(playerId), null, detail);
    }

    /**
     * 记录敏感操作（管理员命令、系统开关等）
     */
    public void logSensitive(@NotNull UUID playerId, @NotNull String playerName,
                             @NotNull String action) {
        insertAsync(LogType.SENSITIVE, playerId, playerName, null, action);
    }

    /**
     * 记录安全拦截事件（扫描机器人、可疑用户名、频率超限等被拦截的连接尝试）
     * playerId 可能为 null（离线模式或未获取到 UUID），此时使用 NIL UUID 占位
     */
    public void logSecurityBlock(@Nullable UUID playerId, @NotNull String playerName,
                                 @NotNull String ip, @NotNull String reason) {
        UUID id = playerId != null ? playerId : new UUID(0, 0);
        insertAsync(LogType.SECURITY_BLOCK, id, playerName, ip, reason);
    }

    /**
     * 异步插入日志记录
     */
    private void insertAsync(@NotNull LogType type, @NotNull UUID playerId,
                            @NotNull String playerName, @Nullable String ip,
                            @Nullable String detail) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String sql = """
                    INSERT INTO plugin_logs (log_type, player_uuid, player_name, ip, detail)
                    VALUES (?, ?, ?, ?, ?);
                    """;
            try (Connection conn = databaseManager.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, type.name());
                ps.setString(2, playerId.toString());
                ps.setString(3, playerName);
                if (ip != null) {
                    ps.setString(4, ip);
                } else {
                    ps.setNull(4, java.sql.Types.VARCHAR);
                }
                if (detail != null) {
                    ps.setString(5, detail);
                } else {
                    ps.setNull(5, java.sql.Types.VARCHAR);
                }
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().warning("写入日志失败: " + e.getMessage());
            }
        });
    }

    // ==================== 日志查询（同步，供命令使用） ====================

    /**
     * 查询最近的全局日志
     */
    @NotNull
    public List<LogEntry> getRecentLogs(int limit) {
        String sql = """
                SELECT id, log_type, player_uuid, player_name, ip, detail, created_at
                FROM plugin_logs
                ORDER BY id DESC
                LIMIT ?;
                """;
        return queryLogs(sql, ps -> ps.setInt(1, limit));
    }

    /**
     * 查询某玩家的日志
     */
    @NotNull
    public List<LogEntry> getPlayerLogs(@NotNull UUID playerId, int limit) {
        String sql = """
                SELECT id, log_type, player_uuid, player_name, ip, detail, created_at
                FROM plugin_logs
                WHERE player_uuid = ?
                ORDER BY id DESC
                LIMIT ?;
                """;
        return queryLogs(sql, ps -> {
            ps.setString(1, playerId.toString());
            ps.setInt(2, limit);
        });
    }

    /**
     * 按类型查询日志
     */
    @NotNull
    public List<LogEntry> getLogsByType(@NotNull LogType type, int limit) {
        String sql = """
                SELECT id, log_type, player_uuid, player_name, ip, detail, created_at
                FROM plugin_logs
                WHERE log_type = ?
                ORDER BY id DESC
                LIMIT ?;
                """;
        return queryLogs(sql, ps -> {
            ps.setString(1, type.name());
            ps.setInt(2, limit);
        });
    }

    /**
     * 执行日志查询的通用方法
     */
    @NotNull
    private List<LogEntry> queryLogs(@NotNull String sql, @NotNull StatementConfigurer configurer) {
        List<LogEntry> result = new ArrayList<>();
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            configurer.configure(ps);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    LogType type;
                    try {
                        type = LogType.valueOf(rs.getString("log_type"));
                    } catch (IllegalArgumentException e) {
                        continue; // 跳过未知日志类型
                    }
                    result.add(new LogEntry(
                            rs.getLong("id"),
                            type,
                            UUID.fromString(rs.getString("player_uuid")),
                            rs.getString("player_name"),
                            rs.getString("ip"),
                            rs.getString("detail"),
                            rs.getTimestamp("created_at")
                    ));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("查询日志失败: " + e.getMessage());
        }
        return result;
    }

    // ==================== 工具方法 ====================

    @NotNull
    private String getPlayerName(@NotNull UUID playerId) {
        var player = Bukkit.getPlayer(playerId);
        if (player != null) return player.getName();
        var offline = Bukkit.getOfflinePlayer(playerId);
        return offline.getName() != null ? offline.getName() : "Unknown";
    }

    // ==================== 回调接口与数据类 ====================

    @FunctionalInterface
    private interface StatementConfigurer {
        void configure(@NotNull PreparedStatement ps) throws SQLException;
    }

    /**
     * 日志条目数据类
     */
    public record LogEntry(
            long id,
            LogType type,
            UUID playerUuid,
            String playerName,
            String ip,
            String detail,
            Timestamp createdAt
    ) {}
}
