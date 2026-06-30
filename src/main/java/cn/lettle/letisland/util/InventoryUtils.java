package cn.lettle.letisland.util;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * 物品栏操作工具类
 * 替代散布在 FishingManager / TitleManager / ShopListener 中的重复实现
 */
public final class InventoryUtils {

    private InventoryUtils() {}

    /**
     * 统计玩家背包中指定材质物品的总数
     */
    public static int countItem(@NotNull Player player, @NotNull Material material) {
        int count = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && item.getType() == material) {
                count += item.getAmount();
            }
        }
        return count;
    }

    /**
     * 检查玩家背包是否有足够数量的指定材质物品
     */
    public static boolean hasEnoughItem(@NotNull Player player, @NotNull Material material, int amount) {
        return countItem(player, material) >= amount;
    }

    /**
     * 从玩家背包中扣除指定数量的物品（跨槽位扣除）
     */
    public static void removeItem(@NotNull Player player, @NotNull Material material, int amount) {
        ItemStack[] contents = player.getInventory().getContents();
        int remaining = amount;
        for (int i = 0; i < contents.length && remaining > 0; i++) {
            ItemStack item = contents[i];
            if (item == null || item.getType() != material) continue;
            if (item.getAmount() <= remaining) {
                remaining -= item.getAmount();
                contents[i] = null;
            } else {
                item.setAmount(item.getAmount() - remaining);
                remaining = 0;
            }
        }
        player.getInventory().setContents(contents);
        player.updateInventory();
    }
}
