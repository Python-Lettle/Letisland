package cn.lettle.letisland.trash;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 垃圾桶管理器
 * 定时收集世界中的掉落物（Item实体）并存入内存，玩家可从箱子GUI中取出
 * 物品仅存于内存，服务器重启后清空（垃圾桶定位为临时回收站）
 */
public class TrashBinManager {

    /** 每页物品槽位数（5行 x 9列 = 45） */
    public static final int PAGE_SIZE = 45;

    /** 垃圾桶内所有物品（按存入顺序排列，取出时按下标移除） */
    private final List<ItemStack> items = new ArrayList<>();

    /**
     * 添加一个物品堆到垃圾桶（会尝试与现有同类型堆合并以节省槽位）
     */
    public void addItem(@NotNull ItemStack toAdd) {
        if (toAdd.getType().isAir() || toAdd.getAmount() <= 0) {
            return;
        }
        ItemStack add = toAdd.clone();
        for (int i = 0; i < items.size() && add.getAmount() > 0; i++) {
            ItemStack existing = items.get(i);
            if (existing.isSimilar(add) && existing.getAmount() < existing.getMaxStackSize()) {
                int space = existing.getMaxStackSize() - existing.getAmount();
                int move = Math.min(space, add.getAmount());
                existing.setAmount(existing.getAmount() + move);
                add.setAmount(add.getAmount() - move);
            }
        }
        if (add.getAmount() > 0) {
            items.add(add);
        }
    }

    /**
     * 收集所有世界中所有掉落物（Item实体）到垃圾桶
     * 必须在主线程调用（涉及实体移除）
     *
     * @return 本次收集的掉落物实体数量
     */
    public int collectDroppedItems() {
        int collected = 0;
        for (World world : Bukkit.getWorlds()) {
            // 复制列表避免遍历时移除导致 ConcurrentModificationException
            List<Item> entities = new ArrayList<>(world.getEntitiesByClass(Item.class));
            for (Item entity : entities) {
                ItemStack stack = entity.getItemStack();
                if (stack == null || stack.getType().isAir()) {
                    continue;
                }
                entity.remove();
                addItem(stack);
                collected++;
            }
        }
        return collected;
    }

    /**
     * 取出指定下标的物品（从垃圾桶移除并返回）
     *
     * @param index 物品下标（按整页计算）
     * @return 取出的物品，下标越界或为空时返回 null
     */
    @Nullable
    public ItemStack takeItem(int index) {
        if (index < 0 || index >= items.size()) {
            return null;
        }
        return items.remove(index);
    }

    /**
     * 获取指定下标的物品（不移除，用于GUI展示）
     */
    @Nullable
    public ItemStack getItem(int index) {
        if (index < 0 || index >= items.size()) {
            return null;
        }
        return items.get(index);
    }

    /**
     * 物品总数
     */
    public int size() {
        return items.size();
    }

    /**
     * 总页数（至少1页，空垃圾桶也返回1页用于显示空界面）
     */
    public int getPageCount() {
        return Math.max(1, (items.size() + PAGE_SIZE - 1) / PAGE_SIZE);
    }

    /**
     * 垃圾桶是否为空
     */
    public boolean isEmpty() {
        return items.isEmpty();
    }
}
