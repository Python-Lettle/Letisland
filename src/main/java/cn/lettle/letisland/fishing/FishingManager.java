package cn.lettle.letisland.fishing;

import cn.lettle.letisland.economy.EconomyManager;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * 钓鱼科技核心管理器
 * 管理玩家钓鱼等级、经验值、鱼类池、BUFF和经验道具
 */
public class FishingManager {

    private final JavaPlugin plugin;
    private final EconomyManager economyManager;
    private final File fishingFile;
    private FileConfiguration fishingConfig;

    // PersistentData keys
    private final NamespacedKey fishIdKey;
    private final NamespacedKey fishWeightKey;
    private final NamespacedKey expItemKey;

    /** 系统是否启用 */
    private boolean enabled;

    /** 等级配置 */
    private final Map<Integer, LevelConfig> levelConfigs = new LinkedHashMap<>();
    private int maxLevel;

    /** 鱼类配置 */
    private final Map<String, FishConfig> fishConfigs = new LinkedHashMap<>();

    /** BUFF配置 */
    private final Map<String, BuffConfig> buffConfigs = new LinkedHashMap<>();

    /** 钓鱼奖励概率 */
    private double customFishChance;
    private double expItemChance;
    private double buffChance;

    /** 经验道具配置 */
    private String expItemName;
    private Material expItemMaterial;
    private int expItemMinExp;
    private int expItemMaxExp;

    /** 自动出售配置 */
    private boolean autoSellEnabled;
    private int autoSellDefaultTier;

    public FishingManager(@NotNull JavaPlugin plugin, @NotNull EconomyManager economyManager) {
        this.plugin = plugin;
        this.economyManager = economyManager;
        this.fishingFile = new File(plugin.getDataFolder(), "fishing.yml");
        this.fishIdKey = new NamespacedKey(plugin, "fish_id");
        this.fishWeightKey = new NamespacedKey(plugin, "fish_weight");
        this.expItemKey = new NamespacedKey(plugin, "exp_item");
        loadConfig();
    }

    // ==================== 配置加载 ====================

    public void loadConfig() {
        if (!fishingFile.exists()) {
            plugin.saveResource("fishing.yml", false);
        }
        fishingConfig = YamlConfiguration.loadConfiguration(fishingFile);
        parseConfig();
    }

    public void saveConfig() {
        try {
            fishingConfig.save(fishingFile);
        } catch (IOException e) {
            throw new RuntimeException("无法保存钓鱼配置文件: " + e.getMessage(), e);
        }
    }

    public void reload() {
        loadConfig();
    }

