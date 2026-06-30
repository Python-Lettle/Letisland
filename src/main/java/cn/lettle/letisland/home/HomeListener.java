package cn.lettle.letisland.home;

import cn.lettle.letisland.ship.ShipyardGUI;
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
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 家园系统 GUI 监听器
 * 负责主面板、仓库、升级、设施菜单的 GUI 渲染与点击路由
 * 魔法台和磨石 GUI 由独立的 MagicTableGUI / GrindstoneGUI 处理
 */
public class HomeListener implements Listener {

    private static final String MAIN_TITLE = "§6§l家园系统";
    private static final String WAREHOUSE_TITLE = "§6§l家园仓库";
    private static final String UPGRADE_TITLE = "§6§l家园升级";
    private static final String FACILITY_TITLE = "§6§l家园设施";

    private static final int MAIN_SIZE = 54;
    private static final int WAREHOUSE_SIZE = 54;
    private static final int UPGRADE_SIZE = 54;
    private static final int FACILITY_SIZE = 54;

    // 主面板槽位
    private static final int MAIN_WAREHOUSE_SLOT = 11;
    private static final int MAIN_UPGRADE_SLOT = 13;
    private static final int MAIN_FACILITY_SLOT = 15;
    private static final int MAIN_INFO_SLOT = 49;
    private static final int MAIN_SCOREBOARD_SLOT = 51;
    private static final int MAIN_CLOSE_SLOT = 52;

    // 仓库面板槽位（6x9 布局）
    static final int WAREHOUSE_INPUT_START = 36;
    static final int WAREHOUSE_INPUT_END = 44;      // 第5行输入区（9格，列0-8）
    static final int WAREHOUSE_PREV_PAGE_SLOT = 45;  // 上一页
    static final int WAREHOUSE_NEXT_PAGE_SLOT = 47;  // 下一页
    static final int WAREHOUSE_BACK_SLOT = 51;       // 返回主面板
    static final int WAREHOUSE_SUBMIT_SLOT = 49;     // 提交
    static final int WAREHOUSE_CLOSE_SLOT = 52;       // 关闭
    static final int WAREHOUSE_PAGE_SIZE = 36;        // 每页显示36种物品（4行x9列）
    static final int[] WAREHOUSE_DISPLAY_SLOTS = {
            0, 1, 2, 3, 4, 5, 6, 7, 8,
            9, 10, 11, 12, 13, 14, 15, 16, 17,
            18, 19, 20, 21, 22, 23, 24, 25, 26,
            27, 28, 29, 30, 31, 32, 33, 34, 35
    };

    // 升级面板槽位
    private static final int UPGRADE_INFO_SLOT = 11;
    private static final int UPGRADE_BUTTON_SLOT = 13;
    private static final int UPGRADE_BACK_SLOT = 22;

    // 设施菜单槽位
    static final int FACILITY_MAGIC_SLOT = 11;
    static final int FACILITY_SHIPYARD_SLOT = 13;
    static final int FACILITY_GRINDSTONE_SLOT = 15;
    private static final int FACILITY_BACK_SLOT = 22;

    private final JavaPlugin plugin;
    private final HomeManager homeManager;
    private MagicTableGUI magicTableGUI;
    private GrindstoneGUI grindstoneGUI;
    private ShipyardGUI shipyardGUI;
    private HomelandScoreboardManager scoreboardManager;

    public HomeListener(@NotNull JavaPlugin plugin, @NotNull HomeManager homeManager) {
        this.plugin = plugin;
        this.homeManager = homeManager;
    }

    /** 由 Letisland.java 装配时注入 */
    public void setFacilityGUIs(@NotNull MagicTableGUI magicTableGUI, @NotNull GrindstoneGUI grindstoneGUI,
                                @NotNull ShipyardGUI shipyardGUI) {
        this.magicTableGUI = magicTableGUI;
        this.grindstoneGUI = grindstoneGUI;
        this.shipyardGUI = shipyardGUI;
    }

