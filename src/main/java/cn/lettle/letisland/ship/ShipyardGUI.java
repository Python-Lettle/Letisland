package cn.lettle.letisland.ship;

import cn.lettle.letisland.home.HomeListener;
import cn.lettle.letisland.home.HomeManager;
import cn.lettle.letisland.util.ItemBuilder;
import cn.lettle.letisland.util.MaterialNames;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 造船厂 GUI
 * slot 11=船帆 / 13=船桨 / 15=船体 / 22=渔网 / 49=返回设施菜单
 * 点击组件槽位升级（消耗家园仓库材料，需家园 3 级）
 */
public class ShipyardGUI implements Listener {

    private static final String TITLE = "§6§l造船厂";
    private static final int SIZE = 54;

    static final int SAIL_SLOT = 11;
    static final int OAR_SLOT = 13;
    static final int HULL_SLOT = 15;
    static final int NET_SLOT = 22;
    private static final int BACK_SLOT = 49;

    private final ShipManager shipManager;
    private final HomeManager homeManager;
    private final HomeListener homeListener;

    public ShipyardGUI(@NotNull ShipManager shipManager, @NotNull HomeManager homeManager,
                       @NotNull HomeListener homeListener) {
        this.shipManager = shipManager;
        this.homeManager = homeManager;
        this.homeListener = homeListener;
    }

    public void open(@NotNull Player player) {
        Inventory inv = Bukkit.createInventory(new ShipyardHolder(), SIZE, TITLE);
        if (inv.getHolder() instanceof ShipyardHolder h) h.setInventory(inv);
        // 背景
        for (int i = 0; i < SIZE; i++) {
            inv.setItem(i, ItemBuilder.grayPane());
        }
        // 仓库持有量（渲染材料状态用）
        Map<Material, Long> warehouse = getWarehouse(player);
        inv.setItem(SAIL_SLOT, buildComponentItem(player, ShipManager.ComponentType.SAIL, warehouse));
        inv.setItem(OAR_SLOT, buildComponentItem(player, ShipManager.ComponentType.OAR, warehouse));
        inv.setItem(HULL_SLOT, buildComponentItem(player, ShipManager.ComponentType.HULL, warehouse));
        inv.setItem(NET_SLOT, buildComponentItem(player, ShipManager.ComponentType.NET, warehouse));
        inv.setItem(BACK_SLOT, ItemBuilder.createNamed(Material.ARROW, "§e← 返回设施菜单"));
        player.openInventory(inv);
    }

    @Nullable
    private Map<Material, Long> getWarehouse(@NotNull Player player) {
        Optional<Integer> opt = homeManager.getHomelandByMember(player.getUniqueId());
        if (opt.isEmpty()) return Map.of();
        return homeManager.getWarehouse(opt.get());
    }

    @NotNull
    private ItemStack buildComponentItem(@NotNull Player player, @NotNull ShipManager.ComponentType type,
                                         @NotNull Map<Material, Long> warehouse) {
        ShipManager.ComponentConfig cfg = shipManager.getComponentConfig(type);
        if (cfg == null) return ItemBuilder.createNamed(Material.BARRIER, "§c配置缺失: " + type);

        int level = shipManager.getComponentLevel(player.getUniqueId(), type);
        int maxLevel = shipManager.getMaxLevel();
        List<String> lore = new ArrayList<>();
        lore.add("§7等级: §e" + level + "§7/§e" + maxLevel);
        lore.add("");
        lore.addAll(effectLore(type, level));
        lore.add("§8(仅骑船时生效)");
        lore.add("");

        if (level >= maxLevel) {
            lore.add("§a已达最高等级");
        } else {
            Map<Material, Long> cost = shipManager.getUpgradeCost(type, level + 1);
            if (cost == null || cost.isEmpty()) {
                lore.add("§c下一级未配置");
            } else {
                lore.add("§7升级所需材料:");
                for (Map.Entry<Material, Long> e : cost.entrySet()) {
                    long have = warehouse.getOrDefault(e.getKey(), 0L);
                    String status = have >= e.getValue() ? "§a" : "§c";
                    lore.add(status + "  " + MaterialNames.toChinese(e.getKey()) + " §7" + have + "/" + e.getValue());
                }
                lore.add("");
                lore.add("§e▶ 点击升级");
            }
        }
        return ItemBuilder.createNamed(cfg.material(), "§b" + cfg.name(), lore.toArray(new String[0]));
    }

