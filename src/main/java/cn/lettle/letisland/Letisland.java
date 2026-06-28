package cn.lettle.letisland;

import cn.lettle.letisland.database.DatabaseManager;
import cn.lettle.letisland.database.YamlMigrator;
import cn.lettle.letisland.economy.EconomyCommand;
import cn.lettle.letisland.economy.EconomyManager;
import cn.lettle.letisland.fishing.FishingCommand;
import cn.lettle.letisland.fishing.FishingGUI;
import cn.lettle.letisland.fishing.FishingListener;
import cn.lettle.letisland.fishing.FishingManager;
import cn.lettle.letisland.generator.GeneratorCommand;
import cn.lettle.letisland.generator.GeneratorListener;
import cn.lettle.letisland.generator.GeneratorManager;
import cn.lettle.letisland.log.LogCommand;
import cn.lettle.letisland.log.LogListener;
import cn.lettle.letisland.log.LogManager;
import cn.lettle.letisland.shop.ShopCommand;
import cn.lettle.letisland.shop.ShopListener;
import cn.lettle.letisland.shop.ShopManager;
import cn.lettle.letisland.title.ChatListener;
import cn.lettle.letisland.title.TitleCommand;
import cn.lettle.letisland.title.TitleManager;
import cn.lettle.letisland.trash.TrashBinCommand;
import cn.lettle.letisland.trash.TrashBinListener;
import cn.lettle.letisland.trash.TrashBinManager;
import org.bukkit.plugin.java.JavaPlugin;

public final class Letisland extends JavaPlugin {

    private DatabaseManager databaseManager;
    private EconomyManager economyManager;
    private ShopManager shopManager;
    private ShopListener shopListener;
    private GeneratorManager generatorManager;
    private FishingManager fishingManager;
    private TitleManager titleManager;
    private TrashBinManager trashBinManager;

