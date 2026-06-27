package cn.lettle.letisland.title;

import cn.lettle.letisland.fishing.FishingManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * 聊天监听器
 * 将玩家消息格式化为：[称号] [钓鱼等级] [玩家名称] : [消息]
 * 称号显示为称号自身的名称颜色
 */
public class ChatListener implements Listener {

    private final TitleManager titleManager;
    private final FishingManager fishingManager;

    public ChatListener(@NotNull TitleManager titleManager, @NotNull FishingManager fishingManager) {
        this.titleManager = titleManager;
        this.fishingManager = fishingManager;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(@NotNull AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();

        String titleDisplay = buildTitleDisplay(playerId);
        String levelDisplay = buildLevelDisplay(playerId);

        // %1$s = 玩家显示名, %2$s = 消息内容
        // 格式: [称号] [钓鱼等级] 玩家名 : 消息（无称号时省略称号部分，不留多余空格）
        String prefix = titleDisplay.isEmpty() ? levelDisplay : titleDisplay + " " + levelDisplay;
        event.setFormat(prefix + " §f%1$s §8: §f%2$s");
    }

    private String buildTitleDisplay(@NotNull UUID playerId) {
        String currentTitleId = titleManager.getCurrentTitle(playerId);
        if (currentTitleId == null) {
            return "";
        }
        TitleManager.TitleConfig title = titleManager.getTitleConfig(currentTitleId);
        if (title == null) {
            return "";
        }
        return title.getColor() + "[" + title.getName() + "]";
    }

    private String buildLevelDisplay(@NotNull UUID playerId) {
        int level = fishingManager.getPlayerLevel(playerId);
        String color = getLevelColor(level);
        return color + "[" + level + "级]";
    }

    /**
     * 根据钓鱼等级返回对应颜色
     * 1级-灰 2级-绿 3级-青 4级-粉 5级-金 6+-红
     */
    private String getLevelColor(int level) {
        return switch (level) {
            case 1 -> "§7";
            case 2 -> "§a";
            case 3 -> "§b";
            case 4 -> "§d";
            case 5 -> "§6";
            default -> level > 5 ? "§c" : "§7";
        };
    }
}
