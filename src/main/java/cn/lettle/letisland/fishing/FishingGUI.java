package cn.lettle.letisland.fishing;

import cn.lettle.letisland.economy.EconomyManager;
import cn.lettle.letisland.ship.ShipManager;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 钓鱼GUI处理器
 * 包含等级查看/升级GUI和鱼出售GUI
 */
public class FishingGUI implements Listener {

    private final FishingManager fishingManager;
    private final EconomyManager economyManager;
    private final ShipManager shipManager;

    // GUI标题
    private static final String LEVEL_GUI_TITLE = "§6§l钓鱼科技";
    private static final String SELL_GUI_TITLE = "§6§l鱼市场 - 出售鱼类";
    private static final String CODEX_GUI_TITLE = "§6§l鱼类图鉴";

    // 等级GUI的槽位
    private static final int INFO_SLOT = 13;       // 信息显示
    private static final int UPGRADE_SLOT = 31;    // 升级按钮

    // 出售GUI的槽位
    private static final int SELL_AREA_START = 0;
    private static final int SELL_AREA_END = 44;   // 前5行可放鱼
    private static final int CONFIRM_SLOT = 49;     // 确认按钮（第6行中间）
    private static final int CANCEL_SLOT = 45;      // 取消按钮

    // 记录玩家打开的GUI
    private final Map<UUID, String> openGUIs = new HashMap<>();
    private final Set<UUID> sellOpen = new HashSet<>();

    public FishingGUI(@NotNull FishingManager fishingManager, @NotNull EconomyManager economyManager,
                      @NotNull ShipManager shipManager) {
        this.fishingManager = fishingManager;
        this.economyManager = economyManager;
        this.shipManager = shipManager;
    }

    // ==================== 等级GUI ====================

    public void openLevelGUI(@NotNull Player player) {
        Inventory inv = Bukkit.createInventory(null, 36, LEVEL_GUI_TITLE);

        // 填充玻璃板背景
        for (int i = 0; i < 36; i++) {
            inv.setItem(i, createGlassPane(Material.GRAY_STAINED_GLASS_PANE, " "));
        }

        // 信息物品
        int level = fishingManager.getPlayerLevel(player.getUniqueId());
        int exp = fishingManager.getPlayerExp(player.getUniqueId());
        FishingManager.LevelConfig config = fishingManager.getLevelConfigs().get(level);
        int expToNext = config != null ? config.getExpToNext() : 0;
        String levelName = config != null ? config.getName() : "未知";

        ItemStack infoItem = new ItemStack(Material.FISHING_ROD);
        ItemMeta infoMeta = infoItem.getItemMeta();
        if (infoMeta != null) {
            infoMeta.setDisplayName("§6" + levelName);
            List<String> lore = new ArrayList<>();
            lore.add("§7等级: §e" + level + "§7/§e" + fishingManager.getMaxLevel());
            if (expToNext > 0) {
                lore.add("§7经验: §e" + exp + "§7/§e" + expToNext);
                double percent = Math.min(100.0 * exp / expToNext, 100.0);
                lore.add("§7进度: §e" + String.format("%.1f%%", percent));
            } else {
                lore.add("§a已达最高等级！");
            }
            lore.add("");
            lore.add("§7可钓等级: §f" + FishingManager.getTierName(level) + " §7及以下");
            infoMeta.setLore(lore);
            infoItem.setItemMeta(infoMeta);
        }
        inv.setItem(INFO_SLOT, infoItem);

        // 升级按钮
        if (level < fishingManager.getMaxLevel()) {
            FishingManager.LevelConfig nextConfig = fishingManager.getLevelConfigs().get(level + 1);
            boolean expMaxed = fishingManager.isExpMaxed(player.getUniqueId());

            ItemStack upgradeItem = new ItemStack(expMaxed ? Material.LIME_CONCRETE : Material.RED_CONCRETE);
            ItemMeta upgradeMeta = upgradeItem.getItemMeta();
            if (upgradeMeta != null) {
                upgradeMeta.setDisplayName(expMaxed ? "§a点击升级" : "§c经验不足，无法升级");
                List<String> lore = new ArrayList<>();
                if (nextConfig != null) {
                    lore.add("§7下一级: §e" + nextConfig.getName());
                    for (FishingManager.UpgradeCost cost : nextConfig.getUpgradeCost()) {
                        int has = countItem(player, cost.getMaterial());
                        String status = has >= cost.getAmount() ? "§a" : "§c";
                        lore.add(status + "  " + cost.getDisplayName() +
                                " §7" + has + "/" + cost.getAmount());
                    }
                }
                upgradeMeta.setLore(lore);
                upgradeItem.setItemMeta(upgradeMeta);
            }
            inv.setItem(UPGRADE_SLOT, upgradeItem);
        } else {
            inv.setItem(UPGRADE_SLOT, createGlassPane(Material.GOLD_BLOCK, "§6已达最高等级"));
        }

        openGUIs.put(player.getUniqueId(), "level");
        player.openInventory(inv);
    }

