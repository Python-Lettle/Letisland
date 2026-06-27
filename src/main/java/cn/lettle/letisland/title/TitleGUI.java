package cn.lettle.letisland.title;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 称号图鉴GUI
 * 显示所有称号，点击未解锁称号可尝试解锁，点击已解锁称号可佩戴
 */
public class TitleGUI implements Listener {

    private final TitleManager titleManager;

    private static final String CODEX_GUI_TITLE = "§6§l称号图鉴";

    public TitleGUI(@NotNull TitleManager titleManager) {
        this.titleManager = titleManager;
    }

    public void openCodexGUI(@NotNull Player player) {
        List<String> titleIds = new ArrayList<>(titleManager.getTitleConfigs().keySet());
        int titleCount = titleIds.size();
        // 行数：称号数量 + 1（关闭按钮），向上取整，1~6行
        int rows = Math.max(1, Math.min(6, (int) Math.ceil((titleCount + 1) / 9.0)));
        int size = rows * 9;

        CodexHolder holder = new CodexHolder(titleIds);
        Inventory inv = Bukkit.createInventory(holder, size, CODEX_GUI_TITLE);
        holder.setInventory(inv);

        UUID playerId = player.getUniqueId();
        String currentTitle = titleManager.getCurrentTitle(playerId);

        int slot = 0;
        for (String titleId : titleIds) {
            TitleManager.TitleConfig title = titleManager.getTitleConfig(titleId);
            if (title == null) continue;

            boolean unlocked = titleManager.isUnlocked(playerId, titleId);
            boolean isCurrent = titleId.equals(currentTitle);

            inv.setItem(slot, createTitleItem(title, unlocked, isCurrent, player));
            slot++;
        }

        // 最后一格放关闭按钮
        ItemStack back = new ItemStack(Material.ARROW);
        ItemMeta backMeta = back.getItemMeta();
        if (backMeta != null) {
            backMeta.setDisplayName("§c关闭");
            back.setItemMeta(backMeta);
        }
        inv.setItem(size - 1, back);

        int unlockedCount = titleManager.getUnlockedCount(playerId);
        int total = titleManager.getTitleConfigs().size();
        player.sendMessage("§6[称号] §7已解锁 §e" + unlockedCount + "§7/§e" + total + " §7个称号");

        player.openInventory(inv);
    }

    private ItemStack createTitleItem(TitleManager.TitleConfig title, boolean unlocked,
                                       boolean isCurrent, Player player) {
        ItemStack item;
        if (unlocked && isCurrent) {
            // 当前佩戴：附魔书（视觉特殊）
            item = new ItemStack(Material.ENCHANTED_BOOK);
        } else if (unlocked) {
            // 已解锁未佩戴：命名牌
            item = new ItemStack(Material.NAME_TAG);
        } else {
            // 未解锁：灰色染料
            item = new ItemStack(Material.GRAY_DYE);
        }

        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        List<String> lore = new ArrayList<>();

        if (unlocked) {
            meta.setDisplayName(title.getColor() + title.getName() + (isCurrent ? " §a✓" : ""));
            lore.add("§7介绍: §f" + title.getDescription());
            lore.add("");
            if (isCurrent) {
                lore.add("§a当前已佩戴");
            } else {
                lore.add("§a▶ 点击佩戴此称号");
            }
        } else {
            meta.setDisplayName("§7[未解锁] " + title.getColor() + title.getName());
            lore.add("§7介绍: §f" + title.getDescription());
            lore.add("§7解锁方式: §e" + title.getUnlockMethod());
            lore.add("");

            if (title.isUnlockable()) {
                lore.add("§7所需材料:");
                for (TitleManager.UnlockItem reqItem : title.getUnlockItems()) {
                    int has = titleManager.countItem(player, reqItem.getMaterial());
                    String status = has >= reqItem.getAmount() ? "§a" : "§c";
                    lore.add(status + "  " + reqItem.getDisplayName() +
                            " §7" + has + "/" + reqItem.getAmount());
                }
                lore.add("");
                lore.add("§a▶ 点击尝试解锁（将扣除材料）");
            } else {
                lore.add("§c该称号解锁条件未知");
            }
        }

        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    // ==================== 事件处理 ====================

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(@NotNull InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Inventory inv = event.getInventory();
        if (!(inv.getHolder() instanceof CodexHolder holder)) return;

        // 阻止任何物品移动
        event.setCancelled(true);

        int slot = event.getRawSlot();

        // 关闭按钮（最后一格）
        if (slot == inv.getSize() - 1) {
            player.closeInventory();
            return;
        }

        List<String> titleIds = holder.getTitleIds();
        if (slot < 0 || slot >= titleIds.size()) return;

        String titleId = titleIds.get(slot);
        UUID playerId = player.getUniqueId();

        if (titleManager.isUnlocked(playerId, titleId)) {
            // 已解锁：佩戴
            if (!titleId.equals(titleManager.getCurrentTitle(playerId))) {
                titleManager.setCurrentTitle(playerId, titleId);
                TitleManager.TitleConfig titleConfig = titleManager.getTitleConfig(titleId);
                String titleName = titleConfig != null ? titleConfig.getColor() + titleConfig.getName() : titleId;
                player.sendMessage("§a已佩戴称号: §r" + titleName);
                openCodexGUI(player); // 刷新GUI显示佩戴状态
            }
        } else {
            // 未解锁：尝试解锁
            TitleManager.UnlockResult result = titleManager.tryUnlock(player, titleId);
            player.sendMessage(result.message());
            if (result.success()) {
                openCodexGUI(player); // 刷新GUI显示已解锁状态
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrag(@NotNull InventoryDragEvent event) {
        Inventory inv = event.getInventory();
        if (inv.getHolder() instanceof CodexHolder) {
            event.setCancelled(true);
        }
    }

    /**
     * 图鉴GUI的InventoryHolder，携带槽位与称号ID的映射
     * 用Holder而非Map避免刷新时的同步问题
     */
    private static class CodexHolder implements InventoryHolder {
        private final List<String> titleIds;
        private Inventory inventory;

        CodexHolder(List<String> titleIds) {
            this.titleIds = titleIds;
        }

        List<String> getTitleIds() {
            return titleIds;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }

        void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }
    }
}