    private void parseConfig() {
        levelConfigs.clear();
        fishConfigs.clear();
        buffConfigs.clear();

        enabled = fishingConfig.getBoolean("enabled", true);

        // 解析等级
        ConfigurationSection levelsSection = fishingConfig.getConfigurationSection("levels");
        if (levelsSection != null) {
            for (String key : levelsSection.getKeys(false)) {
                int level = Integer.parseInt(key);
                ConfigurationSection ls = levelsSection.getConfigurationSection(key);
                if (ls == null) continue;

                String name = ls.getString("name", "等级 " + level);
                int expToNext = ls.getInt("exp-to-next", 0);

                List<UpgradeCost> costs = new ArrayList<>();
                for (Map<?, ?> map : ls.getMapList("upgrade-cost")) {
                    String materialStr = (String) map.get("material");
                    int amount = (int) map.get("amount");
                    Material material = Material.matchMaterial(materialStr);
                    if (material != null) {
                        String displayName = (String) map.get("display-name");
                        if (displayName == null) {
                            displayName = materialStr;
                        }
                        costs.add(new UpgradeCost(material, amount, displayName));
                    }
                }
                levelConfigs.put(level, new LevelConfig(level, name, expToNext, costs));
            }
        }
        maxLevel = levelConfigs.keySet().stream().max(Integer::compare).orElse(1);

        // 解析鱼类
        ConfigurationSection fishSection = fishingConfig.getConfigurationSection("fish");
        if (fishSection != null) {
            for (String id : fishSection.getKeys(false)) {
                ConfigurationSection fs = fishSection.getConfigurationSection(id);
                if (fs == null) continue;

                String name = fs.getString("name", id);
                int tier = fs.getInt("tier", 1);
                String materialStr = fs.getString("material", "COD");
                Material material = Material.matchMaterial(materialStr);
                if (material == null) material = Material.COD;

                double minWeight = fs.getDouble("min-weight", 0.1);
                double maxWeight = fs.getDouble("max-weight", 1.0);
                double baseValue = fs.getDouble("base-value", 1.0);
                double weight = fs.getDouble("weight", 10.0);
                // 自定义模型数据（用于资源包替换材质；<=0 表示不设置，使用基础材质贴图）
                int customModelData = fs.getInt("custom-model-data", 0);

                fishConfigs.put(id, new FishConfig(id, name, tier, material, minWeight, maxWeight,
                        baseValue, weight, customModelData));
            }
        }

        // 解析奖励概率
        customFishChance = fishingConfig.getDouble("fishing-rewards.custom-fish-chance", 0.60);
        expItemChance = fishingConfig.getDouble("fishing-rewards.exp-item-chance", 0.10);
        buffChance = fishingConfig.getDouble("fishing-rewards.buff-chance", 0.15);

        // 解析经验道具
        ConfigurationSection expSection = fishingConfig.getConfigurationSection("exp-item");
        if (expSection != null) {
            expItemName = expSection.getString("name", "钓鱼经验卷轴");
            String matStr = expSection.getString("material", "PAPER");
            expItemMaterial = Material.matchMaterial(matStr);
            if (expItemMaterial == null) expItemMaterial = Material.PAPER;
            expItemMinExp = expSection.getInt("min-exp", 10);
            expItemMaxExp = expSection.getInt("max-exp", 50);
        } else {
            expItemName = "钓鱼经验卷轴";
            expItemMaterial = Material.PAPER;
            expItemMinExp = 10;
            expItemMaxExp = 50;
        }

        // 解析BUFF
        ConfigurationSection buffSection = fishingConfig.getConfigurationSection("buffs");
        if (buffSection != null) {
            for (String id : buffSection.getKeys(false)) {
                ConfigurationSection bs = buffSection.getConfigurationSection(id);
                if (bs == null) continue;

                String name = bs.getString("name", id);
                String type = bs.getString("type", "GOOD");
                String effect = bs.getString("effect", "REGENERATION");
                int duration = bs.getInt("duration", 10);
                int amplifier = bs.getInt("amplifier", 0);
                double weight = bs.getDouble("weight", 10.0);

                buffConfigs.put(id, new BuffConfig(id, name, type, effect, duration, amplifier, weight));
            }
        }

        // 解析自动出售配置
        autoSellEnabled = fishingConfig.getBoolean("auto-sell.enabled", true);
        autoSellDefaultTier = fishingConfig.getInt("auto-sell.default-tier", 0);
    }

    // ==================== 玩家数据 ====================

    public int getPlayerLevel(@NotNull UUID playerId) {
        return fishingConfig.getInt("players." + playerId + ".level", 1);
    }

    public int getPlayerExp(@NotNull UUID playerId) {
        return fishingConfig.getInt("players." + playerId + ".exp", 0);
    }

    public void setPlayerData(@NotNull UUID playerId, int level, int exp) {
        fishingConfig.set("players." + playerId + ".level", level);
        fishingConfig.set("players." + playerId + ".exp", exp);
        saveConfig();
    }

    public void addExp(@NotNull UUID playerId, int amount) {
        int level = getPlayerLevel(playerId);
        int exp = getPlayerExp(playerId);
        exp += amount;

        // 检查是否升级（经验值达标后不自动升级，需要手动消耗材料升级）
        LevelConfig config = levelConfigs.get(level);
        if (config != null) {
            int expToNext = config.getExpToNext();
            if (expToNext > 0 && exp > expToNext) {
                exp = expToNext; // 经验值封顶
            }
        }
        setPlayerData(playerId, level, exp);
    }

    // ==================== 自动出售 ====================

    public boolean isAutoSellEnabled() {
        return autoSellEnabled;
    }

    public int getAutoSellDefaultTier() {
        return autoSellDefaultTier;
    }

    /**
     * 获取玩家自动出售等级（0=关闭）
     */
    public int getAutoSellTier(@NotNull UUID playerId) {
        return fishingConfig.getInt("players." + playerId + ".auto-sell-tier", autoSellDefaultTier);
    }

    /**
     * 设置玩家自动出售等级
     */
    public void setAutoSellTier(@NotNull UUID playerId, int tier) {
        fishingConfig.set("players." + playerId + ".auto-sell-tier", tier);
        saveConfig();
    }

    /**
     * 检查鱼是否应该自动出售
     */
    public boolean shouldAutoSell(@NotNull UUID playerId, int fishTier) {
        if (!autoSellEnabled) return false;
        int tier = getAutoSellTier(playerId);
        return tier > 0 && fishTier <= tier;
    }

    // ==================== 鱼类图鉴 ====================

