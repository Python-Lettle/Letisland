package cn.lettle.letisland.util;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * 物品构建工具类
 * 替代散布在各 GUI 类中的 createNamed / grayPane 重复实现
 */
public final class ItemBuilder {

    private ItemBuilder() {}

    /**
     * 创建带名称和 lore 的物品
     *
     * @param material 材质
     * @param name     显示名称（支持 § 颜色码）
     * @param lore     lore 行（可选，每行支持 § 颜色码）
     * @return 构建好的 ItemStack
     */
    @NotNull
    public static ItemStack createNamed(@NotNull Material material, @NotNull String name, String... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            if (lore.length > 0) {
                List<String> loreList = new ArrayList<>(lore.length);
                for (String line : lore) {
                    loreList.add(line);
                }
                meta.setLore(loreList);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    /**
     * 创建灰色玻璃板（用作 GUI 背景填充）
     */
    @NotNull
    public static ItemStack grayPane() {
        return createNamed(Material.GRAY_STAINED_GLASS_PANE, " ");
    }
}
