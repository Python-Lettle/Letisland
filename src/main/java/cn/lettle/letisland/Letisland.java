package cn.lettle.letisland;

import cn.lettle.letisland.economy.EconomyCommand;
import cn.lettle.letisland.economy.EconomyManager;
import cn.lettle.letisland.shop.ShopCommand;
import cn.lettle.letisland.shop.ShopListener;
import cn.lettle.letisland.shop.ShopManager;
import org.bukkit.plugin.java.JavaPlugin;

public final class Letisland extends JavaPlugin {

    private EconomyManager economyManager;
    private ShopManager shopManager;
    private ShopListener shopListener;

    @Override
    public void onEnable() {
        // 保存默认配置
        saveDefaultConfig();
        saveResource("shop.yml", false);

        // 初始化经济系统
        String currencySymbol = getConfig().getString("economy.currency-symbol", "$");
        economyManager = new EconomyManager(this, currencySymbol);

        // 初始化商店系统
        shopManager = new ShopManager(getDataFolder());
        shopListener = new ShopListener(this, shopManager, economyManager);

        // 注册命令
        EconomyCommand economyCommand = new EconomyCommand(economyManager);
        getCommand("economy").setExecutor(economyCommand);
        getCommand("economy").setTabCompleter(economyCommand);

        ShopCommand shopCommand = new ShopCommand(shopManager, shopListener);
        getCommand("shop").setExecutor(shopCommand);
        getCommand("shop").setTabCompleter(shopCommand);

        // 注册事件监听器
        getServer().getPluginManager().registerEvents(shopListener, this);

        // 输出启动信息
        getLogger().info("==============================");
        getLogger().info("      Letisland Start!");
        getLogger().info("       Author: Lettle");
        getLogger().info("==============================");
        getLogger().info("经济系统已加载，货币符号: " + currencySymbol);
        getLogger().info("商店系统已加载，购买物品池: " + shopManager.getBuyPool().size() +
                "，出售物品池: " + shopManager.getSellPool().size());
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
}
