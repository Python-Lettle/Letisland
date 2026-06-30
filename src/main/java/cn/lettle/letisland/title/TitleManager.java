package cn.lettle.letisland.title;

import cn.lettle.letisland.database.DatabaseManager;
import cn.lettle.letisland.log.LogManager;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 称号系统核心管理器
 * 管理称号定义、玩家解锁状态及当前佩戴称号
 * 玩家运行时数据持久化在SQLite数据库中
 */
public class TitleManager {

    private final JavaPlugin plugin;
    private final DatabaseManager databaseManager;
    private final LogManager logManager;
    private final File titlesFile;
    private FileConfiguration titlesConfig;

    /** 系统是否启用 */
    private boolean enabled;

    /** 称号配置 */
    private final Map<String, TitleConfig> titleConfigs = new LinkedHashMap<>();

    /** 玩家当前称号内存缓存（聊天热路径优化）
     *  使用 Optional 包装：key 存在表示已缓存，Optional.empty() 表示已缓存且无称号 */
    private final Map<UUID, Optional<String>> currentTitleCache = new ConcurrentHashMap<>();

    public TitleManager(@NotNull JavaPlugin plugin, @NotNull DatabaseManager databaseManager,
                        @NotNull LogManager logManager) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
        this.logManager = logManager;
        this.titlesFile = new File(plugin.getDataFolder(), "titles.yml");
        loadConfig();
    }

    /** 玩家退出时清理当前称号缓存 */
    public void evictCache(@NotNull UUID uuid) {
        currentTitleCache.remove(uuid);
    }

    // ==================== 配置加载 ====================

    public void loadConfig() {
        if (!titlesFile.exists()) {
            plugin.saveResource("titles.yml", false);
        }
        titlesConfig = YamlConfiguration.loadConfiguration(titlesFile);
        parseConfig();
    }

    public void saveConfig() {
        try {
            titlesConfig.save(titlesFile);
        } catch (IOException e) {
            throw new RuntimeException("无法保存称号配置文件: " + e.getMessage(), e);
        }
    }

    public void reload() {
        loadConfig();
    }

    private void parseConfig() {
        titleConfigs.clear();
        enabled = titlesConfig.getBoolean("enabled", true);

        ConfigurationSection titlesSection = titlesConfig.getConfigurationSection("titles");
        if (titlesSection != null) {
            for (String id : titlesSection.getKeys(false)) {
                ConfigurationSection ts = titlesSection.getConfigurationSection(id);
                if (ts == null) continue;

                String name = ts.getString("name", id);
                String color = ts.getString("color", "§f");
                String description = ts.getString("description", "");
                String unlockMethod = ts.getString("unlock-method", "未知");

                List<UnlockItem> unlockItems = new ArrayList<>();
                for (Map<?, ?> map : ts.getMapList("unlock-items")) {
                    String materialStr = (String) map.get("material");
                    int amount = (int) map.get("amount");
                    Material material = Material.matchMaterial(materialStr);
                    if (material != null) {
                        String displayName = (String) map.get("display-name");
                        if (displayName == null) {
                            displayName = materialStr;
                        }
                        unlockItems.add(new UnlockItem(material, amount, displayName));
                    }
                }

                titleConfigs.put(id, new TitleConfig(id, name, color, description, unlockMethod, unlockItems));
            }
        }
    }

    // ==================== 玩家数据 ====================

    public boolean isUnlocked(@NotNull UUID playerId, @NotNull String titleId) {
        String sql = "SELECT 1 FROM player_unlocked_titles WHERE player_uuid = ? AND title_id = ?";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerId.toString());
            ps.setString(2, titleId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("查询称号解锁状态失败: " + e.getMessage());
            return false;
        }
    }

    @NotNull
    public List<String> getUnlockedTitles(@NotNull UUID playerId) {
        String sql = "SELECT title_id FROM player_unlocked_titles WHERE player_uuid = ?";
        List<String> result = new ArrayList<>();
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(rs.getString("title_id"));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("查询已解锁称号列表失败: " + e.getMessage());
        }
        return result;
    }

    public void unlock(@NotNull UUID playerId, @NotNull String titleId) {
        if (isUnlocked(playerId, titleId)) {
            return;
        }
        String sql = "INSERT OR IGNORE INTO player_unlocked_titles (player_uuid, title_id) VALUES (?, ?)";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerId.toString());
            ps.setString(2, titleId);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("解锁称号失败: " + e.getMessage());
            return;
        }
        // 记录称号解锁日志
        TitleConfig title = titleConfigs.get(titleId);
        String titleName = title != null ? title.getName() : titleId;
        logManager.logCodexTitle(playerId, titleId, titleName);
    }

    @Nullable
    public String getCurrentTitle(@NotNull UUID playerId) {
        // 缓存命中检查：key 存在表示已缓存，Optional.empty() 代表已缓存且无称号
        Optional<String> cached = currentTitleCache.get(playerId);
        if (cached != null) {
            return cached.orElse(null);
        }
        String sql = "SELECT title_id FROM player_current_title WHERE player_uuid = ?";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String titleId = rs.getString("title_id");
                    currentTitleCache.put(playerId, Optional.ofNullable(titleId));
                    return titleId;
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("查询当前称号失败: " + e.getMessage());
        }
        // 缓存空结果，避免重复查询
        currentTitleCache.put(playerId, Optional.empty());
        return null;
    }

    public void setCurrentTitle(@NotNull UUID playerId, @Nullable String titleId) {
        // 不能设置未解锁的称号
        if (titleId != null && !isUnlocked(playerId, titleId)) {
            return;
        }
        String sql = """
                INSERT INTO player_current_title (player_uuid, title_id)
                VALUES (?, ?)
                ON CONFLICT(player_uuid) DO UPDATE SET title_id = excluded.title_id;
                """;
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerId.toString());
            if (titleId != null) {
                ps.setString(2, titleId);
            } else {
                ps.setNull(2, java.sql.Types.VARCHAR);
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("设置当前称号失败: " + e.getMessage());
            return;
        }
        // 更新内存缓存
        currentTitleCache.put(playerId, Optional.ofNullable(titleId));
    }

    // ==================== 解锁逻辑 ====================

    /**
     * 尝试通过点击图鉴解锁称号
     * 检查背包物品，扣除后解锁
     */
    @NotNull
    public UnlockResult tryUnlock(@NotNull Player player, @NotNull String titleId) {
        TitleConfig title = titleConfigs.get(titleId);
        if (title == null) {
            return new UnlockResult(false, "§c称号不存在");
        }
        UUID playerId = player.getUniqueId();
        if (isUnlocked(playerId, titleId)) {
            return new UnlockResult(false, "§c你已经解锁了此称号");
        }
        List<UnlockItem> items = title.getUnlockItems();
        if (items.isEmpty()) {
            return new UnlockResult(false, "§c该称号解锁条件未知");
        }
        // 检查物品
        for (UnlockItem item : items) {
            if (!cn.lettle.letisland.util.InventoryUtils.hasEnoughItem(player, item.getMaterial(), item.getAmount())) {
                return new UnlockResult(false, "§c材料不足！需要 §e" +
                        item.getAmount() + " §c个 §e" + item.getDisplayName());
            }
        }
        // 扣除物品
        for (UnlockItem item : items) {
            cn.lettle.letisland.util.InventoryUtils.removeItem(player, item.getMaterial(), item.getAmount());
        }
        // 解锁
        unlock(playerId, titleId);
        player.updateInventory();
        return new UnlockResult(true, "§a成功解锁称号 §r" + title.getColor() + title.getName());
    }

    // ==================== 工具方法 ====================

    /**
     * 统计玩家背包中某材料的数量（供GUI显示用）
     */
    public int countItem(Player player, Material material) {
        return cn.lettle.letisland.util.InventoryUtils.countItem(player, material);
    }

    // ==================== Getter ====================

    public boolean isEnabled() { return enabled; }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        titlesConfig.set("enabled", enabled);
        saveConfig();
    }

    public Map<String, TitleConfig> getTitleConfigs() { return titleConfigs; }

    @Nullable
    public TitleConfig getTitleConfig(@NotNull String id) {
        return titleConfigs.get(id);
    }

    public int getUnlockedCount(@NotNull UUID playerId) {
        String sql = "SELECT COUNT(*) AS cnt FROM player_unlocked_titles WHERE player_uuid = ?";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("cnt");
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("查询已解锁称号数量失败: " + e.getMessage());
        }
        return 0;
    }

    // ==================== 数据类 ====================

    public static class TitleConfig {
        private final String id;
        private final String name;
        private final String color;
        private final String description;
        private final String unlockMethod;
        private final List<UnlockItem> unlockItems;

        public TitleConfig(String id, String name, String color, String description,
                           String unlockMethod, List<UnlockItem> unlockItems) {
            this.id = id;
            this.name = name;
            this.color = color;
            this.description = description;
            this.unlockMethod = unlockMethod;
            this.unlockItems = unlockItems;
        }

        public String getId() { return id; }
        public String getName() { return name; }
        public String getColor() { return color; }
        public String getDescription() { return description; }
        public String getUnlockMethod() { return unlockMethod; }
        public List<UnlockItem> getUnlockItems() { return unlockItems; }
        /** 是否有明确的物品解锁条件 */
        public boolean isUnlockable() { return !unlockItems.isEmpty(); }
    }

    public static class UnlockItem {
        private final Material material;
        private final int amount;
        private final String displayName;

        public UnlockItem(Material material, int amount, String displayName) {
            this.material = material;
            this.amount = amount;
            this.displayName = displayName;
        }

        public Material getMaterial() { return material; }
        public int getAmount() { return amount; }
        public String getDisplayName() { return displayName; }
    }

    public record UnlockResult(boolean success, String message) {}
}
