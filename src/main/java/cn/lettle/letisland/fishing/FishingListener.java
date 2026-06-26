package cn.lettle.letisland.fishing;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * 钓鱼事件监听器
 * 处理钓鱼奖励生成、经验道具使用和BUFF触发
 */
public class FishingListener implements Listener {

    private final FishingManager fishingManager;
    private final Random random = new Random();

    /** 钓鱼奖励冷却（防止双击收竿重复触发），记录上次奖励时间戳 */
    private final Map<UUID, Long> fishingCooldown = new HashMap<>();

    /** 冷却时间（毫秒） */
    private static final long FISH_COOLDOWN_MS = 500;

    public FishingListener(@NotNull FishingManager fishingManager) {
        this.fishingManager = fishingManager;
    }

    /**
     * 钓鱼事件 - 替换钓鱼奖励
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFish(@NotNull PlayerFishEvent event) {
        if (!fishingManager.isEnabled()) return;

        // 只在成功钓到物品时处理
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH) return;

        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();

        // 冷却检查，防止双击收竿重复触发奖励
        long now = System.currentTimeMillis();
        Long lastTime = fishingCooldown.get(playerId);
        if (lastTime != null && now - lastTime < FISH_COOLDOWN_MS) {
            event.setCancelled(true);
            return;
        }
        fishingCooldown.put(playerId, now);

        int level = fishingManager.getPlayerLevel(playerId);
        Entity caught = event.getCaught();
        if (caught == null) return;

        // 每次钓鱼固定获得1点钓鱼经验
        fishingManager.addExp(playerId, 1);

        // 决定奖励类型
        double roll = random.nextDouble();
        double fishChance = fishingManager.getCustomFishChance();
        double expChance = fishingManager.getExpItemChance();

        if (roll < fishChance) {
            // 钓到自定义鱼
            FishingManager.FishConfig fish = fishingManager.rollFish(level);
            if (fish != null) {
                double weight = fishingManager.rollWeight(fish);
                ItemStack fishItem = fishingManager.createFishItem(fish, weight);
                event.setCancelled(true);
                // 直接将物品给予玩家
                player.getWorld().dropItemNaturally(player.getLocation(), fishItem);
                player.sendMessage("§b[钓鱼] §a你钓到了一条 §e" + fish.getName() +
                        " §a(§f" + weight + " kg§a)！");
            }
        } else if (roll < fishChance + expChance) {
            // 钓到经验道具
            ItemStack expItem = fishingManager.createExpItem();
            event.setCancelled(true);
            player.getWorld().dropItemNaturally(player.getLocation(), expItem);
            player.sendMessage("§b[钓鱼] §a你钓到了一个 §b钓鱼经验卷轴！");
        }

        // 触发BUFF
        if (random.nextDouble() < fishingManager.getBuffChance()) {
            FishingManager.BuffConfig buff = fishingManager.rollBuff();
            if (buff != null) {
                applyBuff(player, buff);
            }
        }
    }

    /**
     * 右键使用经验道具
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(@NotNull PlayerInteractEvent event) {
        if (!fishingManager.isEnabled()) return;
        if (event.getHand() != EquipmentSlot.HAND) return;

        // 只处理右键点击
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;

        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();

        if (fishingManager.isExpItem(item)) {
            event.setCancelled(true);

            int exp = fishingManager.useExpItem(item);
            if (exp <= 0) return;

            // 消耗一个道具
            item.setAmount(item.getAmount() - 1);
            if (item.getAmount() <= 0) {
                player.getInventory().setItemInMainHand(null);
            }
            player.updateInventory();

            // 增加钓鱼经验
            fishingManager.addExp(player.getUniqueId(), exp);
            player.sendMessage("§b[钓鱼] §a使用了经验卷轴，获得 §e" + exp + " §a钓鱼经验！");
        }
    }

    /**
     * 应用BUFF效果
     */
    private void applyBuff(Player player, FishingManager.BuffConfig buff) {
        PotionEffectType effectType = PotionEffectType.getByName(buff.getEffect());
        if (effectType == null) {
            // 处理瞬间治疗/伤害等非药水效果
            if (buff.getEffect().equals("HEAL")) {
                player.setHealth(Math.min(player.getHealth() + 6.0, player.getMaxHealth()));
            } else if (buff.getEffect().equals("HARM")) {
                player.damage(4.0);
            }
        } else {
            player.addPotionEffect(new PotionEffect(effectType,
                    buff.getDuration() * 20, buff.getAmplifier()));
        }

        String prefix = buff.getType().equals("GOOD") ? "§a" : "§c";
        player.sendMessage("§b[钓鱼] " + prefix + "触发了效果: §e" + buff.getName());
    }
}
