package cn.lettle.letisland.home;

import cn.lettle.letisland.economy.EconomyManager;
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
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 魔法台抽奖 GUI（3x9）
 * Row 0/2: 灰色玻璃板分隔
 * Row 1 (slots 9-17): 抽奖动画行，slot 13 为中奖格
 *
 * 双消耗：200金币(经济系统) + 500贡献值
 * 动画：每3tick左移一轮，12-20轮后停在中间显示奖品
 * 动画期间无法关闭面板
 */
public class MagicTableGUI implements Listener {

    private static final String TITLE = "§6§l魔法台";
    private static final int SIZE = 27;
    private static final long ANIMATION_PERIOD_TICKS = 3L;

    private final JavaPlugin plugin;
    private final HomeManager homeManager;
    private final EconomyManager economyManager;
    private final Map<UUID, AnimationState> states = new ConcurrentHashMap<>();

    public MagicTableGUI(@NotNull JavaPlugin plugin, @NotNull HomeManager homeManager,
                         @NotNull EconomyManager economyManager) {
        this.plugin = plugin;
        this.homeManager = homeManager;
        this.economyManager = economyManager;
    }

    public void open(@NotNull Player player) {
        double costCoins = homeManager.getMagicTableCostCoins();
        long costContrib = homeManager.getMagicTableCostContribution();
        // 检查金币
        double haveCoins = economyManager.getBalance(player);
        if (haveCoins < costCoins) {
            player.sendMessage("§c需要 §e" + economyManager.format(costCoins) + " §c金币，当前只有 §e"
                    + economyManager.format(haveCoins));
            return;
        }
        // 检查贡献值
        long haveContrib = homeManager.getContribution(player.getUniqueId());
        if (haveContrib < costContrib) {
            player.sendMessage("§c需要 §e" + costContrib + " §c贡献值，当前只有 §e" + haveContrib);
            return;
        }
        // 必须属于家园才能使用（贡献值系统依赖家园）
        if (homeManager.getHomelandByMember(player.getUniqueId()).isEmpty()) {
            player.sendMessage("§c你还没有家园，无法使用魔法台");
            return;
        }
        // 扣除金币
        if (!economyManager.withdraw(player, costCoins)) {
            player.sendMessage("§c扣除金币失败");
            return;
        }
        // 扣除贡献值（DB事务）；失败则退还金币
        if (!homeManager.spendContribution(player.getUniqueId(), costContrib)) {
            economyManager.deposit(player, costCoins);
            player.sendMessage("§c扣除贡献值失败，已退还金币");
            return;
        }
        // 预选中奖奖品
        HomeManager.MagicTablePrize prize = homeManager.rollMagicTablePrize();
        // 构建初始9格随机展示（slots 9-17）
        ItemStack[] row = new ItemStack[9];
        for (int i = 0; i < 9; i++) {
            row[i] = prizeToItem(homeManager.getRandomPrizeForDisplay());
        }
        // 创建 GUI
        Inventory inv = Bukkit.createInventory(new MagicTableHolder(), SIZE, dynTitle(player));
        if (inv.getHolder() instanceof MagicTableHolder h) h.setInventory(inv);
        // Row 0/2 灰色玻璃板
        for (int i = 0; i < 9; i++) inv.setItem(i, grayPane());
        for (int i = 18; i < 27; i++) inv.setItem(i, grayPane());
        // 动画行
        for (int i = 0; i < 9; i++) inv.setItem(9 + i, row[i]);
        // 动画状态
        int targetSteps = ThreadLocalRandom.current().nextInt(12, 21); // 12-20（动画时长翻倍）
        AnimationState state = new AnimationState(inv, row, prize, targetSteps);
        states.put(player.getUniqueId(), state);
        player.openInventory(inv);
        // 启动动画任务
        state.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> tick(player.getUniqueId()),
                ANIMATION_PERIOD_TICKS, ANIMATION_PERIOD_TICKS);
    }

    private void tick(@NotNull UUID playerId) {
        AnimationState state = states.get(playerId);
        if (state == null || state.finished) return;
        Player player = Bukkit.getPlayer(playerId);
        if (player == null || !player.isOnline()) {
            cleanup(playerId);
            return;
        }
        // 左移：row[0] 消失，row[1]→row[0], ..., row[8]→新随机
        for (int i = 0; i < 8; i++) {
            state.row[i] = state.row[i + 1];
        }
        state.row[8] = prizeToItem(homeManager.getRandomPrizeForDisplay());
        // 更新界面
        for (int i = 0; i < 9; i++) {
            state.inv.setItem(9 + i, state.row[i]);
        }
        state.step++;
        if (state.step >= state.targetSteps) {
            finish(player, state);
        }
    }

    private void finish(@NotNull Player player, @NotNull AnimationState state) {
        state.finished = true;
        // 中间格(slot 13 = row[4])设为中奖奖品
        state.inv.setItem(13, prizeToItem(state.finalPrize));
        // 其余变灰色玻璃板突出中奖格
        for (int i = 0; i < 9; i++) {
            if (i != 4) {
                state.inv.setItem(9 + i, grayPane());
            }
        }
        // 烟花音效
        player.playSound(player.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_BLAST, 1f, 1f);
        player.playSound(player.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 1f, 1.2f);
        // 奖品入背包（溢出丢脚下）
        ItemStack prizeItem = new ItemStack(state.finalPrize.material(), state.finalPrize.amount());
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(prizeItem);
        for (ItemStack drop : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), drop);
        }
        player.sendMessage("§6[魔法台] §a抽奖获得: §e" + state.finalPrize.amount() + "x §f"
                + MaterialNames.toChinese(state.finalPrize.material()));
        // 取消动画任务
        if (state.task != null) {
            state.task.cancel();
            state.task = null;
        }
        // 显示"再抽一次"按钮
        state.inv.setItem(22, createRetryButton());
        // state 保留到 onClose 时清理（finished=true 时允许关闭）
    }

    private void cleanup(@NotNull UUID playerId) {
        AnimationState state = states.remove(playerId);
        if (state != null && state.task != null) {
            state.task.cancel();
        }
    }

    // ==================== 事件处理 ====================

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(@NotNull InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof MagicTableHolder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        AnimationState state = states.get(player.getUniqueId());
        if (state == null || !state.finished) return;
        if (event.getRawSlot() == 22) {
            cleanup(player.getUniqueId());
            player.closeInventory();
            open(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrag(@NotNull InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof MagicTableHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(@NotNull InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        if (!(event.getInventory().getHolder() instanceof MagicTableHolder)) return;
        AnimationState state = states.get(player.getUniqueId());
        if (state == null) return;
        if (!state.finished && player.isOnline()) {
            // 动画进行中：下一tick重新打开，阻止关闭
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline() && states.containsKey(player.getUniqueId())) {
                    player.openInventory(state.inv);
                }
            });
        } else {
            // 动画已结束或玩家离线：清理
            cleanup(player.getUniqueId());
        }
    }

    // ==================== 工具方法 ====================

    @NotNull
    private String dynTitle(@NotNull Player player) {
        long contrib = homeManager.getContribution(player.getUniqueId());
        return TITLE + " §7| §e贡献: " + contrib;
    }

    @NotNull
    private ItemStack prizeToItem(@NotNull HomeManager.MagicTablePrize prize) {
        ItemStack item = new ItemStack(prize.material(), Math.max(1, prize.amount()));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§f" + MaterialNames.toChinese(prize.material()));
            meta.setLore(List.of("§7x" + prize.amount()));
            item.setItemMeta(meta);
        }
        return item;
    }

    @NotNull
    private ItemStack grayPane() {
        return ItemBuilder.grayPane();
    }

    @NotNull
    private ItemStack createRetryButton() {
        ItemStack item = new ItemStack(Material.GREEN_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§a§l再抽一次");
            meta.setLore(List.of(
                    "§7消耗 §e" + economyManager.format(homeManager.getMagicTableCostCoins()) + " §7金币 + "
                            + homeManager.getMagicTableCostContribution() + " §7贡献值",
                    "§a点击重新抽奖",
                    "§7或关闭面板退出"
            ));
            item.setItemMeta(meta);
        }
        return item;
    }

    // ==================== 数据类 ====================

    private static class AnimationState {
        final Inventory inv;
        final ItemStack[] row;
        final HomeManager.MagicTablePrize finalPrize;
        final int targetSteps;
        int step = 0;
        boolean finished = false;
        BukkitTask task = null;

        AnimationState(Inventory inv, ItemStack[] row, HomeManager.MagicTablePrize finalPrize, int targetSteps) {
            this.inv = inv;
            this.row = row;
            this.finalPrize = finalPrize;
            this.targetSteps = targetSteps;
        }
    }

    public static class MagicTableHolder implements InventoryHolder {
        private Inventory inventory;
        void setInventory(Inventory inv) { this.inventory = inv; }
        @Override public @NotNull Inventory getInventory() { return inventory; }
    }
}
