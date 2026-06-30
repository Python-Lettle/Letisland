package cn.lettle.letisland.generator;

import cn.lettle.letisland.economy.EconomyManager;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 刷石机管理器
 * 管理全服共享的刷石机等级、矿物池配置和升级逻辑
 */
public class GeneratorManager {

    private final EconomyManager economyManager;
    private final File generatorFile;
    private FileConfiguration generatorConfig;

    /** 刷石机是否启用 */
    private boolean enabled;

    /** 当前全服刷石机等级 */
    private int currentLevel;

    /** 最高等级 */
    private int maxLevel;

    /** 等级配置缓存：等级 -> 等级配置 */
    private final Map<Integer, LevelConfig> levelConfigs = new HashMap<>();

    public GeneratorManager(@NotNull File dataFolder, @NotNull EconomyManager economyManager) {
        this.economyManager = economyManager;
        this.generatorFile = new File(dataFolder, "generator.yml");
        loadConfig();
    }

    /**
     * 加载配置文件
     */
    public void loadConfig() {
        if (!generatorFile.exists()) {
            try {
                generatorFile.getParentFile().mkdirs();
                generatorFile.createNewFile();
            } catch (IOException e) {
                throw new RuntimeException("无法创建刷石机配置文件: " + e.getMessage(), e);
            }
        }
        generatorConfig = YamlConfiguration.loadConfiguration(generatorFile);
        parseConfig();
    }

    /**
     * 保存配置文件
     */
    public void saveConfig() {
        try {
            generatorConfig.save(generatorFile);
        } catch (IOException e) {
            throw new RuntimeException("无法保存刷石机配置文件: " + e.getMessage(), e);
        }
    }

    /**
     * 热重载
     */
    public void reload() {
        loadConfig();
    }

    /**
     * 刷石机是否启用
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 设置刷石机启用状态并保存
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        generatorConfig.set("enabled", enabled);
        saveConfig();
    }

    /**
     * 解析配置
     */
    private void parseConfig() {
        levelConfigs.clear();

        enabled = generatorConfig.getBoolean("enabled", true);
        currentLevel = generatorConfig.getInt("current-level", 1);

        ConfigurationSection levelsSection = generatorConfig.getConfigurationSection("levels");
        if (levelsSection == null) {
            maxLevel = 1;
            return;
        }

        for (String key : levelsSection.getKeys(false)) {
            int level = Integer.parseInt(key);
            ConfigurationSection levelSection = levelsSection.getConfigurationSection(key);
            if (levelSection == null) continue;

            String name = levelSection.getString("name", "等级 " + level);
            double upgradeCost = levelSection.getDouble("upgrade-cost", 0);
            double replaceChance = levelSection.getDouble("replace-chance", 0.05);

            List<MineralEntry> minerals = new ArrayList<>();
            ConfigurationSection mineralsSection = levelSection.getConfigurationSection("minerals");
            if (mineralsSection != null) {
                for (String mineralKey : mineralsSection.getKeys(false)) {
                    String materialStr = mineralsSection.getString(mineralKey + ".material");
                    if (materialStr == null) continue;
                    Material material = Material.matchMaterial(materialStr);
                    if (material == null) continue;

                    double weight = mineralsSection.getDouble(mineralKey + ".weight", 1.0);
                    minerals.add(new MineralEntry(material, weight));
                }
            }

            levelConfigs.put(level, new LevelConfig(level, name, upgradeCost, replaceChance, minerals));
        }

        maxLevel = levelConfigs.keySet().stream().max(Integer::compare).orElse(1);

        // 防止 currentLevel 越界
        if (currentLevel > maxLevel) {
            currentLevel = maxLevel;
        }
        if (currentLevel < 1) {
            currentLevel = 1;
        }
    }

    /**
     * 获取当前等级配置
     */
    @NotNull
    public LevelConfig getCurrentLevelConfig() {
        LevelConfig config = levelConfigs.get(currentLevel);
        if (config == null) {
            return new LevelConfig(1, "默认", 0, 0, new ArrayList<>());
        }
        return config;
    }

    /**
     * 获取指定等级配置
     */
    @Nullable
    public LevelConfig getLevelConfig(int level) {
        return levelConfigs.get(level);
    }

