package cn.lettle.letisland.home;

import cn.lettle.letisland.database.DatabaseManager;
import cn.lettle.letisland.log.LogManager;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 家园系统核心管理器
 * 管理家园、成员、仓库、贡献值、等级升级、设施配置
 * 数据持久化在 SQLite，热路径字段（玩家→家园、家园等级、贡献值）走写穿缓存
 */
public class HomeManager {

    private final JavaPlugin plugin;
    private final DatabaseManager databaseManager;
    private final LogManager logManager;
    private final File homeFile;
    private FileConfiguration homeConfig;

    private boolean enabled;

    private int nameMinLength = 2;
    private int nameMaxLength = 12;
    private int maxLevel = 3;

    /** 贡献值倍率：material → 每个物品给多少贡献值 */
    private final Map<Material, Long> contributionRates = new HashMap<>();
    /** 等级升级所需资源：等级 → (material → amount) */
    private final Map<Integer, Map<Material, Long>> levelRequirements = new LinkedHashMap<>();
    /** 仓库允许放入的材料白名单（contribution-rates + level-requirements 的并集） */
    private final java.util.Set<Material> allowedWarehouseMaterials = new java.util.HashSet<>();

    /** 魔法台配置 */
    private double magicTableCostCoins = 100.0;
    private long magicTableCostContribution = 100;
    private final List<MagicTablePrize> magicTablePrizes = new ArrayList<>();
    private double magicTableTotalWeight = 0;

    /** 磨石配方 */
    private final List<GrindstoneRecipe> grindstoneRecipes = new ArrayList<>();

    /** 玩家→家园ID 缓存（Optional.empty 表示已缓存且无家园） */
    private final Map<UUID, Optional<Integer>> playerHomelandCache = new ConcurrentHashMap<>();
    /** 家园ID→等级 缓存 */
    private final Map<Integer, Integer> homelandLevelCache = new ConcurrentHashMap<>();
    /** 玩家→贡献值 缓存 */
    private final Map<UUID, Long> contributionCache = new ConcurrentHashMap<>();