    /** 由 Letisland.java 装配时注入计分板管理器 */
    public void setScoreboardManager(@NotNull HomelandScoreboardManager scoreboardManager) {
        this.scoreboardManager = scoreboardManager;
    }

    // ==================== 主面板 ====================

    public void openMain(@NotNull Player player) {
        var opt = homeManager.getHomelandByMember(player.getUniqueId());
        if (opt.isEmpty()) {
            player.sendMessage("§c你还没有家园，使用 §e/hd create <家园名> §c创建一个吧");
            return;
        }
        int homelandId = opt.get();
        HomeManager.HomelandInfo home = homeManager.getHomeland(homelandId);
        if (home == null) {
            player.sendMessage("§c家园数据异常");
            return;
        }
        Inventory inv = Bukkit.createInventory(new MainPanelHolder(), MAIN_SIZE, dynTitle(MAIN_TITLE, player));
        if (inv.getHolder() instanceof MainPanelHolder h) h.setInventory(inv);
        fillBackground(inv, MAIN_SIZE);
        // 仓库
        inv.setItem(MAIN_WAREHOUSE_SLOT, createNamed(Material.CHEST,
                "§a家园仓库", "§7点击提交资源到仓库", "§7提交可获得贡献值"));
        // 等级升级
        int level = home.level();
        int maxLevel = homeManager.getMaxLevel();
        List<String> upgradeLore = new ArrayList<>();
        upgradeLore.add("§7当前等级: §e" + level + "§7/§e" + maxLevel);
        if (level < maxLevel) {
            Map<Material, Long> reqs = homeManager.getLevelRequirement(level + 1);
            if (reqs != null) {
                upgradeLore.add("§7升级到 §e" + (level + 1) + " §7级需要:");
                Map<Material, Long> warehouse = homeManager.getWarehouse(homelandId);
                for (Map.Entry<Material, Long> e : reqs.entrySet()) {
                    long have = warehouse.getOrDefault(e.getKey(), 0L);
                    String status = have >= e.getValue() ? "§a" : "§c";
                    upgradeLore.add(status + "  " + MaterialNames.toChinese(e.getKey()) + " §7" + have + "/" + e.getValue());
                }
            }
        } else {
            upgradeLore.add("§a已达最高等级！");
        }
        inv.setItem(MAIN_UPGRADE_SLOT, createNamed(Material.EXPERIENCE_BOTTLE,
                "§a家园升级", upgradeLore.toArray(new String[0])));
        // 设施
        inv.setItem(MAIN_FACILITY_SLOT, createNamed(Material.CRAFTING_TABLE,
                "§a家园设施", "§7魔法台: §e" + (homeManager.canUseMagicTable(homelandId) ? "可用" : "锁定"),
                "§7磨石: §e" + (homeManager.canUseGrindstone(homelandId) ? "可用" : "锁定"),
                "§7造船厂: §e" + (homeManager.canUseShipyard(homelandId) ? "可用" : "锁定")));
        // 信息
        long myContribution = homeManager.getContribution(player.getUniqueId());
        int memberCount = homeManager.getMemberCount(homelandId);
        List<String> infoLore = new ArrayList<>();
        infoLore.add("§7家园名: §6" + home.name());
        infoLore.add("§7等级: §e" + level + "§7/§e" + maxLevel);
        infoLore.add("§7成员数: §e" + memberCount);
        infoLore.add("§7我的贡献值: §e" + myContribution);
        infoLore.add("§7拥有者: §f" + home.ownerName());
        inv.setItem(MAIN_INFO_SLOT, createNamed(Material.PAPER, "§6家园信息",
                infoLore.toArray(new String[0])));
        // 计分板开关
        boolean sbOn = scoreboardManager != null && scoreboardManager.isShown(player.getUniqueId());
        inv.setItem(MAIN_SCOREBOARD_SLOT, createNamed(
                sbOn ? Material.LIME_DYE : Material.GRAY_DYE,
                sbOn ? "§a贡献榜计分板: §e已开启" : "§7贡献榜计分板: §c已关闭",
                "§7在屏幕右侧显示家族成员贡献排行",
                sbOn ? "§a点击关闭" : "§a点击开启"));
        // 关闭
        inv.setItem(MAIN_CLOSE_SLOT, createNamed(Material.RED_STAINED_GLASS_PANE, "§c关闭"));
        player.openInventory(inv);
    }

