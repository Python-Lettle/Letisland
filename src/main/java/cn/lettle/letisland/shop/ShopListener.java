package cn.lettle.letisland.shop;

import cn.lettle.letisland.economy.EconomyManager;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 商店界面事件监听器
 * 处理玩家在商店 UI 中的点击操作（购买、出售）
 */
public class ShopListener implements Listener {

    private final JavaPlugin plugin;
    private final ShopManager shopManager;
    private final EconomyManager economyManager;
    private final String currencySymbol;

    /** 记录打开商店的玩家及其界面实例，用于定时更新刷新倒计时 */
    private final Map<UUID, Inventory> openShops = new HashMap<>();

    public ShopListener(@NotNull JavaPlugin plugin, @NotNull ShopManager shopManager,
                        @NotNull EconomyManager economyManager) {
        this.plugin = plugin;
        this.shopManager = shopManager;
        this.economyManager = economyManager;
        this.currencySymbol = economyManager.getCurrencySymbol();

        // 启动定时任务，每秒更新刷新倒计时显示
        Bukkit.getScheduler().runTaskTimer(plugin, this::updateRefreshDisplay, 20L, 20L);
    }

    /**
     * 构建商店界面并打开给玩家
     */
    public void openShop(@NotNull Player player) {
        // 检查是否需要自动刷新
        shopManager.checkAutoRefresh();
        // 构建界面（标题中带玩家余额）
        Inventory inv = Bukkit.createInventory(new ShopHolder(), ShopManager.SHOP_SIZE, buildShopTitle(player));
        renderShop(inv);
        player.openInventory(inv);
        openShops.put(player.getUniqueId(), inv);
    }

    /**
     * 构建包含玩家余额的商店标题
     */
    @NotNull
    private String buildShopTitle(@NotNull Player player) {
        return "§6§l空岛商店 §7| §e余额: " + economyManager.format(economyManager.getBalance(player));
    }

    /**
     * 更新当前打开的商店界面标题（用于交易后刷新余额显示）
     */
    private void updateShopTitle(@NotNull Player player) {
        InventoryView view = player.getOpenInventory();
        if (view.getTopInventory().getHolder() instanceof ShopHolder) {
            view.setTitle(buildShopTitle(player));
        }
    }

    /**
     * 渲染商店内容
     */
    private void renderShop(@NotNull Inventory inv) {
        // 填充购买区
        for (Map.Entry<Integer, ShopItem> entry : shopManager.getCurrentStock().entrySet()) {
            ShopItem shopItem = entry.getValue();
            ItemStack displayItem = shopItem.createDisplayItem(currencySymbol);
            inv.setItem(entry.getKey(), displayItem);
        }

        // 填充分隔栏
        renderSeparator(inv);
    }

    /**
     * 渲染分隔栏
     * 左4格：灰色玻璃板，"↑上面是出售商店↑"
     * 中1格：红色玻璃板，"还有xx分钟刷新商店"
     * 右4格：灰色玻璃板，"↓下面是收购商店↓"
     */
    private void renderSeparator(@NotNull Inventory inv) {
        ItemStack leftSep = createNamedPane(Material.GRAY_STAINED_GLASS_PANE, "§7↑上面是出售商店↑");
        ItemStack rightSep = createNamedPane(Material.GRAY_STAINED_GLASS_PANE, "§7↓下面是收购商店↓");
        ItemStack middleSep = createRefreshPane();

        // 左4格
        for (int i = ShopManager.SEPARATOR_START; i < ShopManager.SEPARATOR_START + 4; i++) {
            inv.setItem(i, leftSep);
        }
        // 中1格
        inv.setItem(ShopManager.SEPARATOR_START + 4, middleSep);
        // 右4格
        for (int i = ShopManager.SEPARATOR_START + 5; i <= ShopManager.SEPARATOR_END; i++) {
            inv.setItem(i, rightSep);
        }
    }

