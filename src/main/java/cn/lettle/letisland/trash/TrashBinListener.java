package cn.lettle.letisland.trash;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 垃圾桶GUI与事件监听器
 * 提供翻页箱子GUI供玩家取出物品，并定时（每5分钟）清理世界掉落物
 */
public class TrashBinListener implements Listener {

    private static final String GUI_TITLE = "§6§l垃圾桶";

    /** GUI 总槽位（6行） */
    private static final int GUI_SIZE = 54;
    /** 物品区槽位数（前5行） */
    private static final int ITEM_AREA = TrashBinManager.PAGE_SIZE;
    /** 上一页按钮槽位 */
    private static final int PREV_SLOT = 45;
    /** 页码信息槽位 */
    private static final int INFO_SLOT = 49;
    /** 下一页按钮槽位 */
    private static final int NEXT_SLOT = 53;

    /** 清理周期（tick）：5分钟 = 6000tick */
    private static final long CLEANUP_PERIOD_TICKS = 6000L;

    private final JavaPlugin plugin;
    private final TrashBinManager manager;
    /** 记录当前打开垃圾桶的玩家及其界面实例，用于取出/清理后刷新 */
    private final Map<UUID, Inventory> openTrashGUIs = new HashMap<>();

    public TrashBinListener(@NotNull JavaPlugin plugin, @NotNull TrashBinManager manager) {
        this.plugin = plugin;
        this.manager = manager;
        // 每5分钟收集一次世界掉落物（首次延迟5分钟，之后周期5分钟）
        Bukkit.getScheduler().runTaskTimer(plugin, this::runCleanup, CLEANUP_PERIOD_TICKS, CLEANUP_PERIOD_TICKS);
    }

    /**
     * 定时清理任务：收集世界掉落物到垃圾桶并刷新界面
     */
    private void runCleanup() {
        int collected = manager.collectDroppedItems();
        if (collected > 0) {
            Bukkit.broadcastMessage("§b[垃圾桶] §7已清理 §e" + collected
                    + " §7个掉落物，使用 §e/trash §7可取出");
        }
        refreshAllOpenGUIs();
    }

    /**
     * 打开垃圾桶GUI（第1页）
     */
    public void openTrash(@NotNull Player player) {
        Inventory inv = buildInventory(0);
        openTrashGUIs.put(player.getUniqueId(), inv);
        player.openInventory(inv);
    }

    /**
     * 构建指定页的垃圾桶界面
     */
    @NotNull
    private Inventory buildInventory(int page) {
        int pageCount = manager.getPageCount();
        if (page < 0) page = 0;
        if (page >= pageCount) page = pageCount - 1;

        TrashHolder holder = new TrashHolder(page);
        Inventory inv = Bukkit.createInventory(holder, GUI_SIZE, GUI_TITLE);
        holder.setInventory(inv);
        renderInto(inv, page);
        return inv;
    }

    /**
     * 将指定页内容渲染到已存在的界面中（原地刷新，不重新打开）
     */
    private void renderInto(@NotNull Inventory inv, int page) {
        inv.clear();
        int pageCount = manager.getPageCount();
        if (page < 0) page = 0;
        if (page >= pageCount) page = pageCount - 1;

        int start = page * ITEM_AREA;
        for (int i = 0; i < ITEM_AREA; i++) {
            ItemStack item = manager.getItem(start + i);
            if (item != null) {
                inv.setItem(i, item.clone());
            }
        }
        renderNavBar(inv, page, pageCount);
    }

    /**
     * 渲染底部导航栏
     */
    private void renderNavBar(@NotNull Inventory inv, int page, int pageCount) {
        ItemStack filler = createNamed(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = ITEM_AREA; i < GUI_SIZE; i++) {
            inv.setItem(i, filler);
        }
        if (page > 0) {
            inv.setItem(PREV_SLOT, createNamed(Material.ARROW, "§e← 上一页"));
        }
        inv.setItem(INFO_SLOT, createNamed(Material.PAPER,
                "§6第 §e" + (page + 1) + " §6/ §e" + pageCount + " §6页"));
        if (page < pageCount - 1) {
            inv.setItem(NEXT_SLOT, createNamed(Material.ARROW, "§e下一页 →"));
        }
    }

    @NotNull
    private ItemStack createNamed(@NotNull Material material, @NotNull String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            item.setItemMeta(meta);
        }
        return item;
    }

    /**
     * 刷新所有打开的垃圾桶界面（取出物品或清理后调用）
     */
    private void refreshAllOpenGUIs() {
        var it = openTrashGUIs.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Inventory> entry = it.next();
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) {
                it.remove();
                continue;
            }
            Inventory inv = entry.getValue();
            if (!(inv.getHolder() instanceof TrashHolder holder)) {
                it.remove();
                continue;
            }
            // 物品被取出后当前页可能越界，重新钳制
            int pageCount = manager.getPageCount();
            if (holder.getPage() >= pageCount) {
                holder.setPage(pageCount - 1);
            }
            renderInto(inv, holder.getPage());
        }
    }

    // ==================== 事件处理 ====================

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(@NotNull InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof TrashHolder holder)) {
            return;
        }
        // 阻止一切默认操作（防止玩家放入物品或拿走显示用占位）
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        // 仅处理点击顶部垃圾桶界面
        Inventory clicked = event.getClickedInventory();
        if (clicked == null || !clicked.equals(top)) {
            return;
        }

        int slot = event.getRawSlot();
        int page = holder.getPage();

        // 上一页
        if (slot == PREV_SLOT) {
            if (page > 0) {
                holder.setPage(page - 1);
                renderInto(top, page - 1);
            }
            return;
        }
        // 下一页
        if (slot == NEXT_SLOT) {
            int pageCount = manager.getPageCount();
            if (page < pageCount - 1) {
                holder.setPage(page + 1);
                renderInto(top, page + 1);
            }
            return;
        }

        // 点击物品区：取出该物品
        if (slot >= 0 && slot < ITEM_AREA) {
            int index = page * ITEM_AREA + slot;
            ItemStack item = manager.takeItem(index);
            if (item == null) {
                return;
            }
            // 优先放入背包，溢出部分丢到玩家脚下
            Map<Integer, ItemStack> leftover = player.getInventory().addItem(item);
            for (ItemStack remaining : leftover.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), remaining);
            }
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1f, 1f);
            // 刷新所有打开的垃圾桶界面（取出后下标变化）
            refreshAllOpenGUIs();
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrag(@NotNull InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof TrashHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(@NotNull InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof TrashHolder) {
            openTrashGUIs.remove(event.getPlayer().getUniqueId());
        }
    }

    /**
     * 垃圾桶界面的 InventoryHolder，携带当前页码
     */
    public static class TrashHolder implements InventoryHolder {
        private int page;
        private Inventory inventory;

        TrashHolder(int page) {
            this.page = page;
        }

        int getPage() {
            return page;
        }

        void setPage(int page) {
            this.page = page;
        }

        void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }
}