    // ==================== 出售GUI ====================

    public void openSellGUI(@NotNull Player player) {
        Inventory inv = Bukkit.createInventory(null, 54, SELL_GUI_TITLE);

        // 第6行（45-53）为操作栏
        for (int i = 45; i < 54; i++) {
            inv.setItem(i, createGlassPane(Material.GRAY_STAINED_GLASS_PANE, " "));
        }

        // 确认按钮（绿色玻璃板）
        ItemStack confirm = new ItemStack(Material.GREEN_STAINED_GLASS_PANE);
        ItemMeta confirmMeta = confirm.getItemMeta();
        if (confirmMeta != null) {
            confirmMeta.setDisplayName("§a§l点击出售全部鱼类");
            List<String> lore = new ArrayList<>();
            lore.add("§7将鱼放入上方区域");
            lore.add("§7点击此按钮出售所有鱼");
            confirmMeta.setLore(lore);
            confirm.setItemMeta(confirmMeta);
        }
        inv.setItem(CONFIRM_SLOT, confirm);

        // 取消按钮（红色玻璃板）
        ItemStack cancel = new ItemStack(Material.RED_STAINED_GLASS_PANE);
        ItemMeta cancelMeta = cancel.getItemMeta();
        if (cancelMeta != null) {
            cancelMeta.setDisplayName("§c§l关闭并退还物品");
            cancelMeta.setLore(List.of("§7关闭界面，物品退还背包"));
            cancel.setItemMeta(cancelMeta);
        }
        inv.setItem(CANCEL_SLOT, cancel);

        openGUIs.put(player.getUniqueId(), "sell");
        sellOpen.add(player.getUniqueId());
        player.openInventory(inv);
    }

    // ==================== 图鉴GUI ====================

    public void openCodexGUI(@NotNull Player player) {
        // 计算需要的行数（每行9格，至少1行）
        int fishCount = fishingManager.getFishConfigs().size();
        int rows = Math.min(6, (int) Math.ceil((fishCount + 9) / 9.0)); // 最后一行留空给返回按钮
        int size = rows * 9;

        Inventory inv = Bukkit.createInventory(null, size, CODEX_GUI_TITLE);

        UUID playerId = player.getUniqueId();
        // 批量查询玩家图鉴数据（一次查询替代 N 条鱼的逐条查询）
        Map<String, FishingManager.CodexEntry> codex = fishingManager.getCodexBatch(playerId);
        int discovered = codex.size();
        int slot = 0;

        for (FishingManager.FishConfig fish : fishingManager.getFishConfigs().values()) {
            FishingManager.CodexEntry entry = codex.get(fish.getId());

            if (entry != null) {
                // 已发现：显示鱼的真实信息
                ItemStack item = new ItemStack(fish.getMaterial());
                ItemMeta meta = item.getItemMeta();
                if (meta != null) {
                    String tierColor = FishingManager.getTierColor(fish.getTier());
                    meta.setDisplayName(tierColor + fish.getName());

                    List<String> lore = new ArrayList<>();
                    lore.add("§7品质: " + tierColor + FishingManager.getTierName(fish.getTier()));
                    lore.add("§7钓到次数: §e" + entry.catchCount());
                    lore.add("§7最高纪录: §e" + entry.maxWeight() + " kg");
                    lore.add("§7重量范围: §f" + fish.getMinWeight() + " - " + fish.getMaxWeight() + " kg");
                    lore.add("§7基础价值: §e" + economyManager.format(fish.getBaseValue()) + "/kg");
                    meta.setLore(lore);
                    // 应用自定义材质（与实际鱼物品保持一致）
                    fishingManager.applyFishCustomModel(meta, fish);
                    item.setItemMeta(meta);
                }
                inv.setItem(slot, item);
            } else {
                // 未发现：显示为未知道具
                ItemStack unknown = new ItemStack(Material.BARRIER);
                ItemMeta meta = unknown.getItemMeta();
                if (meta != null) {
                    meta.setDisplayName("§7??? 未知鱼类");
                    List<String> lore = new ArrayList<>();
                    lore.add("§8品质: " + FishingManager.getTierColor(fish.getTier()) + FishingManager.getTierName(fish.getTier()));
                    lore.add("");
                    lore.add("§8尚未发现此鱼类");
                    meta.setLore(lore);
                    unknown.setItemMeta(meta);
                }
                inv.setItem(slot, unknown);
            }
            slot++;
        }

        // 底部最后一格放返回按钮
        int backSlot = size - 1;
        ItemStack back = new ItemStack(Material.ARROW);
        ItemMeta backMeta = back.getItemMeta();
        if (backMeta != null) {
            backMeta.setDisplayName("§c关闭");
            back.setItemMeta(backMeta);
        }
        inv.setItem(backSlot, back);

        int total = fishingManager.getFishConfigs().size();
        player.sendMessage("§6[图鉴] §7已发现 §e" + discovered + "§7/§e" + total + " §7种鱼类");

        openGUIs.put(player.getUniqueId(), "codex");
        player.openInventory(inv);
    }