    /**
     * 记录玩家钓到鱼（图鉴数据）
     */
    public void recordFishCatch(@NotNull UUID playerId, @NotNull String fishId, double weight) {
        String base = "players." + playerId + ".codex." + fishId;
        int count = fishingConfig.getInt(base + ".count", 0);
        double maxWeight = fishingConfig.getDouble(base + ".max-weight", 0.0);

        fishingConfig.set(base + ".count", count + 1);
        if (weight > maxWeight) {
            fishingConfig.set(base + ".max-weight", weight);
        }
        saveConfig();
    }

    /**
     * 获取玩家某鱼的钓到次数
     */
    public int getCodexCount(@NotNull UUID playerId, @NotNull String fishId) {
        return fishingConfig.getInt("players." + playerId + ".codex." + fishId + ".count", 0);
    }

    /**
     * 获取玩家某鱼的最高重量纪录
     */
    public double getCodexMaxWeight(@NotNull UUID playerId, @NotNull String fishId) {
        return fishingConfig.getDouble("players." + playerId + ".codex." + fishId + ".max-weight", 0.0);
    }

    /**
     * 检查玩家是否已发现某种鱼
     */
    public boolean hasDiscovered(@NotNull UUID playerId, @NotNull String fishId) {
        return fishingConfig.contains("players." + playerId + ".codex." + fishId);
    }

    /**
     * 获取玩家已发现的鱼类数量
     */
    public int getDiscoveredCount(@NotNull UUID playerId) {
        ConfigurationSection codex = fishingConfig.getConfigurationSection("players." + playerId + ".codex");
        return codex != null ? codex.getKeys(false).size() : 0;
    }

    /**
     * 检查玩家经验是否已满
     */
    public boolean isExpMaxed(@NotNull UUID playerId) {
        int level = getPlayerLevel(playerId);
        LevelConfig config = levelConfigs.get(level);
        if (config == null || config.getExpToNext() <= 0) {
            return true; // 已满级
        }
        return getPlayerExp(playerId) >= config.getExpToNext();
    }

    /**
     * 尝试升级
     */
    @NotNull
    public UpgradeResult tryUpgrade(@NotNull Player player) {
        UUID playerId = player.getUniqueId();
        int level = getPlayerLevel(playerId);

        if (level >= maxLevel) {
            return new UpgradeResult(false, "§c钓鱼等级已达最高级 §e" + maxLevel);
        }

        if (!isExpMaxed(playerId)) {
            LevelConfig config = levelConfigs.get(level);
            int expToNext = config != null ? config.getExpToNext() : 0;
            return new UpgradeResult(false, "§c经验值不足！需要 §e" + expToNext +
                    "§c，当前 §e" + getPlayerExp(playerId));
        }

        LevelConfig nextConfig = levelConfigs.get(level + 1);
        if (nextConfig == null) {
            return new UpgradeResult(false, "§c下一级配置不存在");
        }

        // 检查材料
        List<UpgradeCost> costs = nextConfig.getUpgradeCost();
        for (UpgradeCost cost : costs) {
            if (!hasEnoughItem(player, cost.getMaterial(), cost.getAmount())) {
                return new UpgradeResult(false, "§c材料不足！需要 §e" +
                        cost.getAmount() + " §c个 §e" + cost.getMaterial().name());
            }
        }

        // 扣除材料
        for (UpgradeCost cost : costs) {
            removeItem(player, cost.getMaterial(), cost.getAmount());
        }

        // 升级
        setPlayerData(playerId, level + 1, 0);
        player.updateInventory();

        return new UpgradeResult(true, "§a钓鱼等级已升级到 §e" + nextConfig.getName() + " §a！");
    }

    // ==================== 鱼类生成 ====================

    /**
     * 根据玩家等级随机选择一条鱼
     * @return 选中的鱼配置，null表示不生成自定义鱼
     */
    @Nullable
    public FishConfig rollFish(int playerLevel) {
        // 筛选玩家可钓到的鱼（tier <= playerLevel）
        List<FishConfig> available = new ArrayList<>();
        for (FishConfig fish : fishConfigs.values()) {
            if (fish.getTier() <= playerLevel) {
                available.add(fish);
            }
        }
        if (available.isEmpty()) return null;

        // 按权重随机选择
        double totalWeight = available.stream().mapToDouble(FishConfig::getWeight).sum();
        double r = new Random().nextDouble() * totalWeight;
        double cumulative = 0;
        for (FishConfig fish : available) {
            cumulative += fish.getWeight();
            if (r <= cumulative) {
                return fish;
            }
        }
        return available.get(available.size() - 1);
    }

