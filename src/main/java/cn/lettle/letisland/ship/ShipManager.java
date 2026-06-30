package cn.lettle.letisland.ship;

import cn.lettle.letisland.database.DatabaseManager;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 造船厂核心管理器
 * 管理玩家船只组件等级（玩家级数据，与家园解耦），加载 shipyard.yml 配置
 * 热路径（钓鱼/骑船加速）走 write-through 缓存
 */
public class ShipManager {

    public enum ComponentType { SAIL, OAR, HULL, NET }

    private final JavaPlugin plugin;
    private final DatabaseManager databaseManager;
    private final File shipyardFile;
    private FileConfiguration shipyardConfig;

    private boolean enabled;
    private int maxLevel;

    private final Map<ComponentType, ComponentConfig> components = new LinkedHashMap<>();

    /** 玩家→船只组件等级 缓存 */
    private final Map<UUID, ShipLevels> cache = new ConcurrentHashMap<>();

    public ShipManager(@NotNull JavaPlugin plugin, @NotNull DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
        this.shipyardFile = new File(plugin.getDataFolder(), "shipyard.yml");
        loadConfig();
    }

    // ==================== 配置加载 ====================

    public void loadConfig() {
        if (!shipyardFile.exists()) {
            plugin.saveResource("shipyard.yml", false);
        }
        shipyardConfig = YamlConfiguration.loadConfiguration(shipyardFile);
        parseConfig();
    }

    public void reload() {
        loadConfig();
        cache.clear();
    }

    /** 玩家退出时清理缓存 */
    public void evictCache(@NotNull UUID uuid) {
        cache.remove(uuid);
    }

    private void parseConfig() {
        components.clear();
        enabled = shipyardConfig.getBoolean("shipyard.enabled", true);
        maxLevel = shipyardConfig.getInt("shipyard.max-level", 5);

        ConfigurationSection componentsSection = shipyardConfig.getConfigurationSection("shipyard.components");
        if (componentsSection == null) return;

        for (ComponentType type : ComponentType.values()) {
            String key = type.name().toLowerCase();
            ConfigurationSection cs = componentsSection.getConfigurationSection(key);
            if (cs == null) {
                plugin.getLogger().warning("shipyard.yml 缺少组件配置: " + key);
                continue;
            }
            components.put(type, parseComponent(type, cs));
        }
    }

    private ComponentConfig parseComponent(@NotNull ComponentType type, @NotNull ConfigurationSection cs) {
        String name = cs.getString("name", type.name());
        Material material = parseMaterial(cs.getString("material", "PAPER"));

        Map<Integer, Map<Material, Long>> levels = new LinkedHashMap<>();
        ConfigurationSection levelsSection = cs.getConfigurationSection("levels");
        if (levelsSection != null) {
            for (String lvlKey : levelsSection.getKeys(false)) {
                int lvl;
                try {
                    lvl = Integer.parseInt(lvlKey);
                } catch (NumberFormatException e) {
                    plugin.getLogger().warning("shipyard.yml " + type + " levels 中非数字等级: " + lvlKey);
                    continue;
                }
                ConfigurationSection ls = levelsSection.getConfigurationSection(lvlKey);
                if (ls == null) continue;
                Map<Material, Long> cost = new LinkedHashMap<>();
                for (String matKey : ls.getKeys(false)) {
                    Material mat = parseMaterial(matKey);
                    if (mat != null) {
                        cost.put(mat, ls.getLong(matKey));
                    } else {
                        plugin.getLogger().warning("shipyard.yml " + type + " levels 中未知材质: " + matKey);
                    }
                }
                levels.put(lvl, cost);
            }
        }

        return new ComponentConfig(name, material, levels,
                cs.getInt("rarity-tier-threshold", 4),
                cs.getDouble("bonus-per-level", 0.0),
                cs.getDouble("max-speed-base", 0.4),
                cs.getDouble("max-speed-bonus-per-level", 0.0),
                cs.getDouble("boost-per-level", 0.0),
                cs.getDouble("value-bonus-per-level", 0.0),
                cs.getDouble("custom-fish-chance-bonus-per-level", 0.0));
    }

