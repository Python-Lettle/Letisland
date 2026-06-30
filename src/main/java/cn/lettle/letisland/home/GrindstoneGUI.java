package cn.lettle.letisland.home;

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
import java.util.List;
import java.util.Map;

/**
 * 磨石 GUI（3x9）
 * slot 11 = 输入格 / slot 13 = 合成按钮 / slot 15 = 输出格
 * 合成规则：按配方 input_num/output_num 合成，受输出格容量限制，剩余材料留在输入格
 */
public class GrindstoneGUI implements Listener {

    private static final String TITLE = "§6§l磨石";
    private static final int SIZE = 27;

    static final int INPUT_SLOT = 11;
    static final int CRAFT_SLOT = 13;
    static final int OUTPUT_SLOT = 15;

    private final HomeManager homeManager;

    public GrindstoneGUI(@NotNull JavaPlugin plugin, @NotNull HomeManager homeManager) {
        this.homeManager = homeManager;
    }

    public void open(@NotNull Player player) {
        Inventory inv = Bukkit.createInventory(new GrindstoneHolder(), SIZE, dynTitle(player));
        if (inv.getHolder() instanceof GrindstoneHolder h) h.setInventory(inv);
        // Row 0/2 灰色玻璃板
        for (int i = 0; i < 9; i++) inv.setItem(i, createNamed(Material.GRAY_STAINED_GLASS_PANE, " "));
        for (int i = 18; i < 27; i++) inv.setItem(i, createNamed(Material.GRAY_STAINED_GLASS_PANE, " "));
        // Row 1 非功能槽位填充
        for (int i = 9; i < 18; i++) {
            if (i == INPUT_SLOT || i == CRAFT_SLOT || i == OUTPUT_SLOT) continue;
            inv.setItem(i, createNamed(Material.GRAY_STAINED_GLASS_PANE, " "));
        }
        // 输入格：保持空白
        // 合成按钮（动态生成配方提示）
        List<String> craftLore = new ArrayList<>();
        craftLore.add("§7放入材料后点击此按钮");
        craftLore.add("§7配方:");
        for (HomeManager.GrindstoneRecipe r : homeManager.getGrindstoneRecipes()) {
            craftLore.add("§7  " + r.inputNum() + " " + MaterialNames.toChinese(r.input())
                    + " → " + r.outputNum() + " " + MaterialNames.toChinese(r.output()));
        }
        inv.setItem(CRAFT_SLOT, createNamed(Material.GREEN_STAINED_GLASS_PANE,
                "§a§l合成", craftLore.toArray(new String[0])));
        // 输出格：保持空白
        player.openInventory(inv);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(@NotNull InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof GrindstoneHolder)) return;
        // 默认取消，按槽位放行
        event.setCancelled(true);

