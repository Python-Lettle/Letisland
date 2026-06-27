package cn.lettle.letisland.database;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * 一次性数据迁移工具
 * 将旧版YAML文件中的玩家运行时数据导入SQLite数据库
 * 通过 schema_meta 表中的迁移标记保证只执行一次
 *
 * 迁移范围：
 *   - economy.yml  players.<uuid>.balance
 *   - fishing.yml  players.<uuid>.level / exp / auto-sell-tier / codex.<fishId>.{count,max-weight}
 *   - titles.yml   players.<uuid>.unlocked[] / current
 *
 * 设计原则：
 *   - 幂等：全部使用 UPSERT / INSERT OR IGNORE，重跑安全
 *   - 容错：单个系统失败不影响其他系统；UUID 非法时跳过该条
 *   - 保留原文件：迁移后不删除 YAML，仅 players.* 段变为死数据（配置段仍被使用）
 */
public class YamlMigrator {

    /** 迁移标记 key，存在即代表迁移已完成 */
    private static final String MIGRATION_KEY = "yaml_to_sqlite_v1";

    private final JavaPlugin plugin;
    private final DatabaseManager databaseManager;
    private final Logger logger;

    public YamlMigrator(@NotNull JavaPlugin plugin, @NotNull DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
        this.logger = plugin.getLogger();
    }

    /**
     * 执行迁移（若尚未执行过）
     */
    public void migrateIfNeeded() {
        ensureMetaTable();
        if (isMigrated()) {
            return;
        }
        logger.info("检测到首次启动，开始从 YAML 迁移玩家数据到 SQLite ...");
        int total = 0;
        try {
            total += migrateEconomy();
            total += migrateFishing();
            total += migrateTitles();
        } catch (Exception e) {
            logger.warning("YAML 数据迁移过程中出错（已迁移部分仍有效）: " + e.getMessage());
        } finally {
            // 即使中途出错也标记完成，避免每次启动都重试失败的迁移
            markMigrated();
        }
        if (total > 0) {
            logger.info("YAML 数据迁移完成，共迁移 " + total + " 条玩家记录");
        } else {
            logger.info("YAML 数据迁移完成（无玩家数据需要迁移）");
        }
    }

    // ==================== 迁移标记 ====================