    public HomeManager(@NotNull JavaPlugin plugin, @NotNull DatabaseManager databaseManager,
                       @NotNull LogManager logManager) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
        this.logManager = logManager;
        this.homeFile = new File(plugin.getDataFolder(), "home.yml");
        loadConfig();
    }

    // ==================== 配置加载 ====================

    public void loadConfig() {
        if (!homeFile.exists()) {
            plugin.saveResource("home.yml", false);
        }
        homeConfig = YamlConfiguration.loadConfiguration(homeFile);
        parseConfig();
    }

    public void reload() {
        loadConfig();
        playerHomelandCache.clear();
        homelandLevelCache.clear();
        contributionCache.clear();
    }

    /** 玩家退出时清理相关缓存（homelandLevelCache 按家园ID索引，不在此清理） */
    public void evictCache(@NotNull UUID uuid) {
        playerHomelandCache.remove(uuid);
        contributionCache.remove(uuid);
    }

    private void parseConfig() {
        contributionRates.clear();
        levelRequirements.clear();
        magicTablePrizes.clear();
        grindstoneRecipes.clear();

        ConfigurationSection homeland = homeConfig.getConfigurationSection("homeland");
        if (homeland == null) return;

        enabled = homeland.getBoolean("enabled", true);
        nameMinLength = homeland.getInt("name.min-length", 2);
        nameMaxLength = homeland.getInt("name.max-length", 12);
        maxLevel = homeland.getInt("max-level", 3);

        // 贡献值倍率
        ConfigurationSection ratesSection = homeland.getConfigurationSection("contribution-rates");
        if (ratesSection != null) {
            for (String key : ratesSection.getKeys(false)) {
                Material mat = parseMaterial(key);
                if (mat != null) {
                    contributionRates.put(mat, (long) ratesSection.getDouble(key, 1.0));
                } else {
                    plugin.getLogger().warning("home.yml contribution-rates 中未知材质: " + key);
                }
            }
        }

        // 等级升级所需资源
        ConfigurationSection levelSection = homeland.getConfigurationSection("level-requirements");
        if (levelSection != null) {
            for (String key : levelSection.getKeys(false)) {
                int level = Integer.parseInt(key);
                ConfigurationSection lvlSection = levelSection.getConfigurationSection(key);
                if (lvlSection == null) continue;
                Map<Material, Long> reqs = new LinkedHashMap<>();
                for (String matKey : lvlSection.getKeys(false)) {
                    Material mat = parseMaterial(matKey);
                    if (mat != null) {
                        reqs.put(mat, lvlSection.getLong(matKey));
                    } else {
                        plugin.getLogger().warning("home.yml level-requirements." + key + " 中未知材质: " + matKey);
                    }
                }
                levelRequirements.put(level, reqs);
            }
        }

        // 构建仓库允许材料白名单（有贡献值 或 升级所需）
        allowedWarehouseMaterials.clear();
        allowedWarehouseMaterials.addAll(contributionRates.keySet());
        for (Map<Material, Long> reqs : levelRequirements.values()) {
            allowedWarehouseMaterials.addAll(reqs.keySet());
        }

        // 魔法台
        ConfigurationSection magicSection = homeConfig.getConfigurationSection("magic-table");
        if (magicSection != null) {
            magicTableCostCoins = magicSection.getDouble("cost-coins", 100.0);
            magicTableCostContribution = magicSection.getLong("cost-contribution", 100);
            for (Map<?, ?> map : magicSection.getMapList("prizes")) {
                String matStr = (String) map.get("material");
                int amount = map.get("amount") instanceof Number ? ((Number) map.get("amount")).intValue() : 1;
                double weight = map.get("weight") instanceof Number ? ((Number) map.get("weight")).doubleValue() : 1.0;
                Material mat = parseMaterial(matStr);
                if (mat != null) {
                    magicTablePrizes.add(new MagicTablePrize(mat, amount, weight));
                } else {
                    plugin.getLogger().warning("home.yml magic-table.prizes 中未知材质: " + matStr);
                }
            }
        }
        // 预计算魔法台奖品总权重（配置加载后不变）
        magicTableTotalWeight = 0;
        for (MagicTablePrize p : magicTablePrizes) magicTableTotalWeight += p.weight();

        // 磨石
        ConfigurationSection grindstoneSection = homeConfig.getConfigurationSection("grindstone");
        if (grindstoneSection != null) {
            for (Map<?, ?> map : grindstoneSection.getMapList("recipes")) {
                String inStr = (String) map.get("input");
                String outStr = (String) map.get("output");
                int inputNum = map.get("input_num") instanceof Number ? ((Number) map.get("input_num")).intValue() : 1;
                int outputNum = map.get("output_num") instanceof Number ? ((Number) map.get("output_num")).intValue() : 1;
                if (inputNum <= 0 || outputNum <= 0) {
                    plugin.getLogger().warning("home.yml grindstone.recipes 中 input_num/output_num 必须为正整数: input=" + inStr + ", output=" + outStr);
                    continue;
                }
                Material inMat = parseMaterial(inStr);
                Material outMat = parseMaterial(outStr);
                if (inMat != null && outMat != null) {
                    grindstoneRecipes.add(new GrindstoneRecipe(inMat, outMat, inputNum, outputNum));
                } else {
                    plugin.getLogger().warning("home.yml grindstone.recipes 中未知材质: input=" + inStr + ", output=" + outStr);
                }
            }
        }
    }

    /** 严格解析材质枚举名（大小写不敏感），无效返回 null */
    @org.jetbrains.annotations.Nullable
    private Material parseMaterial(@NotNull String name) {
        try {
            return Material.valueOf(name.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ==================== 家园 CRUD ====================

    /**
     * 创建家园
     * @return 创建结果（含家园ID或失败原因）
     */
    @NotNull
    public CreateResult createHomeland(@NotNull UUID ownerUuid, @NotNull String ownerName, @NotNull String name) {
        if (!enabled) return new CreateResult(false, -1, "§c家园系统未启用");
        if (name.length() < nameMinLength || name.length() > nameMaxLength) {
            return new CreateResult(false, -1, "§c家园名长度需为 §e" + nameMinLength + "-" + nameMaxLength + " §c字");
        }
        // 玩家必须不属于任何家园
        if (getHomelandByMember(ownerUuid).isPresent()) {
            return new CreateResult(false, -1, "§c你已属于一个家园，请先 §e/hd leave §c离开");
        }
        // 重名检查
        if (getHomelandByName(name) != null) {
            return new CreateResult(false, -1, "§c该家园名已被占用");
        }
        String sql = "INSERT INTO homelands (name, owner_uuid, owner_name, level) VALUES (?, ?, ?, 1)";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name);
            ps.setString(2, ownerUuid.toString());
            ps.setString(3, ownerName);
            ps.executeUpdate();
            int homelandId;
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    homelandId = rs.getInt(1);
                } else {
                    return new CreateResult(false, -1, "§c创建失败：未获取到家园ID");
                }
            }
            // 创建者自动加入家园（贡献值0）
            addMember(homelandId, ownerUuid, ownerName);
            // 缓存
            playerHomelandCache.put(ownerUuid, Optional.of(homelandId));
            homelandLevelCache.put(homelandId, 1);
            contributionCache.put(ownerUuid, 0L);
            return new CreateResult(true, homelandId, "§a家园 §6" + name + " §a创建成功！");
        } catch (SQLException e) {
            plugin.getLogger().warning("创建家园失败: " + e.getMessage());
            return new CreateResult(false, -1, "§c创建失败（数据库错误）");
        }
    }

    @Nullable
    public HomelandInfo getHomeland(int homelandId) {
        String sql = "SELECT id, name, owner_uuid, owner_name, level, created_at FROM homelands WHERE id = ?";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, homelandId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new HomelandInfo(
                            rs.getInt("id"),
                            rs.getString("name"),
                            rs.getString("owner_uuid"),
                            rs.getString("owner_name"),
                            rs.getInt("level"),
                            rs.getTimestamp("created_at")
                    );
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("查询家园失败: " + e.getMessage());
        }
        return null;
    }

    @Nullable
    public HomelandInfo getHomelandByName(@NotNull String name) {
        String sql = "SELECT id, name, owner_uuid, owner_name, level, created_at FROM homelands WHERE name = ?";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new HomelandInfo(
                            rs.getInt("id"),
                            rs.getString("name"),
                            rs.getString("owner_uuid"),
                            rs.getString("owner_name"),
                            rs.getInt("level"),
                            rs.getTimestamp("created_at")
                    );
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("按名查询家园失败: " + e.getMessage());
        }
        return null;
    }

    /**
     * 查询玩家所属家园ID（带缓存）
     */
    @NotNull
    public Optional<Integer> getHomelandByMember(@NotNull UUID playerUuid) {
        Optional<Integer> cached = playerHomelandCache.get(playerUuid);
        if (cached != null) return cached;
        String sql = "SELECT homeland_id FROM homeland_members WHERE player_uuid = ?";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    int id = rs.getInt("homeland_id");
                    playerHomelandCache.put(playerUuid, Optional.of(id));
                    return Optional.of(id);
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("查询玩家家园失败: " + e.getMessage());
        }
        playerHomelandCache.put(playerUuid, Optional.empty());
        return Optional.empty();
    }

    public int getLevel(int homelandId) {
        Integer cached = homelandLevelCache.get(homelandId);
        if (cached != null) return cached;
        String sql = "SELECT level FROM homelands WHERE id = ?";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, homelandId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    int lvl = rs.getInt("level");
                    homelandLevelCache.put(homelandId, lvl);
                    return lvl;
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("查询家园等级失败: " + e.getMessage());
        }
        return 1;
    }

    public boolean isMaxLevel(int homelandId) {
        return getLevel(homelandId) >= maxLevel;
    }

    /**
     * 管理员强制设置家园等级（不扣资源）
     */
    public boolean setHomelandLevel(int homelandId, int level) {
        if (level < 1 || level > maxLevel) return false;
        String sql = "UPDATE homelands SET level = ? WHERE id = ?";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, level);
            ps.setInt(2, homelandId);
            int rows = ps.executeUpdate();
            if (rows > 0) {
                homelandLevelCache.put(homelandId, level);
                return true;
            }
            return false;
        } catch (SQLException e) {
            plugin.getLogger().warning("设置家园等级失败: " + e.getMessage());
            return false;
        }
    }

    // ==================== 成员管理 ====================

    @NotNull
    public List<MemberInfo> getMembers(int homelandId) {
        List<MemberInfo> result = new ArrayList<>();
        String sql = "SELECT player_uuid, player_name, homeland_id, contribution, joined_at FROM homeland_members WHERE homeland_id = ?";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, homelandId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new MemberInfo(
                            UUID.fromString(rs.getString("player_uuid")),
                            rs.getString("player_name"),
                            rs.getInt("homeland_id"),
                            rs.getLong("contribution"),
                            rs.getTimestamp("joined_at")
                    ));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("查询成员列表失败: " + e.getMessage());
        }
        return result;
    }

    public int getMemberCount(int homelandId) {
        String sql = "SELECT COUNT(*) AS cnt FROM homeland_members WHERE homeland_id = ?";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, homelandId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt("cnt");
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("查询成员数量失败: " + e.getMessage());
        }
        return 0;
    }

    /**
     * 加入家园（不做重复检查，调用方需保证玩家不在其他家园）
     */
    public boolean addMember(int homelandId, @NotNull UUID playerUuid, @NotNull String playerName) {
        String sql = """
                INSERT INTO homeland_members (player_uuid, player_name, homeland_id, contribution)
                VALUES (?, ?, ?, 0)
                ON CONFLICT(player_uuid) DO UPDATE SET homeland_id = excluded.homeland_id, player_name = excluded.player_name;
                """;
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerUuid.toString());
            ps.setString(2, playerName);
            ps.setInt(3, homelandId);
            ps.executeUpdate();
            playerHomelandCache.put(playerUuid, Optional.of(homelandId));
            contributionCache.put(playerUuid, 0L);
            return true;
        } catch (SQLException e) {
            plugin.getLogger().warning("加入家园失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * 离开家园
     * @return 离开结果（false 表示拥有者不能直接离开等）
     */
    @NotNull
    public LeaveResult removeMember(@NotNull UUID playerUuid) {
        Optional<Integer> opt = getHomelandByMember(playerUuid);
        if (opt.isEmpty()) {
            return new LeaveResult(false, "§c你当前不属于任何家园");
        }
        int homelandId = opt.get();
        HomelandInfo home = getHomeland(homelandId);
        if (home == null) {
            return new LeaveResult(false, "§c家园数据异常");
        }
        // 拥有者不可直接离开
        if (home.ownerUuid().equals(playerUuid.toString())) {
            return new LeaveResult(false, "§c家园拥有者不能直接离开，请先 §e/hd disband §c解散家园");
        }
        String sql = "DELETE FROM homeland_members WHERE player_uuid = ?";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerUuid.toString());
            ps.executeUpdate();
            playerHomelandCache.put(playerUuid, Optional.empty());
            contributionCache.remove(playerUuid);
            return new LeaveResult(true, "§a已离开家园 §6" + home.name());
        } catch (SQLException e) {
            plugin.getLogger().warning("离开家园失败: " + e.getMessage());
            return new LeaveResult(false, "§c离开失败（数据库错误）");
        }
    }

    /**
     * 解散家园（仅拥有者，删除家园及所有成员关系和仓库数据）
     */
    public boolean disbandHomeland(int homelandId, @NotNull UUID requesterUuid) {
        HomelandInfo home = getHomeland(homelandId);
        if (home == null) return false;
        if (!home.ownerUuid().equals(requesterUuid.toString())) return false;
        // 清理所有成员缓存
        List<MemberInfo> members = getMembers(homelandId);
        try (Connection conn = databaseManager.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement delMembers = conn.prepareStatement("DELETE FROM homeland_members WHERE homeland_id = ?");
                 PreparedStatement delWarehouse = conn.prepareStatement("DELETE FROM homeland_warehouse WHERE homeland_id = ?");
                 PreparedStatement delHome = conn.prepareStatement("DELETE FROM homelands WHERE id = ?")) {
                delMembers.setInt(1, homelandId);
                delMembers.executeUpdate();
                delWarehouse.setInt(1, homelandId);
                delWarehouse.executeUpdate();
                delHome.setInt(1, homelandId);
                delHome.executeUpdate();
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("解散家园失败: " + e.getMessage());
            return false;
        }
        // 清理缓存
        for (MemberInfo m : members) {
            playerHomelandCache.put(m.playerUuid(), Optional.empty());
            contributionCache.remove(m.playerUuid());
        }
        homelandLevelCache.remove(homelandId);
        return true;
    }

    // ==================== 仓库 ====================

    @NotNull
    public Map<Material, Long> getWarehouse(int homelandId) {
        Map<Material, Long> result = new LinkedHashMap<>();
        String sql = "SELECT material, amount FROM homeland_warehouse WHERE homeland_id = ? AND amount > 0";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, homelandId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Material mat = parseMaterial(rs.getString("material"));
                    if (mat != null) {
                        result.put(mat, rs.getLong("amount"));
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("查询仓库失败: " + e.getMessage());
        }
        return result;
    }

    public void addItem(int homelandId, @NotNull Material material, long amount) {
        if (amount <= 0) return;
        String sql = """
                INSERT INTO homeland_warehouse (homeland_id, material, amount)
                VALUES (?, ?, ?)
                ON CONFLICT(homeland_id, material) DO UPDATE SET amount = amount + excluded.amount;
                """;
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, homelandId);
            ps.setString(2, material.name());
            ps.setLong(3, amount);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("仓库入库失败: " + e.getMessage());
        }
    }

    /**
     * 批量入库多种材料（单事务，避免逐条 UPSERT 的多次 DB 写入）
     */
    public void addItemsBatch(int homelandId, @NotNull Map<Material, Long> items) {
        if (items.isEmpty()) return;
        String sql = """
                INSERT INTO homeland_warehouse (homeland_id, material, amount)
                VALUES (?, ?, ?)
                ON CONFLICT(homeland_id, material) DO UPDATE SET amount = amount + excluded.amount;
                """;
        try (Connection conn = databaseManager.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (Map.Entry<Material, Long> e : items.entrySet()) {
                    if (e.getValue() <= 0) continue;
                    ps.setInt(1, homelandId);
                    ps.setString(2, e.getKey().name());
                    ps.setLong(3, e.getValue());
                    ps.addBatch();
                }
                ps.executeBatch();
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                plugin.getLogger().warning("批量仓库入库失败: " + e.getMessage());
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("批量仓库入库连接失败: " + e.getMessage());
        }
    }

    public boolean hasResources(int homelandId, @NotNull Map<Material, Long> required) {
        Map<Material, Long> warehouse = getWarehouse(homelandId);
        for (Map.Entry<Material, Long> e : required.entrySet()) {
            Long have = warehouse.get(e.getKey());
            if (have == null || have < e.getValue()) return false;
        }
        return true;
    }

    /**
     * 批量扣除仓库资源（事务）
     */
    public boolean consumeResources(int homelandId, @NotNull Map<Material, Long> required) {
        String sql = """
                UPDATE homeland_warehouse
                SET amount = amount - ?
                WHERE homeland_id = ? AND material = ? AND amount >= ?
                """;
        try (Connection conn = databaseManager.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (Map.Entry<Material, Long> e : required.entrySet()) {
                    ps.setLong(1, e.getValue());
                    ps.setInt(2, homelandId);
                    ps.setString(3, e.getKey().name());
                    ps.setLong(4, e.getValue());
                    int rows = ps.executeUpdate();
                    if (rows == 0) {
                        // 资源不足或条件不满足，回滚
                        conn.rollback();
                        return false;
                    }
                }
                conn.commit();
                return true;
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("仓库扣资源失败: " + e.getMessage());
            return false;
        }
    }

    // ==================== 贡献值 ====================

    public long getContribution(@NotNull UUID playerUuid) {
        Long cached = contributionCache.get(playerUuid);
        if (cached != null) return cached;
        String sql = "SELECT contribution FROM homeland_members WHERE player_uuid = ?";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long val = rs.getLong("contribution");
                    contributionCache.put(playerUuid, val);
                    return val;
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("查询贡献值失败: " + e.getMessage());
        }
        contributionCache.put(playerUuid, 0L);
        return 0;
    }

    public void addContribution(@NotNull UUID playerUuid, long amount) {
        if (amount <= 0) return;
        String sql = """
                UPDATE homeland_members SET contribution = contribution + ?
                WHERE player_uuid = ?
                """;
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, amount);
            ps.setString(2, playerUuid.toString());
            int rows = ps.executeUpdate();
            if (rows > 0) {
                contributionCache.computeIfPresent(playerUuid, (k, v) -> v + amount);
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("增加贡献值失败: " + e.getMessage());
        }
    }

    /**
     * 扣除贡献值（事务保证不足时回滚）
     * @return true 表示扣除成功
     */
    public boolean spendContribution(@NotNull UUID playerUuid, long amount) {
        if (amount <= 0) return true;
        String sql = """
                UPDATE homeland_members SET contribution = contribution - ?
                WHERE player_uuid = ? AND contribution >= ?
                """;
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, amount);
            ps.setString(2, playerUuid.toString());
            ps.setLong(3, amount);
            int rows = ps.executeUpdate();
            if (rows > 0) {
                contributionCache.computeIfPresent(playerUuid, (k, v) -> v - amount);
                return true;
            }
            return false;
        } catch (SQLException e) {
            plugin.getLogger().warning("扣除贡献值失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * 根据材质计算提交物品应得的贡献值（未列出材质默认1点/个）
     */
    public long calcContribution(@NotNull Material material, int amount) {
        Long rate = contributionRates.get(material);
        return (rate != null ? rate : 1L) * amount;
    }

    /**
     * 判断该材质是否允许放入家园仓库（有贡献值或升级所需的材料）
     */
    public boolean isAllowedWarehouseMaterial(@NotNull Material material) {
        return allowedWarehouseMaterials.contains(material);
    }

    // ==================== 等级升级 ====================

    @NotNull
    public UpgradeResult upgradeLevel(int homelandId) {
        int currentLevel = getLevel(homelandId);
        if (currentLevel >= maxLevel) {
            return new UpgradeResult(false, "§c家园已达最高等级", currentLevel);
        }
        Map<Material, Long> required = levelRequirements.get(currentLevel + 1);
        if (required == null || required.isEmpty()) {
            return new UpgradeResult(false, "§c下一级资源需求未配置", currentLevel);
        }
        if (!hasResources(homelandId, required)) {
            return new UpgradeResult(false, "§c仓库资源不足，无法升级", currentLevel);
        }
        // 事务：扣资源 + 升等级
        try (Connection conn = databaseManager.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // 扣资源
                String consumeSql = """
                        UPDATE homeland_warehouse
                        SET amount = amount - ?
                        WHERE homeland_id = ? AND material = ? AND amount >= ?
                        """;
                try (PreparedStatement ps = conn.prepareStatement(consumeSql)) {
                    for (Map.Entry<Material, Long> e : required.entrySet()) {
                        ps.setLong(1, e.getValue());
                        ps.setInt(2, homelandId);
                        ps.setString(3, e.getKey().name());
                        ps.setLong(4, e.getValue());
                        int rows = ps.executeUpdate();
                        if (rows == 0) {
                            conn.rollback();
                            return new UpgradeResult(false, "§c仓库资源不足，无法升级", currentLevel);
                        }
                    }
                }
                // 升级
                String upgradeSql = "UPDATE homelands SET level = level + 1 WHERE id = ?";
                try (PreparedStatement ps = conn.prepareStatement(upgradeSql)) {
                    ps.setInt(1, homelandId);
                    ps.executeUpdate();
                }
                conn.commit();
                int newLevel = currentLevel + 1;
                homelandLevelCache.put(homelandId, newLevel);
                return new UpgradeResult(true, "§a家园升级成功！当前等级 §e" + newLevel, newLevel);
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("升级家园失败: " + e.getMessage());
            return new UpgradeResult(false, "§c升级失败（数据库错误）", currentLevel);
        }
    }

    @Nullable
    public Map<Material, Long> getLevelRequirement(int level) {
        return levelRequirements.get(level);
    }

    // ==================== 设施门控 ====================

    public boolean canUseMagicTable(int homelandId) {
        return getLevel(homelandId) >= 1;
    }

    public boolean canUseGrindstone(int homelandId) {
        return getLevel(homelandId) >= 2;
    }

    // ==================== 魔法台抽奖 ====================

    @NotNull
    public MagicTablePrize rollMagicTablePrize() {
        MagicTablePrize prize = cn.lettle.letisland.util.WeightedRandom.pick(
                magicTablePrizes, MagicTablePrize::weight, magicTableTotalWeight);
        return prize != null ? prize : magicTablePrizes.get(0);
    }

    @NotNull
    public MagicTablePrize getRandomPrizeForDisplay() {
        return rollMagicTablePrize();
    }

    public double getMagicTableCostCoins() { return magicTableCostCoins; }
    public long getMagicTableCostContribution() { return magicTableCostContribution; }

    // ==================== Getter ====================

    public boolean isEnabled() { return enabled; }
    public int getNameMinLength() { return nameMinLength; }
    public int getNameMaxLength() { return nameMaxLength; }
    public int getMaxLevel() { return maxLevel; }
    @NotNull public List<MagicTablePrize> getMagicTablePrizes() { return magicTablePrizes; }
    @NotNull public List<GrindstoneRecipe> getGrindstoneRecipes() { return grindstoneRecipes; }

    /**
     * 根据输入材质查询磨石配方
     */
    @Nullable
    public GrindstoneRecipe findGrindstoneRecipe(@NotNull Material input) {
        for (GrindstoneRecipe r : grindstoneRecipes) {
            if (r.input() == input) return r;
        }
        return null;
    }

    // ==================== 数据类 ====================

    public record HomelandInfo(int id, String name, String ownerUuid, String ownerName,
                               int level, java.sql.Timestamp createdAt) {}

    public record MemberInfo(UUID playerUuid, String playerName, int homelandId,
                             long contribution, java.sql.Timestamp joinedAt) {}

    public record MagicTablePrize(Material material, int amount, double weight) {}

    public record GrindstoneRecipe(Material input, Material output, int inputNum, int outputNum) {}

    public record CreateResult(boolean success, int homelandId, String message) {}

    public record LeaveResult(boolean success, String message) {}

    public record UpgradeResult(boolean success, String message, int newLevel) {}
}
