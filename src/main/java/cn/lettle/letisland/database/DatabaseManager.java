package cn.lettle.letisland.database;

import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 数据库管理器
 * 负责SQLite数据库连接与所有运行时数据表的初始化
 * 统一存储经济、钓鱼、称号等系统的玩家数据
 *
 * 连接策略：
 *   getConnection() 每次返回一个独立的新连接，调用方使用 try-with-resources 关闭。
 *   SQLite 连接创建开销极小，WAL 模式支持并发读，每次操作短开短闭可避免连接状态错乱。
 *   各连接独立设置 PRAGMA（synchronous/foreign_keys 为会话级参数）。
 */
public class DatabaseManager {

    private final JavaPlugin plugin;
    private final File dbFile;
    private final String dbUrl;

    public DatabaseManager(@NotNull JavaPlugin plugin) {
        this.plugin = plugin;
        if (!plugin.getDataFolder().exists()) {
            plugin.getDataFolder().mkdirs();
        }
        this.dbFile = new File(plugin.getDataFolder(), "letisland.db");
        this.dbUrl = "jdbc:sqlite:" + dbFile.getAbsolutePath();
        // 使用一次性连接完成建表与WAL模式设置
        try (Connection conn = openConnection()) {
            initTables(conn);
        } catch (SQLException e) {
            throw new RuntimeException("初始化数据库失败: " + e.getMessage(), e);
        }
        plugin.getLogger().info("数据库已就绪: " + dbFile.getName());
    }

    /**
     * 创建并返回一个新的数据库连接，附带性能参数
     * SQLite JDBC 驱动随 Paper/Spigot 服务端一起分发，无需额外依赖
     *
     * PRAGMA 说明：
     *   - journal_mode = WAL:  数据库级持久设置，允许读写并发（首次设置后保持）
     *   - synchronous = NORMAL: 会话级，崩溃时可能损失最后几条事务，写入性能大幅提升
     *   - foreign_keys = ON:   会话级，启用外键约束
     *   - busy_timeout = 5000: 会话级，遇锁时最多等待5秒而非立即失败
     */
    @NotNull
    private Connection openConnection() throws SQLException {
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
     * 获取一个新的数据库连接
     * 调用方应使用 try-with-resources 关闭，无需复用
     */
    @NotNull
    public Connection getConnection() throws SQLException {
        return openConnection();
    }

    /**
     * 初始化所有数据表
     * 使用 IF NOT EXISTS 保证幂等性
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
        }
    }

    /**
     * 插件禁用时调用
     * 连接由调用方各自关闭，此处无需额外清理
     */
    public void close() {
        // 无持久化连接需要关闭；所有连接由调用方的 try-with-resources 管理
    }
}