    @Nullable
    private Material parseMaterial(@NotNull String name) {
        try {
            return Material.valueOf(name.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ==================== 玩家数据查询 ====================

    @NotNull
    public ShipLevels getLevels(@NotNull UUID uuid) {
        ShipLevels cached = cache.get(uuid);
        if (cached != null) return cached;

        int sail = 0, oar = 0, hull = 0, net = 0;
        String sql = "SELECT sail_level, oar_level, hull_level, net_level FROM player_ship WHERE player_uuid = ?";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    sail = rs.getInt("sail_level");
                    oar = rs.getInt("oar_level");
                    hull = rs.getInt("hull_level");
                    net = rs.getInt("net_level");
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("查询玩家船只数据失败: " + e.getMessage());
        }
        ShipLevels levels = new ShipLevels(sail, oar, hull, net);
        cache.put(uuid, levels);
        return levels;
    }

    public int getComponentLevel(@NotNull UUID uuid, @NotNull ComponentType type) {
        return getLevels(uuid).get(type);
    }

    public int getSailLevel(@NotNull UUID uuid) { return getComponentLevel(uuid, ComponentType.SAIL); }
    public int getOarLevel(@NotNull UUID uuid) { return getComponentLevel(uuid, ComponentType.OAR); }
    public int getHullLevel(@NotNull UUID uuid) { return getComponentLevel(uuid, ComponentType.HULL); }
    public int getNetLevel(@NotNull UUID uuid) { return getComponentLevel(uuid, ComponentType.NET); }

    // ==================== 升级 ====================

    public int getMaxLevel() { return maxLevel; }

    /** 返回升级到 nextLevel 所需材料，满级或未配置返回 null */
    @Nullable
    public Map<Material, Long> getUpgradeCost(@NotNull ComponentType type, int nextLevel) {
        ComponentConfig cfg = components.get(type);
        if (cfg == null || nextLevel > maxLevel) return null;
        return cfg.levels().get(nextLevel);
    }

    @Nullable
    public ComponentConfig getComponentConfig(@NotNull ComponentType type) {
        return components.get(type);
    }

    /**
     * 升级组件等级（仅 DB 操作）。调用方应先校验并扣除仓库材料。
     * @return true 升级成功
     */
    public boolean upgradeComponent(@NotNull UUID uuid, @NotNull ComponentType type) {
        int current = getComponentLevel(uuid, type);
        if (current >= maxLevel) return false;

        // 列名来自枚举受控值，无注入风险
        String col = type.name().toLowerCase() + "_level";
        String insertSql = "INSERT OR IGNORE INTO player_ship (player_uuid) VALUES (?)";
        String updateSql = "UPDATE player_ship SET " + col + " = " + col + " + 1 " +
                "WHERE player_uuid = ? AND " + col + " < ?";
        try (Connection conn = databaseManager.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                ps.setString(1, uuid.toString());
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
                ps.setString(1, uuid.toString());
                ps.setInt(2, maxLevel);
                int rows = ps.executeUpdate();
                if (rows > 0) {
                    cache.put(uuid, getLevels(uuid).with(type, current + 1));
                    return true;
                }
                return false;
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("升级船只组件失败: " + e.getMessage());
            return false;
        }
    }

    // ==================== 船上检测 ====================

    public boolean isPlayerOnBoat(@NotNull Player player) {
        return player.isInsideVehicle() && player.getVehicle() instanceof Boat;
    }

    // ==================== 配置 Getter ====================

    public boolean isEnabled() { return enabled; }

    public int getSailRarityThreshold() {
        ComponentConfig c = components.get(ComponentType.SAIL);
        return c != null ? c.rarityTierThreshold() : 4;
    }

    public double getSailBonusPerLevel() {
        ComponentConfig c = components.get(ComponentType.SAIL);
        return c != null ? c.bonusPerLevel() : 0.0;
    }

    public double getOarMaxSpeedBase() {
        ComponentConfig c = components.get(ComponentType.OAR);
        return c != null ? c.maxSpeedBase() : 0.4;
    }

    public double getOarMaxSpeedBonusPerLevel() {
        ComponentConfig c = components.get(ComponentType.OAR);
        return c != null ? c.maxSpeedBonusPerLevel() : 0.0;
    }

    public double getOarBoostPerLevel() {
        ComponentConfig c = components.get(ComponentType.OAR);
        return c != null ? c.boostPerLevel() : 0.0;
    }

    public double getHullValueBonusPerLevel() {
        ComponentConfig c = components.get(ComponentType.HULL);
        return c != null ? c.valueBonusPerLevel() : 0.0;
    }

    public double getNetChanceBonusPerLevel() {
        ComponentConfig c = components.get(ComponentType.NET);
        return c != null ? c.customFishChanceBonusPerLevel() : 0.0;
    }

    // ==================== 数据类 ====================

    public record ShipLevels(int sail, int oar, int hull, int net) {
        public int get(@NotNull ComponentType type) {
            return switch (type) {
                case SAIL -> sail;
                case OAR -> oar;
                case HULL -> hull;
                case NET -> net;
            };
        }

        @NotNull
        public ShipLevels with(@NotNull ComponentType type, int newValue) {
            return switch (type) {
                case SAIL -> new ShipLevels(newValue, oar, hull, net);
                case OAR -> new ShipLevels(sail, newValue, hull, net);
                case HULL -> new ShipLevels(sail, oar, newValue, net);
                case NET -> new ShipLevels(sail, oar, hull, newValue);
            };
        }
    }

    public record ComponentConfig(
            @NotNull String name,
            @NotNull Material material,
            @NotNull Map<Integer, Map<Material, Long>> levels,
            int rarityTierThreshold,
            double bonusPerLevel,
            double maxSpeedBase,
            double maxSpeedBonusPerLevel,
            double boostPerLevel,
            double valueBonusPerLevel,
            double customFishChanceBonusPerLevel
    ) {}
}