    private void ensureMetaTable() {
        try (Connection conn = databaseManager.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS schema_meta (
                        key   VARCHAR(64) PRIMARY KEY,
                        value VARCHAR(255)
                    );
                    """);
        } catch (SQLException e) {
            logger.warning("创建 schema_meta 表失败: " + e.getMessage());
        }
    }

    private boolean isMigrated() {
        String sql = "SELECT value FROM schema_meta WHERE key = ?";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, MIGRATION_KEY);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            return false;
        }
    }

    private void markMigrated() {
        String sql = "INSERT OR IGNORE INTO schema_meta (key, value) VALUES (?, ?)";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, MIGRATION_KEY);
            ps.setString(2, "done");
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.warning("标记迁移完成失败: " + e.getMessage());
        }
    }

    // ==================== 各系统迁移 ====================

    /**
     * 迁移经济系统玩家余额
     */
    private int migrateEconomy() {
        File file = new File(plugin.getDataFolder(), "economy.yml");
        if (!file.exists()) return 0;
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection players = cfg.getConfigurationSection("players");
        if (players == null) return 0;

        String sql = """
                INSERT INTO economy_balance (player_uuid, balance)
                VALUES (?, ?)
                ON CONFLICT(player_uuid) DO UPDATE SET balance = excluded.balance;
                """;
        int count = 0;
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (String uuidStr : players.getKeys(false)) {
                if (!isValidUuid(uuidStr)) continue;
                double balance = cfg.getDouble("players." + uuidStr + ".balance", 0.0);
                ps.setString(1, uuidStr);
                ps.setDouble(2, balance);
                ps.addBatch();
                count++;
            }
            if (count > 0) ps.executeBatch();
        } catch (SQLException e) {
            logger.warning("迁移经济数据失败: " + e.getMessage());
        }
        if (count > 0) logger.info("  - 经济数据: 迁移 " + count + " 个账户");
        return count;
    }

    /**
     * 迁移钓鱼系统玩家数据 + 图鉴
     */
    private int migrateFishing() {
        File file = new File(plugin.getDataFolder(), "fishing.yml");
        if (!file.exists()) return 0;
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection players = cfg.getConfigurationSection("players");
        if (players == null) return 0;

        String playerSql = """
                INSERT INTO fishing_player (player_uuid, level, exp, auto_sell_tier)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(player_uuid) DO UPDATE SET
                    level = excluded.level,
                    exp = excluded.exp,
                    auto_sell_tier = excluded.auto_sell_tier;
                """;
        String codexSql = """
                INSERT INTO fishing_codex (player_uuid, fish_id, catch_count, max_weight)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(player_uuid, fish_id) DO UPDATE SET
                    catch_count = excluded.catch_count,
                    max_weight = excluded.max_weight;
                """;
        int playerCount = 0;
        int codexCount = 0;
        try (Connection conn = databaseManager.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement pps = conn.prepareStatement(playerSql);
                 PreparedStatement cps = conn.prepareStatement(codexSql)) {
                for (String uuidStr : players.getKeys(false)) {
                    if (!isValidUuid(uuidStr)) continue;
                    String base = "players." + uuidStr;
                    int level = cfg.getInt(base + ".level", 1);
                    int exp = cfg.getInt(base + ".exp", 0);
                    int autoSellTier = cfg.getInt(base + ".auto-sell-tier", 0);

                    pps.setString(1, uuidStr);
                    pps.setInt(2, level);
                    pps.setInt(3, exp);
                    pps.setInt(4, autoSellTier);
                    pps.addBatch();
                    playerCount++;

                    // 图鉴数据
                    ConfigurationSection codex = cfg.getConfigurationSection(base + ".codex");
                    if (codex != null) {
                        for (String fishId : codex.getKeys(false)) {
                            int cnt = cfg.getInt(base + ".codex." + fishId + ".count", 0);
                            double maxW = cfg.getDouble(base + ".codex." + fishId + ".max-weight", 0.0);
                            if (cnt <= 0) continue;
                            cps.setString(1, uuidStr);
                            cps.setString(2, fishId);
                            cps.setInt(3, cnt);
                            cps.setDouble(4, maxW);
                            cps.addBatch();
                            codexCount++;
                        }
                    }
                }
                if (playerCount > 0) pps.executeBatch();
                if (codexCount > 0) cps.executeBatch();
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            logger.warning("迁移钓鱼数据失败: " + e.getMessage());
        }
        if (playerCount > 0 || codexCount > 0) {
            logger.info("  - 钓鱼数据: 迁移 " + playerCount + " 个玩家, " + codexCount + " 条图鉴记录");
        }
        return playerCount + codexCount;
    }

    /**
     * 迁移称号系统已解锁称号 + 当前佩戴称号
     */
    private int migrateTitles() {
        File file = new File(plugin.getDataFolder(), "titles.yml");
        if (!file.exists()) return 0;
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection players = cfg.getConfigurationSection("players");
        if (players == null) return 0;

        String unlockedSql = """
                INSERT OR IGNORE INTO player_unlocked_titles (player_uuid, title_id)
                VALUES (?, ?);
                """;
        String currentSql = """
                INSERT INTO player_current_title (player_uuid, title_id)
                VALUES (?, ?)
                ON CONFLICT(player_uuid) DO UPDATE SET title_id = excluded.title_id;
                """;
        int unlockedCount = 0;
        int currentCount = 0;
        try (Connection conn = databaseManager.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ups = conn.prepareStatement(unlockedSql);
                 PreparedStatement cps = conn.prepareStatement(currentSql)) {
                for (String uuidStr : players.getKeys(false)) {
                    if (!isValidUuid(uuidStr)) continue;
                    String base = "players." + uuidStr;

                    List<String> unlocked = cfg.getStringList(base + ".unlocked");
                    for (String titleId : unlocked) {
                        if (titleId == null || titleId.isEmpty()) continue;
                        ups.setString(1, uuidStr);
                        ups.setString(2, titleId);
                        ups.addBatch();
                        unlockedCount++;
                    }

                    String current = cfg.getString(base + ".current");
                    if (current != null && !current.isEmpty()) {
                        cps.setString(1, uuidStr);
                        cps.setString(2, current);
                        cps.addBatch();
                        currentCount++;
                    }
                }
                if (unlockedCount > 0) ups.executeBatch();
                if (currentCount > 0) cps.executeBatch();
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            logger.warning("迁移称号数据失败: " + e.getMessage());
        }
        if (unlockedCount > 0 || currentCount > 0) {
            logger.info("  - 称号数据: 迁移 " + unlockedCount + " 条解锁记录, " + currentCount + " 条当前称号");
        }
        return unlockedCount + currentCount;
    }

    // ==================== 工具方法 ====================

    private boolean isValidUuid(String uuidStr) {
        try {
            UUID.fromString(uuidStr);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