    /**
     * 生成鱼的重量（偏向下限，越重越稀有）
     */
    public double rollWeight(@NotNull FishConfig fish) {
        double min = fish.getMinWeight();
        double max = fish.getMaxWeight();
        // 使用平方分布使重量偏向较小值
        double r = new Random().nextDouble();
        r = r * r; // 平方使分布偏向0
        double weight = min + (max - min) * r;
        // 保留两位小数
        return Math.round(weight * 100.0) / 100.0;
    }

    /**
     * 创建鱼物品
     */
    @NotNull
    public ItemStack createFishItem(@NotNull FishConfig fish, double weight) {
        ItemStack item = new ItemStack(fish.getMaterial());
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        // 显示名称（含等级颜色）
        String tierColor = getTierColor(fish.getTier());
        meta.setDisplayName(tierColor + fish.getName());

        // Lore 显示信息
        List<String> lore = new ArrayList<>();
        lore.add("§7重量: §f" + weight + " kg");
        lore.add("§7等级: " + tierColor + getTierName(fish.getTier()));
        lore.add("§7价值: §e" + economyManager.format(calculateFishValue(fish, weight)));
        lore.add("");
        lore.add("§8可用于出售");
        meta.setLore(lore);

        // 应用自定义材质（CustomModelData）
        // 鲁棒性：未配置(<=0)时不调用 setCustomModelData，客户端将按基础材质渲染；
        //        若配置了但客户端未安装资源包，则会按基础材质回退显示，无副作用。
        applyFishCustomModel(meta, fish);

        // 使用 PersistentDataContainer 存储自定义数据
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(fishIdKey, PersistentDataType.STRING, fish.getId());
        pdc.set(fishWeightKey, PersistentDataType.DOUBLE, weight);

        item.setItemMeta(meta);
        return item;
    }

    /**
     * 为物品Meta应用鱼的CustomModelData（如果配置了的话）
     * 可在创建鱼物品或图鉴物品等场景复用
     */
    public void applyFishCustomModel(@NotNull ItemMeta meta, @NotNull FishConfig fish) {
        int cmd = fish.getCustomModelData();
        if (cmd > 0) {
            meta.setCustomModelData(cmd);
        }
    }

    /**
     * 计算鱼的出售价值
     */
    public double calculateFishValue(@NotNull FishConfig fish, double weight) {
        return fish.getBaseValue() * weight;
    }

    /**
     * 检查物品是否是自定义鱼
     */
    public boolean isCustomFish(@Nullable ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        return meta.getPersistentDataContainer().has(fishIdKey, PersistentDataType.STRING);
    }

    /**
     * 获取鱼物品的信息
     */
    @Nullable
    public FishItemInfo getFishInfo(@Nullable ItemStack item) {
        if (!isCustomFish(item)) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();

        String fishId = pdc.get(fishIdKey, PersistentDataType.STRING);
        double weight = pdc.getOrDefault(fishWeightKey, PersistentDataType.DOUBLE, 0.0);
        if (fishId == null) return null;

        FishConfig fish = fishConfigs.get(fishId);
        if (fish == null) return null;

        double totalValue = calculateFishValue(fish, weight) * item.getAmount();
        return new FishItemInfo(fish, weight, totalValue);
    }

    // ==================== 经验道具 ====================

    /**
     * 创建经验值道具
     */
    @NotNull
    public ItemStack createExpItem() {
        int exp = expItemMinExp + new Random().nextInt(expItemMaxExp - expItemMinExp + 1);
        ItemStack item = new ItemStack(expItemMaterial);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.setDisplayName("§b" + expItemName);

        List<String> lore = new ArrayList<>();
        lore.add("§7右键使用获得 §e" + exp + " §7钓鱼经验");
        lore.add("");
        lore.add("§8右键点击使用");
        meta.setLore(lore);

        // 存储经验值
        meta.getPersistentDataContainer().set(expItemKey, PersistentDataType.INTEGER, exp);

        item.setItemMeta(meta);
        return item;
    }

    /**
     * 检查物品是否是经验道具
     */
    public boolean isExpItem(@Nullable ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        return meta.getPersistentDataContainer().has(expItemKey, PersistentDataType.INTEGER);
    }

    /**
     * 获取经验道具的经验值并消耗
     */
    public int useExpItem(@NotNull ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return 0;
        Integer exp = meta.getPersistentDataContainer().get(expItemKey, PersistentDataType.INTEGER);
        return exp != null ? exp : 0;
    }

    // ==================== BUFF ====================