    private void handleMainClick(@NotNull InventoryClickEvent event, @NotNull Player player) {
        int slot = event.getRawSlot();
        var opt = homeManager.getHomelandByMember(player.getUniqueId());
        if (opt.isEmpty()) return;
        int homelandId = opt.get();
        switch (slot) {
            case MAIN_WAREHOUSE_SLOT -> openWarehouse(player);
            case MAIN_UPGRADE_SLOT -> openUpgrade(player);
            case MAIN_FACILITY_SLOT -> openFacilityMenu(player);
            case MAIN_INFO_SLOT -> { /* 仅显示，无操作 */ }
            case MAIN_SCOREBOARD_SLOT -> {
                if (scoreboardManager != null) {
                    scoreboardManager.toggle(player);
                    openMain(player);
                }
            }
            case MAIN_CLOSE_SLOT -> player.closeInventory();
            default -> { /* 空白槽，无操作 */ }
        }
        // 拥有者额外可通过 info 按钮解散？保持简单：用命令 /hd disband
    }

    // ==================== 仓库面板（Phase 3 实现） ====================

    public void openWarehouse(@NotNull Player player) {
        var opt = homeManager.getHomelandByMember(player.getUniqueId());
        if (opt.isEmpty()) {
            player.sendMessage("§c你还没有家园");
            return;
        }
        int homelandId = opt.get();
        WarehouseHolder holder = new WarehouseHolder(homelandId);
        Inventory inv = Bukkit.createInventory(holder, WAREHOUSE_SIZE, dynTitle(WAREHOUSE_TITLE, player));
        holder.setInventory(inv);
        renderWarehouse(inv, holder);
        player.openInventory(inv);
    }