    @NotNull
    private List<String> effectLore(@NotNull ShipManager.ComponentType type, int level) {
        List<String> l = new ArrayList<>();
        switch (type) {
            case SAIL -> {
                l.add("§7效果: 提升高等级鱼出现概率");
                if (level > 0) {
                    l.add("§7  当前: 高等级鱼权重 §a+"
                            + (level * shipManager.getSailBonusPerLevel() * 100) + "%");
                }
            }
            case OAR -> {
                l.add("§7效果: 提升划船移动速度");
                if (level > 0) {
                    l.add("§7  当前: 速度上限 §a"
                            + String.format("%.2f", shipManager.getOarMaxSpeedBase()
                            + level * shipManager.getOarMaxSpeedBonusPerLevel()));
                }
            }
            case HULL -> {
                l.add("§7效果: 提升鱼出售价格");
                if (level > 0) {
                    l.add("§7  当前: 售价 §a+"
                            + (level * shipManager.getHullValueBonusPerLevel() * 100) + "%");
                }
            }
            case NET -> {
                l.add("§7效果: 提升插件鱼出现概率");
                if (level > 0) {
                    l.add("§7  当前: 插件鱼概率 §a+"
                            + (level * shipManager.getNetChanceBonusPerLevel() * 100) + "%");
                }
            }
        }
        return l;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(@NotNull InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof ShipyardHolder)) return;
        event.setCancelled(true);

        int slot = event.getRawSlot();
        if (slot == BACK_SLOT) {
            player.closeInventory();
            homeListener.openFacilityMenu(player);
            return;
        }
        ShipManager.ComponentType type = slotToType(slot);
        if (type != null) {
            tryUpgrade(player, type);
        }
    }

    @Nullable
    private ShipManager.ComponentType slotToType(int slot) {
        return switch (slot) {
            case SAIL_SLOT -> ShipManager.ComponentType.SAIL;
            case OAR_SLOT -> ShipManager.ComponentType.OAR;
            case HULL_SLOT -> ShipManager.ComponentType.HULL;
            case NET_SLOT -> ShipManager.ComponentType.NET;
            default -> null;
        };
    }

    private void tryUpgrade(@NotNull Player player, @NotNull ShipManager.ComponentType type) {
        if (!shipManager.isEnabled()) {
            player.sendMessage("§c造船厂未开启");
            return;
        }
        Optional<Integer> opt = homeManager.getHomelandByMember(player.getUniqueId());
        if (opt.isEmpty()) {
            player.sendMessage("§c你还没有家园");
            return;
        }
        int homelandId = opt.get();
        if (!homeManager.canUseShipyard(homelandId)) {
            player.sendMessage("§c造船厂需要家园 3 级解锁");
            return;
        }

        int level = shipManager.getComponentLevel(player.getUniqueId(), type);
        int maxLevel = shipManager.getMaxLevel();
        if (level >= maxLevel) {
            player.sendMessage("§c该组件已达最高等级");
            return;
        }
        Map<Material, Long> cost = shipManager.getUpgradeCost(type, level + 1);
        if (cost == null || cost.isEmpty()) {
            player.sendMessage("§c下一级材料未配置");
            return;
        }
        if (!homeManager.hasResources(homelandId, cost)) {
            player.sendMessage("§c仓库材料不足，无法升级");
            return;
        }
        if (!homeManager.consumeResources(homelandId, cost)) {
            player.sendMessage("§c仓库材料不足，无法升级");
            return;
        }
        if (!shipManager.upgradeComponent(player.getUniqueId(), type)) {
            player.sendMessage("§c升级失败（数据库错误），请联系管理员");
            return;
        }

        ShipManager.ComponentConfig cfg = shipManager.getComponentConfig(type);
        player.sendMessage("§a升级成功！§e" + (cfg != null ? cfg.name() : type) + " §a已升至 §e" + (level + 1) + " §a级");
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1.5f);
        // 刷新 GUI
        open(player);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(@NotNull InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof ShipyardHolder) {
            event.setCancelled(true);
        }
    }

    public static class ShipyardHolder implements InventoryHolder {
        private Inventory inventory;
        void setInventory(Inventory inv) { this.inventory = inv; }
        @Override public @NotNull Inventory getInventory() { return inventory; }
    }
}