    /**
     * 随机选择一个BUFF
     */
    @Nullable
    public BuffConfig rollBuff() {
        if (buffConfigs.isEmpty()) return null;

        double totalWeight = buffConfigs.values().stream().mapToDouble(BuffConfig::getWeight).sum();
        double r = new Random().nextDouble() * totalWeight;
        double cumulative = 0;
        for (BuffConfig buff : buffConfigs.values()) {
            cumulative += buff.getWeight();
            if (r <= cumulative) {
                return buff;
            }
        }
        return null;
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

    public static String getTierColor(int tier) {
        return switch (tier) {
            case 1 -> "§f";   // 白色
            case 2 -> "§a";   // 绿色
            case 3 -> "§b";   // 青色
            case 4 -> "§d";   // 粉色
            case 5 -> "§6";   // 金色
            default -> "§7";
        };
    }

    public static String getTierName(int tier) {
        return switch (tier) {
            case 1 -> "普通";
            case 2 -> "罕见";
            case 3 -> "稀有";
            case 4 -> "史诗";
            case 5 -> "传说";
            default -> "未知";
        };
    }

    // ==================== Getter ====================

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        fishingConfig.set("enabled", enabled);
        saveConfig();
    }

    public int getMaxLevel() { return maxLevel; }
    public Map<Integer, LevelConfig> getLevelConfigs() { return levelConfigs; }
    public Map<String, FishConfig> getFishConfigs() { return fishConfigs; }
    public Map<String, BuffConfig> getBuffConfigs() { return buffConfigs; }

    public double getCustomFishChance() { return customFishChance; }
    public double getExpItemChance() { return expItemChance; }
    public double getBuffChance() { return buffChance; }

    public EconomyManager getEconomyManager() { return economyManager; }

    public NamespacedKey getFishIdKey() { return fishIdKey; }
    public NamespacedKey getFishWeightKey() { return fishWeightKey; }
    public NamespacedKey getExpItemKey() { return expItemKey; }

    // ==================== 数据类 ====================

    public static class LevelConfig {
        private final int level;
        private final String name;
        private final int expToNext;
        private final List<UpgradeCost> upgradeCost;

        public LevelConfig(int level, String name, int expToNext, List<UpgradeCost> upgradeCost) {
            this.level = level;
            this.name = name;
            this.expToNext = expToNext;
            this.upgradeCost = upgradeCost;
        }

        public int getLevel() { return level; }
        public String getName() { return name; }
        public int getExpToNext() { return expToNext; }
        public List<UpgradeCost> getUpgradeCost() { return upgradeCost; }
    }

    public static class UpgradeCost {
        private final Material material;
        private final int amount;
        private final String displayName;

        public UpgradeCost(Material material, int amount, String displayName) {
            this.material = material;
            this.amount = amount;
            this.displayName = displayName;
        }

        public Material getMaterial() { return material; }
        public int getAmount() { return amount; }
        public String getDisplayName() { return displayName; }
    }

    public static class FishConfig {
        private final String id;
        private final String name;
        private final int tier;
        private final Material material;
        private final double minWeight;
        private final double maxWeight;
        private final double baseValue;
        private final double weight;
        private final int customModelData;

        public FishConfig(String id, String name, int tier, Material material,
                          double minWeight, double maxWeight, double baseValue, double weight,
                          int customModelData) {
            this.id = id;
            this.name = name;
            this.tier = tier;
            this.material = material;
            this.minWeight = minWeight;
            this.maxWeight = maxWeight;
            this.baseValue = baseValue;
            this.weight = weight;
            this.customModelData = customModelData;
        }

        public String getId() { return id; }
        public String getName() { return name; }
        public int getTier() { return tier; }
        public Material getMaterial() { return material; }
        public double getMinWeight() { return minWeight; }
        public double getMaxWeight() { return maxWeight; }
        public double getBaseValue() { return baseValue; }
        public double getWeight() { return weight; }
        public int getCustomModelData() { return customModelData; }
    }

    public static class BuffConfig {
        private final String id;
        private final String name;
        private final String type;
        private final String effect;
        private final int duration;
        private final int amplifier;
        private final double weight;

        public BuffConfig(String id, String name, String type, String effect,
                          int duration, int amplifier, double weight) {
            this.id = id;
            this.name = name;
            this.type = type;
            this.effect = effect;
            this.duration = duration;
            this.amplifier = amplifier;
            this.weight = weight;
        }

        public String getId() { return id; }
        public String getName() { return name; }
        public String getType() { return type; }
        public String getEffect() { return effect; }
        public int getDuration() { return duration; }
        public int getAmplifier() { return amplifier; }
        public double getWeight() { return weight; }
    }

    public record FishItemInfo(FishConfig fish, double weight, double totalValue) {}

    public record UpgradeResult(boolean success, String message) {}
}