    /**
     * 升级刷石机
     * @return 升级结果
     */
    @NotNull
    public UpgradeResult upgrade(@NotNull Player player) {
        if (currentLevel >= maxLevel) {
            return new UpgradeResult(false, "§c刷石机已达最高等级 §e" + maxLevel + "§c，无法继续升级");
        }

        int nextLevel = currentLevel + 1;
        LevelConfig nextConfig = levelConfigs.get(nextLevel);
        if (nextConfig == null) {
            return new UpgradeResult(false, "§c下一级配置不存在");
        }

        double cost = nextConfig.getUpgradeCost();
        if (cost <= 0) {
            // 免费升级
            setCurrentLevel(nextLevel);
            return new UpgradeResult(true, "§a刷石机已免费升级到 §e" + nextConfig.getName());
        }

        if (!economyManager.has(player, cost)) {
            return new UpgradeResult(false, "§c余额不足！需要 §e" + economyManager.format(cost) +
                    "§c，当前余额 §e" + economyManager.format(economyManager.getBalance(player)));
        }

        if (!economyManager.withdraw(player, cost)) {
            return new UpgradeResult(false, "§c扣款失败");
        }

        setCurrentLevel(nextLevel);
        return new UpgradeResult(true, "§a刷石机已升级到 §e" + nextConfig.getName() +
                "§a！消耗 §e" + economyManager.format(cost));
    }

    /**
     * 设置当前等级并保存
     */
    public void setCurrentLevel(int level) {
        this.currentLevel = level;
        generatorConfig.set("current-level", level);
        saveConfig();
    }

    /**
     * 根据当前等级的矿物池随机选择一个矿物
     * @return 选中的矿物，null 表示不替换（保持圆石）
     */
    @Nullable
    public Material rollMineral() {
        LevelConfig config = getCurrentLevelConfig();
        List<MineralEntry> minerals = config.getMinerals();
        if (minerals.isEmpty()) {
            return null;
        }

        // 先判断是否触发替换
        if (ThreadLocalRandom.current().nextDouble() >= config.getReplaceChance()) {
            return null;
        }

        // 按权重随机选择矿物（totalWeight 预计算自 LevelConfig）
        double totalWeight = config.getTotalMineralWeight();
        double r = ThreadLocalRandom.current().nextDouble(totalWeight);
        double cumulative = 0;
        for (MineralEntry entry : minerals) {
            cumulative += entry.getWeight();
            if (r <= cumulative) {
                return entry.getMaterial();
            }
        }
        return minerals.get(minerals.size() - 1).getMaterial();
    }

    public int getCurrentLevel() {
        return currentLevel;
    }

    public int getMaxLevel() {
        return maxLevel;
    }

    public boolean isMaxLevel() {
        return currentLevel >= maxLevel;
    }

    /**
     * 等级配置数据类
     */
    public static class LevelConfig {
        private final int level;
        private final String name;
        private final double upgradeCost;
        private final double replaceChance;
        private final List<MineralEntry> minerals;
        private final double totalMineralWeight;

        public LevelConfig(int level, String name, double upgradeCost, double replaceChance, List<MineralEntry> minerals) {
            this.level = level;
            this.name = name;
            this.upgradeCost = upgradeCost;
            this.replaceChance = replaceChance;
            this.minerals = minerals;
            this.totalMineralWeight = minerals.stream().mapToDouble(MineralEntry::getWeight).sum();
        }

        public int getLevel() { return level; }
        public String getName() { return name; }
        public double getUpgradeCost() { return upgradeCost; }
        public double getReplaceChance() { return replaceChance; }
        public List<MineralEntry> getMinerals() { return minerals; }
        public double getTotalMineralWeight() { return totalMineralWeight; }
    }

    /**
     * 矿物条目数据类
     */
    public static class MineralEntry {
        private final Material material;
        private final double weight;

        public MineralEntry(Material material, double weight) {
            this.material = material;
            this.weight = Math.max(0, weight);
        }

        public Material getMaterial() { return material; }
        public double getWeight() { return weight; }
    }

    /**
     * 升级结果
     */
    public record UpgradeResult(boolean success, String message) {}
}
