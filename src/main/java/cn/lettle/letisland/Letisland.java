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
import cn.lettle.letisland.home.GrindstoneGUI;
import cn.lettle.letisland.home.HomeCommand;
import cn.lettle.letisland.home.HomeListener;
import cn.lettle.letisland.home.HomeManager;
import cn.lettle.letisland.home.HomelandScoreboardManager;
import cn.lettle.letisland.home.MagicTableGUI;
import cn.lettle.letisland.log.LogCommand;
import cn.lettle.letisland.log.LogListener;
import cn.lettle.letisland.log.LogManager;
import cn.lettle.letisland.security.SecurityCommand;
import cn.lettle.letisland.security.SecurityListener;
import cn.lettle.letisland.security.SecurityManager;
import cn.lettle.letisland.shop.ShopCommand;
import cn.lettle.letisland.shop.ShopListener;
import cn.lettle.letisland.shop.ShopManager;
import cn.lettle.letisland.title.ChatListener;
import cn.lettle.letisland.title.TitleCommand;
import cn.lettle.letisland.title.TitleManager;
import cn.lettle.letisland.trash.TrashBinCommand;
import cn.lettle.letisland.trash.TrashBinListener;
import cn.lettle.letisland.trash.TrashBinManager;
import cn.lettle.letisland.util.CacheEvictionListener;
import org.bukkit.plugin.java.JavaPlugin;

public final class Letisland extends JavaPlugin {

    private DatabaseManager databaseManager;
    private EconomyManager economyManager;
    private ShopManager shopManager;
    private ShopListener shopListener;
    private GeneratorManager generatorManager;
    private FishingManager fishingManager;
    private FishingListener fishingListener;
    private TitleManager titleManager;
    private TrashBinManager trashBinManager;
    private SecurityManager securityManager;
    private HomeManager homeManager;
    private HomeCommand homeCommand;
    private HomelandScoreboardManager scoreboardManager;

    @Override
    public void onEnable() {
        // 保存默认配置
        saveDefaultConfig();
        saveResource("shop.yml", false);
        saveResource("generator.yml", false);
        saveResource("fishing.yml", false);
        saveResource("titles.yml", false);
        saveResource("home.yml", false);

        // 初始化数据库管理器（必须在所有数据管理器之前初始化）
        databaseManager = new DatabaseManager(this);

        // 一次性迁移旧版YAML玩家数据到SQLite（仅在首次启动时执行）
        new YamlMigrator(this, databaseManager).migrateIfNeeded();

        // 初始化日志系统（必须在FishingManager/TitleManager之前初始化）
        LogManager logManager = new LogManager(this, databaseManager);

        // 初始化安全防护系统（拦截扫描机器人/可疑用户名/频率超限，记录 SECURITY_BLOCK 日志）
        // 依赖 LogManager；在玩家登录最早期（AsyncPlayerPreLoginEvent）拦截，需先于其它业务模块就绪
        securityManager = new SecurityManager(this, databaseManager, logManager);
        SecurityListener securityListener = new SecurityListener(securityManager);
        SecurityCommand securityCommand = new SecurityCommand(securityManager);
        getCommand("security").setExecutor(securityCommand);
        getCommand("security").setTabCompleter(securityCommand);

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
        fishingListener = new FishingListener(fishingManager);
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

        // 初始化家园系统（依赖 DatabaseManager 和 LogManager）
        homeManager = new HomeManager(this, databaseManager, logManager);
        HomeListener homeListener = new HomeListener(this, homeManager);
        MagicTableGUI magicTableGUI = new MagicTableGUI(this, homeManager, economyManager);
        GrindstoneGUI grindstoneGUI = new GrindstoneGUI(this, homeManager);
        homeListener.setFacilityGUIs(magicTableGUI, grindstoneGUI);
        scoreboardManager = new HomelandScoreboardManager(homeManager);
        scoreboardManager.startRefreshTask(this);
        homeListener.setScoreboardManager(scoreboardManager);
        homeCommand = new HomeCommand(homeManager, homeListener);
        getCommand("homeland").setExecutor(homeCommand);
        getCommand("homeland").setTabCompleter(homeCommand);

        // 注册中央缓存驱逐监听器（玩家退出时统一清理各管理器内存缓存）
        CacheEvictionListener cacheEvictionListener = new CacheEvictionListener(
                homeManager, economyManager, fishingManager, titleManager,
                fishingListener, homeCommand, scoreboardManager);

        // 注册事件监听器
        getServer().getPluginManager().registerEvents(securityListener, this);
        getServer().getPluginManager().registerEvents(shopListener, this);
        getServer().getPluginManager().registerEvents(generatorListener, this);
        getServer().getPluginManager().registerEvents(fishingListener, this);
        getServer().getPluginManager().registerEvents(fishingCommand.getFishingGUI(), this);
        getServer().getPluginManager().registerEvents(chatListener, this);
        getServer().getPluginManager().registerEvents(titleCommand.getTitleGUI(), this);
        getServer().getPluginManager().registerEvents(logListener, this);
        getServer().getPluginManager().registerEvents(trashBinListener, this);
        getServer().getPluginManager().registerEvents(homeListener, this);
        getServer().getPluginManager().registerEvents(magicTableGUI, this);
        getServer().getPluginManager().registerEvents(grindstoneGUI, this);
        getServer().getPluginManager().registerEvents(cacheEvictionListener, this);

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
        getLogger().info("安全防护系统已加载，状态: " + (securityManager.isEnabled() ? "启用" : "关闭") +
                "，当前封禁 IP: " + securityManager.getActiveBlockCount() + " 个");
        getLogger().info("家园系统已加载，状态: " + (homeManager.isEnabled() ? "启用" : "关闭") +
                "，魔法台奖品: " + homeManager.getMagicTablePrizes().size() + " 种" +
                "，磨石配方: " + homeManager.getGrindstoneRecipes().size() + " 种");
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

    /**
     * 获取安全防护管理器实例
     */
    public SecurityManager getSecurityManager() {
        return securityManager;
    }

    /**
     * 获取家园系统管理器实例
     */
    public HomeManager getHomeManager() {
        return homeManager;
    }
}
