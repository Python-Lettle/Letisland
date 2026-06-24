package cn.lettle.letisland;

import org.bukkit.plugin.java.JavaPlugin;

public final class Letisland extends JavaPlugin {

    @Override
    public void onEnable() {
        // 输出一些启动信息
        getLogger().info("==============================");
        getLogger().info("      Letisland Start!");
        getLogger().info("       Author: Lettle");
        getLogger().info("==============================");
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
        getLogger().warning("Letisland 正在关闭... 正在保存数据...");
        getLogger().info("Letisland 已关闭");
    }
}