    private void renderWarehouse(@NotNull Inventory inv, @NotNull WarehouseHolder holder) {
        int homelandId = holder.homelandId();
        Map<Material, Long> warehouse = homeManager.getWarehouse(homelandId);
        List<Map.Entry<Material, Long>> entries = new ArrayList<>(warehouse.entrySet());
        int totalPages = Math.max(1, (int) Math.ceil(entries.size() / (double) WAREHOUSE_PAGE_SIZE));
        int page = Math.min(holder.page(), totalPages - 1);
        if (page < 0) page = 0;
        holder.page(page);

        int start = page * WAREHOUSE_PAGE_SIZE;
        int end = Math.min(start + WAREHOUSE_PAGE_SIZE, entries.size());

        int idx = 0;
        for (int i = start; i < end; i++) {
            Map.Entry<Material, Long> e = entries.get(i);
            ItemStack item = new ItemStack(e.getKey());
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.setDisplayName("§a" + MaterialNames.toChinese(e.getKey()));
                meta.setLore(List.of("§7仓库数量: §e" + e.getValue()));
                item.setItemMeta(meta);
            }
            inv.setItem(WAREHOUSE_DISPLAY_SLOTS[idx++], item);
        }
        for (int i = idx; i < WAREHOUSE_DISPLAY_SLOTS.length; i++) {
            inv.setItem(WAREHOUSE_DISPLAY_SLOTS[i], createNamed(Material.GRAY_STAINED_GLASS_PANE, " "));
        }
        // 底部非按钮槽位填充
        for (int i = WAREHOUSE_INPUT_END + 1; i < WAREHOUSE_SIZE; i++) {
            if (i == WAREHOUSE_PREV_PAGE_SLOT || i == WAREHOUSE_NEXT_PAGE_SLOT
                    || i == WAREHOUSE_BACK_SLOT || i == WAREHOUSE_SUBMIT_SLOT
                    || i == WAREHOUSE_CLOSE_SLOT) continue;
            inv.setItem(i, createNamed(Material.GRAY_STAINED_GLASS_PANE, " "));
        }
        inv.setItem(WAREHOUSE_PREV_PAGE_SLOT, page > 0
                ? createNamed(Material.ARROW, "§e← 上一页", "§7当前第 §e" + (page + 1) + " §7页")
                : createNamed(Material.GRAY_STAINED_GLASS_PANE, "§8已是第一页"));
        inv.setItem(WAREHOUSE_NEXT_PAGE_SLOT, page < totalPages - 1
                ? createNamed(Material.ARROW, "§e下一页 →", "§7当前第 §e" + (page + 1) + " §7页")
                : createNamed(Material.GRAY_STAINED_GLASS_PANE, "§8已是最后一页"));
        inv.setItem(WAREHOUSE_BACK_SLOT, createNamed(Material.ARROW, "§e← 返回主面板"));
        inv.setItem(WAREHOUSE_SUBMIT_SLOT, createNamed(Material.EMERALD,
                "§a§l提交资源", "§7将输入区的物品提交到仓库", "§7按材质获得贡献值"));
        inv.setItem(WAREHOUSE_CLOSE_SLOT, createNamed(Material.RED_STAINED_GLASS_PANE, "§c关闭（物品退还）"));
    }

    private void handleWarehouseClick(@NotNull InventoryClickEvent event, @NotNull Player player,
                                      @NotNull WarehouseHolder holder) {
        int slot = event.getRawSlot();
        Inventory top = event.getView().getTopInventory();
        if (slot == WAREHOUSE_BACK_SLOT) {
            // 返回前先退还输入区
            returnWarehouseItems(player, top);
            player.closeInventory();
            openMain(player);
            return;
        }
        if (slot == WAREHOUSE_CLOSE_SLOT) {
            // 关闭：onClose 会退还
            player.closeInventory();
            return;
        }
        if (slot == WAREHOUSE_SUBMIT_SLOT) {
            submitWarehouse(player, top, holder);
            return;
        }
        if (slot == WAREHOUSE_PREV_PAGE_SLOT) {
            if (holder.page() > 0) {
                holder.page(holder.page() - 1);
                renderWarehouse(top, holder);
            }
            return;
        }
        if (slot == WAREHOUSE_NEXT_PAGE_SLOT) {
            holder.page(holder.page() + 1);
            renderWarehouse(top, holder);
            return;
        }
        // 输入区 36-44：放入需检查白名单，取出允许
        if (slot >= WAREHOUSE_INPUT_START && slot <= WAREHOUSE_INPUT_END) {
            ItemStack cursor = event.getCursor();
            if (cursor != null && cursor.getType() != Material.AIR) {
                if (!homeManager.isAllowedWarehouseMaterial(cursor.getType())) {
                    player.sendMessage("§c该物品不是仓库接受的基础材料");
                    return;
                }
            }
            event.setCancelled(false);
            return;
        }
        // 玩家自己的背包：shift-click 放入需检查白名单
        if (event.getClickedInventory() != null && !event.getClickedInventory().equals(top)) {
            if (event.isShiftClick()) {
                ItemStack item = event.getCurrentItem();
                if (item != null && item.getType() != Material.AIR) {
                    if (!homeManager.isAllowedWarehouseMaterial(item.getType())) {
                        player.sendMessage("§c该物品不是仓库接受的基础材料");
                        return;
                    }
                }
            }
            event.setCancelled(false);
            return;
        }
        // 显示区只读，保持取消
    }

    private void submitWarehouse(@NotNull Player player, @NotNull Inventory inv, @NotNull WarehouseHolder holder) {
        int homelandId = holder.homelandId();
        long totalContribution = 0;
        int totalItems = 0;
        // 聚合所有材料，批量入库
        Map<Material, Long> aggregated = new HashMap<>();
        for (int i = WAREHOUSE_INPUT_START; i <= WAREHOUSE_INPUT_END; i++) {
            ItemStack item = inv.getItem(i);
            if (item == null || item.getType() == Material.AIR) continue;
            Material mat = item.getType();
            int amount = item.getAmount();
            aggregated.merge(mat, (long) amount, Long::sum);
            long contrib = homeManager.calcContribution(mat, amount);
            totalContribution += contrib;
            totalItems += amount;
            inv.setItem(i, null);
        }
        if (totalItems == 0) {
            player.sendMessage("§c输入区没有物品可提交");
            return;
        }
        // 批量入库 + 单次贡献值更新
        homeManager.addItemsBatch(homelandId, aggregated);
        homeManager.addContribution(player.getUniqueId(), totalContribution);
        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1f, 1f);
        player.sendMessage("§a提交了 §e" + totalItems + " §a个物品，获得 §e" + totalContribution + " §a贡献值");
        renderWarehouse(inv, holder);
    }

    private void returnWarehouseItems(@NotNull Player player, @NotNull Inventory inv) {
        for (int i = WAREHOUSE_INPUT_START; i <= WAREHOUSE_INPUT_END; i++) {
            ItemStack item = inv.getItem(i);
            if (item == null || item.getType() == Material.AIR) continue;
            inv.setItem(i, null);
            Map<Integer, ItemStack> leftover = player.getInventory().addItem(item);
            for (ItemStack drop : leftover.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), drop);
            }
        }
    }

    // ==================== 升级面板 ====================

    public void openUpgrade(@NotNull Player player) {
        var opt = homeManager.getHomelandByMember(player.getUniqueId());
        if (opt.isEmpty()) {
            player.sendMessage("§c你还没有家园");
            return;
        }
        int homelandId = opt.get();
        HomeManager.HomelandInfo home = homeManager.getHomeland(homelandId);
        if (home == null) {
            player.sendMessage("§c家园数据异常");
            return;
        }
        int level = home.level();
        int maxLevel = homeManager.getMaxLevel();
        Inventory inv = Bukkit.createInventory(new UpgradeHolder(homelandId), UPGRADE_SIZE, dynTitle(UPGRADE_TITLE, player));
        if (inv.getHolder() instanceof UpgradeHolder h) h.setInventory(inv);
        fillBackground(inv, UPGRADE_SIZE);
        // 信息
        List<String> infoLore = new ArrayList<>();
        infoLore.add("§7家园: §6" + home.name());
        infoLore.add("§7当前等级: §e" + level + "§7/§e" + maxLevel);
        inv.setItem(UPGRADE_INFO_SLOT, createNamed(Material.PAPER, "§6等级信息",
                infoLore.toArray(new String[0])));
        // 升级按钮
        if (level >= maxLevel) {
            inv.setItem(UPGRADE_BUTTON_SLOT, createNamed(Material.GOLD_BLOCK, "§6已达最高等级"));
        } else {
            Map<Material, Long> reqs = homeManager.getLevelRequirement(level + 1);
            Map<Material, Long> warehouse = homeManager.getWarehouse(homelandId);
            List<String> lore = new ArrayList<>();
            lore.add("§7升级到 §e" + (level + 1) + " §7级需要:");
            boolean allMet = true;
            if (reqs != null) {
                for (Map.Entry<Material, Long> e : reqs.entrySet()) {
                    long have = warehouse.getOrDefault(e.getKey(), 0L);
                    String status = have >= e.getValue() ? "§a" : "§c";
                    if (have < e.getValue()) allMet = false;
                    lore.add(status + "  " + MaterialNames.toChinese(e.getKey()) + " §7" + have + "/" + e.getValue());
                }
            }
            lore.add(allMet ? "§a§l点击升级！" : "§c资源不足");
            inv.setItem(UPGRADE_BUTTON_SLOT, createNamed(
                    allMet ? Material.LIME_WOOL : Material.RED_WOOL,
                    "§a升级到 " + (level + 1) + " 级",
                    lore.toArray(new String[0])));
        }
        inv.setItem(UPGRADE_BACK_SLOT, createNamed(Material.ARROW, "§e← 返回主面板"));
        player.openInventory(inv);
    }

    private void handleUpgradeClick(@NotNull InventoryClickEvent event, @NotNull Player player,
                                    @NotNull UpgradeHolder holder) {
        int slot = event.getRawSlot();
        if (slot == UPGRADE_BACK_SLOT) {
            player.closeInventory();
            openMain(player);
            return;
        }
        if (slot == UPGRADE_BUTTON_SLOT) {
            HomeManager.UpgradeResult result = homeManager.upgradeLevel(holder.homelandId());
            player.sendMessage(result.message());
            if (result.success()) {
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
                // 全服广播
                HomeManager.HomelandInfo home = homeManager.getHomeland(holder.homelandId());
                if (home != null) {
                    Bukkit.broadcastMessage("§6[家园] §7家园 §e" + home.name() +
                            " §7已升级到 §e" + result.newLevel() + " §7级！");
                }
                openUpgrade(player);
            }
        }
    }

    // ==================== 设施菜单 ====================

    public void openFacilityMenu(@NotNull Player player) {
        var opt = homeManager.getHomelandByMember(player.getUniqueId());
        if (opt.isEmpty()) {
            player.sendMessage("§c你还没有家园");
            return;
        }
        int homelandId = opt.get();
        Inventory inv = Bukkit.createInventory(new FacilityMenuHolder(homelandId), FACILITY_SIZE, dynTitle(FACILITY_TITLE, player));
        if (inv.getHolder() instanceof FacilityMenuHolder h) h.setInventory(inv);
        fillBackground(inv, FACILITY_SIZE);
        // 魔法台
        boolean magicUnlocked = homeManager.canUseMagicTable(homelandId);
        inv.setItem(FACILITY_MAGIC_SLOT, magicUnlocked
                ? createNamed(Material.ENCHANTING_TABLE, "§d魔法台",
                        "§7消耗 §e" + (int) homeManager.getMagicTableCostCoins() + " 金币 + "
                                + homeManager.getMagicTableCostContribution() + " 贡献值",
                        "§7进行一次抽奖")
                : createNamed(Material.GRAY_STAINED_GLASS_PANE, "§8魔法台（1级解锁）"));
        // 磨石
        boolean grindUnlocked = homeManager.canUseGrindstone(homelandId);
        inv.setItem(FACILITY_GRINDSTONE_SLOT, grindUnlocked
                ? createNamed(Material.GRINDSTONE, "§a磨石", "§7放入材料加工", "§7不消耗贡献值")
                : createNamed(Material.GRAY_STAINED_GLASS_PANE, "§8磨石（2级解锁）"));
        // 造船厂
        boolean shipUnlocked = homeManager.canUseShipyard(homelandId);
        inv.setItem(FACILITY_SHIPYARD_SLOT, shipUnlocked
                ? createNamed(Material.OAK_BOAT, "§b造船厂", "§7升级船只组件", "§7需骑船时生效加成")
                : createNamed(Material.GRAY_STAINED_GLASS_PANE, "§8造船厂（3级解锁）"));
        inv.setItem(FACILITY_BACK_SLOT, createNamed(Material.ARROW, "§e← 返回主面板"));
        player.openInventory(inv);
    }

    private void handleFacilityClick(@NotNull InventoryClickEvent event, @NotNull Player player,
                                     @NotNull FacilityMenuHolder holder) {
        int slot = event.getRawSlot();
        if (slot == FACILITY_BACK_SLOT) {
            player.closeInventory();
            openMain(player);
            return;
        }
        if (slot == FACILITY_MAGIC_SLOT) {
            if (homeManager.canUseMagicTable(holder.homelandId())) {
                player.closeInventory();
                if (magicTableGUI != null) magicTableGUI.open(player);
            } else {
                player.sendMessage("§c魔法台需要家园 1 级解锁");
            }
            return;
        }
        if (slot == FACILITY_GRINDSTONE_SLOT) {
            if (homeManager.canUseGrindstone(holder.homelandId())) {
                player.closeInventory();
                if (grindstoneGUI != null) grindstoneGUI.open(player);
            } else {
                player.sendMessage("§c磨石需要家园 2 级解锁");
            }
            return;
        }
        if (slot == FACILITY_SHIPYARD_SLOT) {
            if (homeManager.canUseShipyard(holder.homelandId())) {
                player.closeInventory();
                if (shipyardGUI != null) shipyardGUI.open(player);
            } else {
                player.sendMessage("§c造船厂需要家园 3 级解锁");
            }
        }
    }

    // ==================== 事件处理 ====================

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(@NotNull InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Inventory top = event.getView().getTopInventory();
        InventoryHolder holder = top.getHolder();
        // 仅处理家园GUI，其他容器（如普通箱子、工作台）不干预
        if (holder instanceof MainPanelHolder) {
            event.setCancelled(true);
            handleMainClick(event, player);
        } else if (holder instanceof WarehouseHolder wh) {
            event.setCancelled(true);
            handleWarehouseClick(event, player, wh);
        } else if (holder instanceof UpgradeHolder uh) {
            event.setCancelled(true);
            handleUpgradeClick(event, player, uh);
        } else if (holder instanceof FacilityMenuHolder fh) {
            event.setCancelled(true);
            handleFacilityClick(event, player, fh);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrag(@NotNull InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        InventoryHolder holder = top.getHolder();
        if (holder instanceof WarehouseHolder) {
            // 仅允许在输入区拖拽
            for (int slot : event.getRawSlots()) {
                if (slot < WAREHOUSE_INPUT_START || slot > WAREHOUSE_INPUT_END) {
                    event.setCancelled(true);
                    return;
                }
            }
            // 检查拖拽物品是否在白名单内
            ItemStack dragged = event.getOldCursor();
            if (dragged != null && dragged.getType() != Material.AIR) {
                if (!homeManager.isAllowedWarehouseMaterial(dragged.getType())) {
                    event.setCancelled(true);
                }
            }
        } else if (holder instanceof MainPanelHolder || holder instanceof UpgradeHolder
                || holder instanceof FacilityMenuHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(@NotNull InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        Inventory inv = event.getInventory();
        if (inv.getHolder() instanceof WarehouseHolder) {
            returnWarehouseItems(player, inv);
        }
    }

    // ==================== 工具方法 ====================

    @NotNull
    private String dynTitle(@NotNull String base, @NotNull Player player) {
        long contrib = homeManager.getContribution(player.getUniqueId());
        return base + " §7| §e贡献: " + contrib;
    }

    private void fillBackground(@NotNull Inventory inv, int size) {
        for (int i = 0; i < size; i++) {
            inv.setItem(i, createNamed(Material.GRAY_STAINED_GLASS_PANE, " "));
        }
    }

    @NotNull
    private ItemStack createNamed(@NotNull Material material, @NotNull String name, @NotNull String... lore) {
        return ItemBuilder.createNamed(material, name, lore);
    }

    // ==================== Holder 类 ====================

    public static class MainPanelHolder implements InventoryHolder {
        private Inventory inventory;
        void setInventory(Inventory inv) { this.inventory = inv; }
        @Override public @NotNull Inventory getInventory() { return inventory; }
    }

    public static class WarehouseHolder implements InventoryHolder {
        private final int homelandId;
        private int page = 0;
        private Inventory inventory;
        WarehouseHolder(int homelandId) { this.homelandId = homelandId; }
        int homelandId() { return homelandId; }
        int page() { return page; }
        void page(int p) { this.page = p; }
        void setInventory(Inventory inv) { this.inventory = inv; }
        @Override public @NotNull Inventory getInventory() { return inventory; }
    }

    public static class UpgradeHolder implements InventoryHolder {
        private final int homelandId;
        private Inventory inventory;
        UpgradeHolder(int homelandId) { this.homelandId = homelandId; }
        int homelandId() { return homelandId; }
        void setInventory(Inventory inv) { this.inventory = inv; }
        @Override public @NotNull Inventory getInventory() { return inventory; }
    }

    public static class FacilityMenuHolder implements InventoryHolder {
        private final int homelandId;
        private Inventory inventory;
        FacilityMenuHolder(int homelandId) { this.homelandId = homelandId; }
        int homelandId() { return homelandId; }
        void setInventory(Inventory inv) { this.inventory = inv; }
        @Override public @NotNull Inventory getInventory() { return inventory; }
    }
}
