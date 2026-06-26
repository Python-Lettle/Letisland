package cn.lettle.letisland.generator;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFormEvent;
import org.jetbrains.annotations.NotNull;

/**
 * 刷石机事件监听器
 * 监听水+岩浆生成圆石/石头的事件，按概率替换为矿物
 */
public class GeneratorListener implements Listener {

    private final GeneratorManager generatorManager;

    public GeneratorListener(@NotNull GeneratorManager generatorManager) {
        this.generatorManager = generatorManager;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockForm(@NotNull BlockFormEvent event) {
        // 刷石机系统关闭时不处理
        if (!generatorManager.isEnabled()) {
            return;
        }

        // 只处理生成圆石或石头的情况（刷石机机制）
        Material newType = event.getNewState().getType();
        if (newType != Material.COBBLESTONE && newType != Material.STONE) {
            return;
        }

        // 按当前等级概率替换为矿物
        Material mineral = generatorManager.rollMineral();
        if (mineral != null) {
            event.getNewState().setType(mineral);
        }
    }
}