    /**
     * 创建带名称的玻璃板
     */
    @NotNull
    private ItemStack createNamedPane(@NotNull Material material, @NotNull String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            item.setItemMeta(meta);
        }
        return item;
    }

    /**
     * 创建刷新倒计时玻璃板（红色）
     */
    @NotNull
    private ItemStack createRefreshPane() {
        long remaining = shopManager.getRemainingMinutes();
        String name = "§c还有 §e" + remaining + " §c分钟刷新商店";
        return createNamedPane(Material.RED_STAINED_GLASS_PANE, name);
    }

    /**
     * 定时更新所有打开的商店界面的刷新倒计时显示
     * 同时检查是否需要自动刷新
     */
    private void updateRefreshDisplay() {
        if (openShops.isEmpty()) {
            return;
        }

        // 检查是否到刷新时间
        boolean needRefresh = shopManager.getRemainingMinutes() <= 0;
        if (needRefresh) {
            shopManager.refresh();
        }

        // 更新所有打开的商店界面
        for (Map.Entry<UUID, Inventory> entry : openShops.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) {
                openShops.remove(entry.getKey());
                continue;
            }
            Inventory inv = entry.getValue();
            // 如果刷新了，重新渲染整个商店
            if (needRefresh) {
                inv.clear();
                renderShop(inv);
            } else {
                // 仅更新中间的刷新倒计时玻璃板
                int middleSlot = ShopManager.SEPARATOR_START + 4;
                inv.setItem(middleSlot, createRefreshPane());
            }
        }
    }

    @EventHandler
    public void onClick(@NotNull InventoryClickEvent event) {
        Inventory topInventory = event.getView().getTopInventory();
        if (!(topInventory.getHolder() instanceof ShopHolder)) {
            return;
        }

        // 取消所有默认操作（防止拿取/放入物品）
        event.setCancelled(true);

        Player player = (Player) event.getWhoClicked();

        // 只处理点击商店界面的操作
        if (event.getClickedInventory() == null || !event.getClickedInventory().equals(topInventory)) {
            return;
        }

        int slot = event.getRawSlot();

        // 分隔栏不处理
        if (shopManager.isSeparatorSlot(slot)) {
            return;
        }

        // 左键点击
        if (event.isLeftClick()) {
            if (shopManager.isBuySlot(slot)) {
                handleBuy(player, slot);
            } else if (shopManager.isSellSlot(slot)) {
                handleSell(player, slot);
            }
        }
    }

    /**
     * 处理购买逻辑
     */
    private void handleBuy(@NotNull Player player, int slot) {
        ShopItem shopItem = shopManager.getShopItem(slot);
        if (shopItem == null) {
            return;
        }

        double price = shopItem.getPrice();
        if (price <= 0) {
            player.sendMessage("§c此物品无法购买");
            return;
        }

        // 检查余额
        if (!economyManager.has(player, price)) {
            player.sendMessage("§c余额不足！需要 §e" + economyManager.format(price) +
                    "§c，当前余额 §e" + economyManager.format(economyManager.getBalance(player)));
            return;
        }

        // 检查背包空间
        if (!hasInventorySpace(player, shopItem.createPlainItem())) {
            player.sendMessage("§c背包空间不足！");
            return;
        }

        // 扣款并给予物品
        if (economyManager.withdraw(player, price)) {
            player.getInventory().addItem(shopItem.createPlainItem());
            player.sendMessage("§a购买成功！§7消耗 §e" + economyManager.format(price) +
                    "§7，获得 §f" + shopItem.getAmount() + "x " + shopItem.getMaterial().name());
            player.getWorld().playSound(player.getLocation(), org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
            updateShopTitle(player);
        } else {
            player.sendMessage("§c购买失败：扣款异常");
        }
    }

    /**
     * 处理出售逻辑
     * 自动从玩家背包（含快捷栏）中扣除对应物品
     */
    private void handleSell(@NotNull Player player, int slot) {
        ShopItem shopItem = shopManager.getShopItem(slot);
        if (shopItem == null) {
            return;
        }

        double price = shopItem.getPrice();
        if (price <= 0) {
            player.sendMessage("§c此物品无法出售");
            return;
        }

        int sellAmount = shopItem.getAmount();
        Material sellMaterial = shopItem.getMaterial();

        // 统计背包中该物品的总数
        int available = countItem(player, sellMaterial);
        if (available < sellAmount) {
            player.sendMessage("§c背包中 §f" + sellMaterial.name() + " §c数量不足！需要 §f" +
                    sellAmount + "x§c，当前持有 §f" + available + "x");
            return;
        }

        // 从背包中扣除物品
        removeItem(player, sellMaterial, sellAmount);

        // 增加余额
        economyManager.deposit(player, price);
        player.sendMessage("§a出售成功！§7出售 §f" + sellAmount + "x " + sellMaterial.name() +
                "§7，获得 §e" + economyManager.format(price) +
                "§7，当前余额 §e" + economyManager.format(economyManager.getBalance(player)));
        player.getWorld().playSound(player.getLocation(), org.bukkit.Sound.ENTITY_ITEM_PICKUP, 1f, 1f);
        updateShopTitle(player);
    }

    /**
     * 统计玩家背包中指定材质物品的总数
     */
    private int countItem(@NotNull Player player, @NotNull Material material) {
        int count = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && item.getType() == material) {
                count += item.getAmount();
            }
        }
        return count;
    }

    /**
     * 从玩家背包中扣除指定数量的物品（会跨槽位扣除）
     */
    private void removeItem(@NotNull Player player, @NotNull Material material, int amount) {
        ItemStack[] contents = player.getInventory().getContents();
        int remaining = amount;

        for (int i = 0; i < contents.length && remaining > 0; i++) {
            ItemStack item = contents[i];
            if (item == null || item.getType() != material) {
                continue;
            }
            if (item.getAmount() <= remaining) {
                // 整组扣除
                remaining -= item.getAmount();
                contents[i] = null;
            } else {
                // 部分扣除
                item.setAmount(item.getAmount() - remaining);
                remaining = 0;
            }
        }
        player.getInventory().setContents(contents);
        player.updateInventory();
    }

    /**
     * 检查背包是否有空间放入物品
     */
    private boolean hasInventorySpace(@NotNull Player player, @NotNull ItemStack item) {
        // 模拟添加，检查是否剩余
        ItemStack clone = item.clone();
        java.util.Map<Integer, ItemStack> leftover = player.getInventory().addItem(clone);
        if (!leftover.isEmpty()) {
            // 还原
            player.getInventory().removeItem(item);
            return false;
        }
        // 还原
        player.getInventory().removeItem(item);
        return true;
    }

    /**
     * 防止拖拽
     */
    @EventHandler
    public void onDrag(@NotNull InventoryDragEvent event) {
        Inventory topInventory = event.getView().getTopInventory();
        if (topInventory.getHolder() instanceof ShopHolder) {
            event.setCancelled(true);
        }
    }

    /**
     * 清理记录
     */
    @EventHandler
    public void onClose(@NotNull InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof ShopHolder) {
            openShops.remove(event.getPlayer().getUniqueId());
        }
    }

    /**
     * 商店界面的 InventoryHolder 标记类
     */
    public static class ShopHolder implements InventoryHolder {
        @Override
        public @NotNull Inventory getInventory() {
            return Bukkit.createInventory(this, 0);
        }
    }
}
