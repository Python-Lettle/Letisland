package cn.lettle.letisland.economy;

import cn.lettle.letisland.database.DatabaseManager;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 经济系统核心管理器
 * 负责玩家余额的存储、查询、增减和转账操作
 * 数据持久化在SQLite数据库的 economy_balance 表中
 */
public class EconomyManager {

    /** 起始余额 */
    private static final double DEFAULT_BALANCE = 0.0;

    private final JavaPlugin plugin;
    private final DatabaseManager databaseManager;
    private final String currencySymbol;

    /** 玩家余额内存缓存（减少数据库查询） */
    private final ConcurrentHashMap<UUID, Double> balanceCache = new ConcurrentHashMap<>();

    public EconomyManager(@NotNull JavaPlugin plugin, @NotNull DatabaseManager databaseManager,
                          @NotNull String currencySymbol) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
        this.currencySymbol = currencySymbol;
    }

    /**
     * 获取玩家余额
     */
    public double getBalance(@NotNull OfflinePlayer player) {
        return getBalance(player.getUniqueId());
    }

    /**
     * 获取玩家余额
     */
    public double getBalance(@NotNull UUID playerId) {
        Double cached = balanceCache.get(playerId);
        if (cached != null) {
            return cached;
        }
        String sql = "SELECT balance FROM economy_balance WHERE player_uuid = ?";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    double balance = round(rs.getDouble("balance"));
                    balanceCache.put(playerId, balance);
                    return balance;
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("查询玩家余额失败: " + e.getMessage());
        }
        // 缓存默认值，避免重复查询不存在的记录
        balanceCache.put(playerId, DEFAULT_BALANCE);
        return DEFAULT_BALANCE;
    }

    /**
     * 设置玩家余额
     */
    public void setBalance(@NotNull OfflinePlayer player, double amount) {
        setBalance(player.getUniqueId(), amount);
    }

    /**
     * 设置玩家余额
     */
    public void setBalance(@NotNull UUID playerId, double amount) {
        double rounded = round(amount);
        double before = getBalance(playerId);
        upsertBalance(playerId, rounded);

        OfflinePlayer player = Bukkit.getOfflinePlayer(playerId);
        callTransactionEvent(player, EconomyTransactionEvent.TransactionType.DEPOSIT,
                rounded - before, before, rounded);
    }

    /**
     * 插入或更新玩家余额（UPSERT语义）
     */
    private void upsertBalance(@NotNull UUID playerId, double balance) {
        String sql = """
                INSERT INTO economy_balance (player_uuid, balance)
                VALUES (?, ?)
                ON CONFLICT(player_uuid) DO UPDATE SET balance = excluded.balance;
                """;
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerId.toString());
            ps.setDouble(2, balance);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("保存玩家余额失败: " + e.getMessage());
            return;
        }
        // 更新内存缓存
        balanceCache.put(playerId, balance);
    }

    /**
     * 检查玩家是否拥有账户
     */
    public boolean hasAccount(@NotNull OfflinePlayer player) {
        return hasAccount(player.getUniqueId());
    }

    /**
     * 检查玩家是否拥有账户
     */
    public boolean hasAccount(@NotNull UUID playerId) {
        String sql = "SELECT 1 FROM economy_balance WHERE player_uuid = ?";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("查询账户是否存在失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * 创建新账户
     */
    public void createAccount(@NotNull OfflinePlayer player) {
        createAccount(player.getUniqueId());
    }

    /**
     * 创建新账户
     */
    public void createAccount(@NotNull UUID playerId) {
        if (!hasAccount(playerId)) {
            upsertBalance(playerId, DEFAULT_BALANCE);

            OfflinePlayer player = Bukkit.getOfflinePlayer(playerId);
            callTransactionEvent(player, EconomyTransactionEvent.TransactionType.INITIALIZE,
                    DEFAULT_BALANCE, 0.0, DEFAULT_BALANCE);
        }
    }

    /**
     * 存入金额
     */
    public boolean deposit(@NotNull OfflinePlayer player, double amount) {
        return deposit(player.getUniqueId(), amount);
    }

    /**
     * 存入金额
     */
    public boolean deposit(@NotNull UUID playerId, double amount) {
        if (amount <= 0) {
            return false;
        }
        double before = getBalance(playerId);
        double after = round(before + amount);
        upsertBalance(playerId, after);

        OfflinePlayer player = Bukkit.getOfflinePlayer(playerId);
        callTransactionEvent(player, EconomyTransactionEvent.TransactionType.DEPOSIT,
                amount, before, after);
        return true;
    }

    /**
     * 取出金额
     */
    public boolean withdraw(@NotNull OfflinePlayer player, double amount) {
        return withdraw(player.getUniqueId(), amount);
    }

    /**
     * 取出金额
     */
    public boolean withdraw(@NotNull UUID playerId, double amount) {
        if (amount <= 0) {
            return false;
        }
        double before = getBalance(playerId);
        if (before < amount) {
            return false;
        }
        double after = round(before - amount);
        upsertBalance(playerId, after);

        OfflinePlayer player = Bukkit.getOfflinePlayer(playerId);
        callTransactionEvent(player, EconomyTransactionEvent.TransactionType.WITHDRAW,
                amount, before, after);
        return true;
    }

    /**
     * 检查玩家是否拥有足够余额
     */
    public boolean has(@NotNull OfflinePlayer player, double amount) {
        return has(player.getUniqueId(), amount);
    }

    /**
     * 检查玩家是否拥有足够余额
     */
    public boolean has(@NotNull UUID playerId, double amount) {
        return getBalance(playerId) >= amount;
    }

    /**
     * 转账
     */
    public boolean transfer(@NotNull OfflinePlayer from, @NotNull OfflinePlayer to, double amount) {
        return transfer(from.getUniqueId(), to.getUniqueId(), amount);
    }

    /**
     * 转账
     */
    public boolean transfer(@NotNull UUID fromId, @NotNull UUID toId, double amount) {
        if (amount <= 0 || fromId.equals(toId)) {
            return false;
        }
        if (!has(fromId, amount)) {
            return false;
        }

        double fromBefore = getBalance(fromId);
        double toBefore = getBalance(toId);
        double fromAfter = round(fromBefore - amount);
        double toAfter = round(toBefore + amount);

        // 事务保证原子性
        try (Connection conn = databaseManager.getConnection()) {
            conn.setAutoCommit(false);
            try {
                upsertBalanceConn(conn, fromId, fromAfter);
                upsertBalanceConn(conn, toId, toAfter);
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("转账失败: " + e.getMessage());
            return false;
        }
        // 事务提交成功后更新内存缓存
        balanceCache.put(fromId, fromAfter);
        balanceCache.put(toId, toAfter);

        OfflinePlayer fromPlayer = Bukkit.getOfflinePlayer(fromId);
        OfflinePlayer toPlayer = Bukkit.getOfflinePlayer(toId);

        callTransactionEvent(fromPlayer, EconomyTransactionEvent.TransactionType.TRANSFER_OUT,
                amount, fromBefore, fromAfter);
        callTransactionEvent(toPlayer, EconomyTransactionEvent.TransactionType.TRANSFER_IN,
                amount, toBefore, toAfter);
        return true;
    }

    /**
     * 在指定连接上更新余额（用于事务）
     */
    private void upsertBalanceConn(@NotNull Connection conn, @NotNull UUID playerId, double balance)
            throws SQLException {
        String sql = """
                INSERT INTO economy_balance (player_uuid, balance)
                VALUES (?, ?)
                ON CONFLICT(player_uuid) DO UPDATE SET balance = excluded.balance;
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerId.toString());
            ps.setDouble(2, balance);
            ps.executeUpdate();
        }
    }

    /**
     * 格式化金额显示
     */
    @NotNull
    public String format(double amount) {
        BigDecimal bd = new BigDecimal(amount).setScale(2, RoundingMode.HALF_UP);
        return currencySymbol + bd.toPlainString();
    }

    /**
     * 获取货币符号
     */
    @NotNull
    public String getCurrencySymbol() {
        return currencySymbol;
    }

    /**
     * 四舍五入到两位小数
     */
    private double round(double amount) {
        return BigDecimal.valueOf(amount).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    /**
     * 触发交易事件（异步执行）
     */
    private void callTransactionEvent(@NotNull OfflinePlayer player,
                                      @NotNull EconomyTransactionEvent.TransactionType type,
                                      double amount, double before, double after) {
        CompletableFuture.runAsync(() -> {
            EconomyTransactionEvent event = new EconomyTransactionEvent(player, type, amount, before, after);
            Bukkit.getScheduler().runTask(plugin, () -> Bukkit.getPluginManager().callEvent(event));
        });
    }
}