    // ==================== 事件处理 ====================

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(@NotNull InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        String guiType = openGUIs.get(player.getUniqueId());
        if (guiType == null) return;

        String title = event.getView().getTitle();
        if (!title.equals(LEVEL_GUI_TITLE) && !title.equals(SELL_GUI_TITLE) && !title.equals(CODEX_GUI_TITLE)) return;

        event.setCancelled(true);

        if (guiType.equals("level")) {
            handleLevelClick(event, player);
        } else if (guiType.equals("sell")) {
            handleSellClick(event, player);
        } else if (guiType.equals("codex")) {
            handleCodexClick(event, player);
        }
    }

    private void handleCodexClick(InventoryClickEvent event, Player player) {
        int slot = event.getRawSlot();
        Inventory inv = event.getInventory();
        // 点击最后一格的关闭按钮
        if (slot == inv.getSize() - 1) {
            player.closeInventory();
        }
    }

    private void handleLevelClick(InventoryClickEvent event, Player player) {
        int slot = event.getRawSlot();
        if (slot == UPGRADE_SLOT) {
            // 点击升级
            FishingManager.UpgradeResult result = fishingManager.tryUpgrade(player);
            player.sendMessage(result.message());
            if (result.success()) {
                // 重新打开GUI刷新
                openLevelGUI(player);
            }
        }
    }

    private void handleSellClick(InventoryClickEvent event, Player player) {
        int slot = event.getRawSlot();
        Inventory inv = event.getInventory();

        if (slot == CONFIRM_SLOT) {
            // 确认出售
            sellAllFish(player, inv);
        } else if (slot == CANCEL_SLOT) {
            // 取消，退还物品
            returnItems(player, inv);
            player.closeInventory();
        } else if (slot >= SELL_AREA_START && slot <= SELL_AREA_END) {
            // 在出售区域内点击 - 允许放入和取出
            event.setCancelled(false);
        } else {
            // 在玩家背包内点击 - 允许shift点击等
            event.setCancelled(false);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrag(@NotNull InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        String guiType = openGUIs.get(player.getUniqueId());
        if (guiType == null) return;

        String title = event.getView().getTitle();
        if (guiType.equals("sell") && title.equals(SELL_GUI_TITLE)) {
            // 只允许在出售区域拖拽
            for (int slot : event.getRawSlots()) {
                if (slot > SELL_AREA_END) {
                    event.setCancelled(true);
                    return;
                }
            }
        } else {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(@NotNull InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        UUID uuid = player.getUniqueId();
        String guiType = openGUIs.remove(uuid);

        if (guiType == null) return;

        // 出售GUI关闭时退还物品
        if (guiType.equals("sell") && sellOpen.remove(uuid)) {
            returnItems(player, event.getInventory());
        }
    }

    // ==================== 出售逻辑 ====================

    private void sellAllFish(Player player, Inventory inv) {
        double totalValue = 0;
        int fishCount = 0;

        for (int i = SELL_AREA_START; i <= SELL_AREA_END; i++) {
            ItemStack item = inv.getItem(i);
            if (item == null || item.getType() == Material.AIR) continue;

            FishingManager.FishItemInfo info = fishingManager.getFishInfo(item);
            if (info != null) {
                totalValue += info.totalValue();
                fishCount += item.getAmount();
                inv.setItem(i, null);
            }
        }

        if (fishCount == 0) {
            player.sendMessage("§c没有可出售的鱼！");
            return;
        }

        // 船体加成（仅骑船时生效）
        if (shipManager.isPlayerOnBoat(player)) {
            int hullLevel = shipManager.getHullLevel(player.getUniqueId());
            if (hullLevel > 0) {
                totalValue *= (1.0 + hullLevel * shipManager.getHullValueBonusPerLevel());
            }
        }

        economyManager.deposit(player, totalValue);
        player.sendMessage("§a出售了 §e" + fishCount + " §a条鱼，获得 §e" +
                economyManager.format(totalValue));
        player.sendMessage("§7当前余额: §e" + economyManager.format(economyManager.getBalance(player)));
    }

    private void returnItems(Player player, Inventory inv) {
        for (int i = SELL_AREA_START; i <= SELL_AREA_END; i++) {
            ItemStack item = inv.getItem(i);
            if (item == null || item.getType() == Material.AIR) continue;
            inv.setItem(i, null);
            // 退还到背包
            Map<Integer, ItemStack> leftover = player.getInventory().addItem(item);
            for (ItemStack drop : leftover.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), drop);
            }
        }
    }

    // ==================== 工具方法 ====================

    private int countItem(Player player, Material material) {
        int count = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && item.getType() == material) {
                count += item.getAmount();
            }
        }
        return count;
    }

    private ItemStack createGlassPane(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            item.setItemMeta(meta);
        }
        return item;
    }
}