        int slot = event.getRawSlot();
        // 输入格：允许放入和取出
        if (slot == INPUT_SLOT) {
            event.setCancelled(false);
            return;
        }
        // 输出格：仅允许取出（cursor 为空时才放行）
        if (slot == OUTPUT_SLOT) {
            ItemStack cursor = event.getCursor();
            if (cursor == null || cursor.getType() == Material.AIR) {
                // 取出操作：放行
                event.setCancelled(false);
            }
            // 放置操作：保持取消
            return;
        }
        // 合成按钮
        if (slot == CRAFT_SLOT) {
            craft(player, top);
            return;
        }
        // 玩家自己的背包：允许（让玩家能 shift 物品，但 shift 进输入格会被默认取消）
        if (event.getClickedInventory() != null && !event.getClickedInventory().equals(top)) {
            event.setCancelled(false);
        }
    }

    private void craft(@NotNull Player player, @NotNull Inventory inv) {
        ItemStack input = inv.getItem(INPUT_SLOT);
        if (input == null || input.getType() == Material.AIR) {
            player.sendMessage("§c输入格没有材料");
            return;
        }
        Material inputMat = input.getType();
        int inputAmount = input.getAmount();
        HomeManager.GrindstoneRecipe recipe = homeManager.findGrindstoneRecipe(inputMat);
        if (recipe == null) {
            player.sendMessage("§c没有此材料的合成配方");
            return;
        }
        int inputNum = recipe.inputNum();
        int outputNum = recipe.outputNum();

        if (inputAmount < inputNum) {
            player.sendMessage("§c数量不足以合成（需要 §e" + inputNum + " §c个）");
            return;
        }

        ItemStack existing = inv.getItem(OUTPUT_SLOT);
        if (existing != null && existing.getType() != Material.AIR) {
            if (existing.getType() != recipe.output()) {
                player.sendMessage("§c输出格有其他物品，请先取出");
                return;
            }
        }

        int maxStack = recipe.output().getMaxStackSize();
        int existingAmount = (existing != null && existing.getType() != Material.AIR)
                ? existing.getAmount() : 0;
        int remainingCap = maxStack - existingAmount;
        int maxFromInput = (inputAmount / inputNum) * outputNum;
        int maxOutput = Math.min(remainingCap, maxFromInput);
        int craftOps = maxOutput / outputNum;

        if (craftOps <= 0) {
            player.sendMessage("§c输出格无法容纳合成结果，请先取出成品");
            return;
        }

        int consumeInput = craftOps * inputNum;
        int produceOutput = craftOps * outputNum;

        int remainder = inputAmount - consumeInput;
        if (remainder > 0) {
            input.setAmount(remainder);
            inv.setItem(INPUT_SLOT, input);
        } else {
            inv.setItem(INPUT_SLOT, null);
        }
        if (existing == null || existing.getType() == Material.AIR) {
            inv.setItem(OUTPUT_SLOT, new ItemStack(recipe.output(), produceOutput));
        } else {
            existing.setAmount(existingAmount + produceOutput);
            inv.setItem(OUTPUT_SLOT, existing);
        }
        player.playSound(player.getLocation(), Sound.BLOCK_GRINDSTONE_USE, 1f, 1f);
        player.sendMessage("§a合成成功：消耗 §e" + consumeInput + " §a个 §f"
                + MaterialNames.toChinese(recipe.input())
                + " §a，获得 §e" + produceOutput + " §a个 §f"
                + MaterialNames.toChinese(recipe.output()));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrag(@NotNull InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof GrindstoneHolder) {
            // 仅允许在输入格拖拽
            for (int slot : event.getRawSlots()) {
                if (slot != INPUT_SLOT) {
                    event.setCancelled(true);
                    return;
                }
            }
        }
    }

    @EventHandler
    public void onClose(@NotNull InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        Inventory inv = event.getInventory();
        if (!(inv.getHolder() instanceof GrindstoneHolder)) return;
        // 退还输入格和输出格的物品
        for (int slot : List.of(INPUT_SLOT, OUTPUT_SLOT)) {
            ItemStack item = inv.getItem(slot);
            if (item == null || item.getType() == Material.AIR) continue;
            inv.setItem(slot, null);
            Map<Integer, ItemStack> leftover = player.getInventory().addItem(item);
            for (ItemStack drop : leftover.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), drop);
            }
        }
    }

    @NotNull
    private String dynTitle(@NotNull Player player) {
        long contrib = homeManager.getContribution(player.getUniqueId());
        return TITLE + " §7| §e贡献: " + contrib;
    }

    @NotNull
    private ItemStack createNamed(@NotNull Material material, @NotNull String name, @NotNull String... lore) {
        return cn.lettle.letisland.util.ItemBuilder.createNamed(material, name, lore);
    }

    public static class GrindstoneHolder implements InventoryHolder {
        private Inventory inventory;
        void setInventory(Inventory inv) { this.inventory = inv; }
        @Override public @NotNull Inventory getInventory() { return inventory; }
    }
}
