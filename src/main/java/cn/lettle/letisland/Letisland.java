package cn.lettle.letisland;

import cn.lettle.letisland.economy.EconomyCommand;
import cn.lettle.letisland.economy.EconomyManager;
import cn.lettle.letisland.fishing.FishingCommand;
import cn.lettle.letisland.fishing.FishingGUI;
import cn.lettle.letisland.fishing.FishingListener;
import cn.lettle.letisland.fishing.FishingManager;
import cn.lettle.letisland.generator.GeneratorCommand;
import cn.lettle.letisland.generator.GeneratorListener;
import cn.lettle.letisland.generator.GeneratorManager;
import cn.lettle.letisland.shop.ShopCommand;
import cn.lettle.letisland.shop.ShopListener;
import cn.lettle.letisland.shop.ShopManager;
import org.bukkit.plugin.java.JavaPlugin;

public final class Letisland extends JavaPlugin {

    private EconomyManager economyManager;
    private ShopManager shopManager;
    private ShopListener shopListener;
    private GeneratorManager generatorManager;
    private FishingManager fishingManager;

    @Override
    public void onEnable() {
        // 保存默认配置
        saveDefaultConfig();
        saveResource("shop.yml", false);
        saveResource("generator.yml", false);
        saveResource("fishing.yml", false);

        // 初始化经济系统
        String currencySymbol = getConfig().getString("economy.currency-symbol", "$");
        economyManager = new EconomyManager(this, currencySymbol);

        // 初始化商店系统
        shopManager = new ShopManager(getDataFolder());
        shopListener = new ShopListener(this, shopManager, economyManager);

        // 初始化刷石机系统
        generatorManager = new GeneratorManager(getDataFolder(), economyManager);
        GeneratorListener generatorListener = new GeneratorListener(generatorManager);

        // 初始化钓鱼系统
        fishingManager = new FishingManager(this, economyManager);
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

        // 注册事件监听器
        getServer().getPluginManager().registerEvents(shopListener, this);
        getServer().getPluginManager().registerEvents(generatorListener, this);
        getServer().getPluginManager().registerEvents(fishingListener, this);
        getServer().getPluginManager().registerEvents(fishingCommand.getFishingGUI(), this);

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
    }

    @Override
    public void onDisable() {
        // 保存经济数据
        if (economyManager != null) {
            economyManager.saveData();
        }
        getLogger().warning("Letisland 正在关闭... 正在保存数据...");
        getLogger().info("Letisland 已关闭");
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
}