    @Override
    public void onEnable() {
        // 保存默认配置
        saveDefaultConfig();
        saveResource("shop.yml", false);
        saveResource("generator.yml", false);
        saveResource("fishing.yml", false);
        saveResource("titles.yml", false);

        // 初始化数据库管理器（必须在所有数据管理器之前初始化）
        databaseManager = new DatabaseManager(this);

        // 一次性迁移旧版YAML玩家数据到SQLite（仅在首次启动时执行）
        new YamlMigrator(this, databaseManager).migrateIfNeeded();

        // 初始化日志系统（必须在FishingManager/TitleManager之前初始化）
        LogManager logManager = new LogManager(this, databaseManager);

        // 初始化经济系统
        String currencySymbol = getConfig().getString("economy.currency-symbol", "$");
        economyManager = new EconomyManager(this, databaseManager, currencySymbol);

        // 初始化商店系统
        shopManager = new ShopManager(getDataFolder());
        shopListener = new ShopListener(this, shopManager, economyManager);

        // 初始化刷石机系统
        generatorManager = new GeneratorManager(getDataFolder(), economyManager);
        GeneratorListener generatorListener = new GeneratorListener(generatorManager);

        // 初始化钓鱼系统
        fishingManager = new FishingManager(this, economyManager, databaseManager, logManager);
        FishingListener fishingListener = new FishingListener(fishingManager);
        FishingCommand fishingCommand = new FishingCommand(fishingManager, economyManager);

        // 注册命令
        EconomyCommand economyCommand = new EconomyCommand(economyManager);
        getCommand("economy").setExecutor(economyCommand);
        getCommand("economy").setTabCompleter(economyCommand);

        ShopCommand shopCommand = new ShopCommand(shopManager, shopListener);
        getCommand("shop").setExecutor(shopCommand);
        getCommand("shop").setTabCompleter(shopCommand);

        GeneratorCommand generatorCommand = new GeneratorCommand(generatorManager, economyManager);
        getCommand("generator").setExecutor(generatorCommand);
        getCommand("generator").setTabCompleter(generatorCommand);

        getCommand("fishing").setExecutor(fishingCommand);
        getCommand("fishing").setTabCompleter(fishingCommand);

        // 初始化称号系统（依赖钓鱼系统获取玩家等级用于聊天格式化）
        titleManager = new TitleManager(this, databaseManager, logManager);
        ChatListener chatListener = new ChatListener(titleManager, fishingManager);
        TitleCommand titleCommand = new TitleCommand(titleManager);
        getCommand("title").setExecutor(titleCommand);
        getCommand("title").setTabCompleter(titleCommand);

        // 初始化垃圾桶系统（定时清理世界掉落物，玩家可从GUI取出）
        trashBinManager = new TrashBinManager();
        TrashBinListener trashBinListener = new TrashBinListener(this, trashBinManager);
        TrashBinCommand trashBinCommand = new TrashBinCommand(trashBinListener);
        getCommand("trash").setExecutor(trashBinCommand);

        // 注册日志系统命令与监听器
        LogCommand logCommand = new LogCommand(logManager);
        getCommand("letisland").setExecutor(logCommand);
        getCommand("letisland").setTabCompleter(logCommand);
        LogListener logListener = new LogListener(logManager);

        // 注册事件监听器
        getServer().getPluginManager().registerEvents(shopListener, this);
        getServer().getPluginManager().registerEvents(generatorListener, this);
        getServer().getPluginManager().registerEvents(fishingListener, this);
        getServer().getPluginManager().registerEvents(fishingCommand.getFishingGUI(), this);
        getServer().getPluginManager().registerEvents(chatListener, this);
        getServer().getPluginManager().registerEvents(titleCommand.getTitleGUI(), this);
        getServer().getPluginManager().registerEvents(logListener, this);
        getServer().getPluginManager().registerEvents(trashBinListener, this);

        // 输出启动信息
        getLogger().info("==============================");
        getLogger().info("      Letisland Start!");
        getLogger().info("       Author: Lettle");
        getLogger().info("==============================");
        getLogger().info("经济系统已加载，货币符号: " + currencySymbol);
        getLogger().info("商店系统已加载，状态: " + (shopManager.isEnabled() ? "启用" : "关闭") +
                "，购买物品池: " + shopManager.getBuyPool().size() +
                "，出售物品池: " + shopManager.getSellPool().size());
        getLogger().info("刷石机系统已加载，状态: " + (generatorManager.isEnabled() ? "启用" : "关闭") +
                "，当前等级: " + generatorManager.getCurrentLevel() +
                "/" + generatorManager.getMaxLevel());
        getLogger().info("钓鱼系统已加载，状态: " + (fishingManager.isEnabled() ? "启用" : "关闭") +
                "，鱼类: " + fishingManager.getFishConfigs().size() + " 种" +
                "，BUFF: " + fishingManager.getBuffConfigs().size() + " 种");
        getLogger().info("称号系统已加载，状态: " + (titleManager.isEnabled() ? "启用" : "关闭") +
                "，称号: " + titleManager.getTitleConfigs().size() + " 种");
    }

    @Override
    public void onDisable() {
        getLogger().warning("Letisland 正在关闭... 正在保存数据...");
        // 关闭数据库连接（SQLite在每次操作时已实时持久化，无需额外保存）
        if (databaseManager != null) {
            databaseManager.close();
        }
        getLogger().info("Letisland 已关闭");
    }

    /**
     * 获取数据库管理器实例
     */
    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    /**
     * 获取经济管理器实例
     */
    public EconomyManager getEconomyManager() {
        return economyManager;
    }

    /**
     * 获取商店管理器实例
     */
    public ShopManager getShopManager() {
        return shopManager;
    }

    /**
     * 获取刷石机管理器实例
     */
    public GeneratorManager getGeneratorManager() {
        return generatorManager;
    }

    /**
     * 获取钓鱼管理器实例
     */
    public FishingManager getFishingManager() {
        return fishingManager;
    }

    /**
     * 获取称号管理器实例
     */
    public TitleManager getTitleManager() {
        return titleManager;
    }
}
