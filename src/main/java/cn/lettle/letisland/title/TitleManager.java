package cn.lettle.letisland.title;

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
import java.util.*;

/**
 * 称号系统核心管理器
 * 管理称号定义、玩家解锁状态及当前佩戴称号
 */
public class TitleManager {

    private final JavaPlugin plugin;
    private final File titlesFile;
    private FileConfiguration titlesConfig;

    /** 系统是否启用 */
    private boolean enabled;

    /** 称号配置 */
    private final Map<String, TitleConfig> titleConfigs = new LinkedHashMap<>();

    public TitleManager(@NotNull JavaPlugin plugin) {
        this.plugin = plugin;
        this.titlesFile = new File(plugin.getDataFolder(), "titles.yml");
        loadConfig();
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
        List<String> unlocked = titlesConfig.getStringList("players." + playerId + ".unlocked");
        return unlocked.contains(titleId);
    }

    @NotNull
    public List<String> getUnlockedTitles(@NotNull UUID playerId) {
        return titlesConfig.getStringList("players." + playerId + ".unlocked");
    }

    public void unlock(@NotNull UUID playerId, @NotNull String titleId) {
        List<String> unlocked = new ArrayList<>(getUnlockedTitles(playerId));
        if (!unlocked.contains(titleId)) {
            unlocked.add(titleId);
            titlesConfig.set("players." + playerId + ".unlocked", unlocked);
            saveConfig();
        }
    }

    @Nullable
    public String getCurrentTitle(@NotNull UUID playerId) {
        return titlesConfig.getString("players." + playerId + ".current");
    }

    public void setCurrentTitle(@NotNull UUID playerId, @Nullable String titleId) {
        // 不能设置未解锁的称号
        if (titleId != null && !isUnlocked(playerId, titleId)) {
            return;
        }
        titlesConfig.set("players." + playerId + ".current", titleId);
        saveConfig();
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
            if (!hasEnoughItem(player, item.getMaterial(), item.getAmount())) {
                return new UnlockResult(false, "§c材料不足！需要 §e" +
                        item.getAmount() + " §c个 §e" + item.getDisplayName());
            }
        }
        // 扣除物品
        for (UnlockItem item : items) {
            removeItem(player, item.getMaterial(), item.getAmount());
        }
        // 解锁
        unlock(playerId, titleId);
        player.updateInventory();
        return new UnlockResult(true, "§a成功解锁称号 §r" + title.getColor() + title.getName());
    }

    // ==================== 工具方法 ====================

    private boolean hasEnoughItem(Player player, Material material, int amount) {
        int count = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && item.getType() == material) {
                count += item.getAmount();
            }
        }
        return count >= amount;
    }

    private void removeItem(Player player, Material material, int amount) {
        int remaining = amount;
        for (int i = 0; i < player.getInventory().getSize(); i++) {
            ItemStack item = player.getInventory().getItem(i);
            if (item == null || item.getType() != material) continue;
            if (item.getAmount() <= remaining) {
                remaining -= item.getAmount();
                player.getInventory().setItem(i, null);
            } else {
                item.setAmount(item.getAmount() - remaining);
                remaining = 0;
            }
            if (remaining <= 0) break;
        }
    }

    /**
     * 统计玩家背包中某材料的数量（供GUI显示用）
     */
    public int countItem(Player player, Material material) {
        int count = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && item.getType() == material) {
                count += item.getAmount();
            }
        }
        return count;
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
        return getUnlockedTitles(playerId).size();
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
