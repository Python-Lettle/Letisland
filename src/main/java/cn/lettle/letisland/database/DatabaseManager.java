package cn.lettle.letisland.database;

import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 数据库管理器（连接池模式）
 * 预创建固定数量的SQLite连接，getConnection() 从池中借用，
 * close() 归还到池中而非真正关闭，消除每操作创建连接的开销。
 *
 * 性能对比：
 *   旧方案：每次 getConnection() = 1次文件打开 + 4条PRAGMA ≈ 数毫秒
 *   新方案：每次 getConnection() = 队列poll ≈ 微秒级
 *
 * 线程安全：每个线程从池中获取独立连接，SQLite WAL模式支持并发读写。
 */
public class DatabaseManager {

    /** 连接池大小 */
    private static final int POOL_SIZE = 5;
    /** 借用连接超时时间（秒） */
    private static final long BORROW_TIMEOUT_SECONDS = 5;

    private final JavaPlugin plugin;
    private final File dbFile;
    private final String dbUrl;
    private final BlockingQueue<Connection> pool;
    private final List<Connection> realConnections;

    public DatabaseManager(@NotNull JavaPlugin plugin) {
        this.plugin = plugin;
        if (!plugin.getDataFolder().exists()) {
            plugin.getDataFolder().mkdirs();
        }
        this.dbFile = new File(plugin.getDataFolder(), "letisland.db");
        this.dbUrl = "jdbc:sqlite:" + dbFile.getAbsolutePath();
        this.pool = new ArrayBlockingQueue<>(POOL_SIZE);
        this.realConnections = new ArrayList<>(POOL_SIZE);

        try {
            // 预创建连接池
            for (int i = 0; i < POOL_SIZE; i++) {
                Connection conn = openRealConnection();
                realConnections.add(conn);
                pool.offer(conn);
            }
            // 借一个连接初始化表结构
            Connection conn = pool.take();
            try {
                initTables(conn);
            } finally {
                pool.offer(conn);
            }
        } catch (SQLException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new RuntimeException("初始化数据库失败: " + e.getMessage(), e);
        }
        plugin.getLogger().info("数据库已就绪: " + dbFile.getName() + " (连接池: " + POOL_SIZE + ")");
    }

    /**
     * 创建一个真实的SQLite连接并设置PRAGMA参数
     *
     * PRAGMA 说明：
     *   - journal_mode = WAL:  数据库级持久设置，允许读写并发
     *   - synchronous = NORMAL: 会话级，写入性能大幅提升
     *   - foreign_keys = ON:   会话级，启用外键约束
     *   - busy_timeout = 5000: 会话级，遇锁时最多等待5秒
     */
    @NotNull
    private Connection openRealConnection() throws SQLException {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException("SQLite JDBC 驱动未找到", e);
        }
        Connection conn = DriverManager.getConnection(dbUrl);
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA journal_mode = WAL;");
            stmt.execute("PRAGMA synchronous = NORMAL;");
            stmt.execute("PRAGMA foreign_keys = ON;");
            stmt.execute("PRAGMA busy_timeout = 5000;");
        } catch (SQLException e) {
            plugin.getLogger().warning("设置数据库参数失败: " + e.getMessage());
        }
        return conn;
    }

    /**
     * 从连接池借用一个连接
     * 返回的是代理对象，调用方使用 try-with-resources 关闭时，
     * 连接会归还到池中而非真正关闭
     */
    @NotNull
    public Connection getConnection() throws SQLException {
        try {
            Connection real = pool.poll(BORROW_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (real == null) {
                throw new SQLException("数据库连接池耗尽（等待超时 " + BORROW_TIMEOUT_SECONDS + "s）");
            }
            // 连接可能因异常已关闭，替换为新连接
            if (real.isClosed()) {
                real = openRealConnection();
                synchronized (realConnections) {
                    realConnections.add(real);
                }
            }
            final Connection conn = real;
            final AtomicBoolean returned = new AtomicBoolean(false);
            // 动态代理：close() 归还到池，其他方法委托给真实连接
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {
                        String name = method.getName();
                        if (name.equals("close")) {
                            if (returned.compareAndSet(false, true)) {
                                pool.offer(conn);
                            }
                            return null;
                        }
                        if (returned.get() && !name.equals("isClosed")) {
                            throw new SQLException("连接已归还到连接池，不能再使用");
                        }
                        return method.invoke(conn, args);
                    }
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SQLException("等待数据库连接时被中断", e);
        }
    }

    /**
     * 初始化所有数据表
     */
    private void initTables(@NotNull Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            // 经济系统 - 玩家余额
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS economy_balance (
                        player_uuid VARCHAR(36) PRIMARY KEY,
                        balance DOUBLE NOT NULL DEFAULT 0
                    );
                    """);

            // 钓鱼系统 - 玩家等级/经验/自动出售等级
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS fishing_player (
                        player_uuid VARCHAR(36) PRIMARY KEY,
                        level INT NOT NULL DEFAULT 1,
                        exp INT NOT NULL DEFAULT 0,
                        auto_sell_tier INT NOT NULL DEFAULT 0
                    );
                    """);

            // 钓鱼系统 - 鱼类图鉴（复合主键）
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS fishing_codex (
                        player_uuid VARCHAR(36) NOT NULL,
                        fish_id VARCHAR(64) NOT NULL,
                        catch_count INT NOT NULL DEFAULT 0,
                        max_weight DOUBLE NOT NULL DEFAULT 0,
                        PRIMARY KEY (player_uuid, fish_id)
                    );
                    """);

            // 称号系统 - 已解锁称号（复合主键）
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS player_unlocked_titles (
                        player_uuid VARCHAR(36) NOT NULL,
                        title_id VARCHAR(64) NOT NULL,
                        PRIMARY KEY (player_uuid, title_id)
                    );
                    """);

            // 称号系统 - 当前佩戴称号
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS player_current_title (
                        player_uuid VARCHAR(36) PRIMARY KEY,
                        title_id VARCHAR(64)
                    );
                    """);

            // 日志系统 - 统一日志表
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS plugin_logs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        log_type VARCHAR(32) NOT NULL,
                        player_uuid VARCHAR(36) NOT NULL,
                        player_name VARCHAR(64) NOT NULL,
                        ip VARCHAR(45),
                        detail VARCHAR(255),
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                    );
                    """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_logs_player ON plugin_logs(player_uuid);");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_logs_type ON plugin_logs(log_type);");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_logs_time ON plugin_logs(created_at);");

            // 安全系统 - IP 封禁记录（持久化，重启后仍生效）
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS security_blocks (
                        ip VARCHAR(45) PRIMARY KEY,
                        reason VARCHAR(255) NOT NULL,
                        blocked_until TIMESTAMP NOT NULL,
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                    );
                    """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_security_until ON security_blocks(blocked_until);");
        }
    }

    /**
     * 插件禁用时关闭所有真实连接
     */
    public void close() {
        for (Connection conn : realConnections) {
            try {
                if (!conn.isClosed()) {
                    conn.close();
                }
            } catch (SQLException ignored) {
            }
        }
        realConnections.clear();
        pool.clear();
        plugin.getLogger().info("数据库连接池已关闭");
    }
}
