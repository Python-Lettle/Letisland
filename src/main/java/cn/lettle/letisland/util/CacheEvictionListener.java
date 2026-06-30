package cn.lettle.letisland.util;

import cn.lettle.letisland.economy.EconomyManager;
import cn.lettle.letisland.fishing.FishingListener;
import cn.lettle.letisland.fishing.FishingManager;
import cn.lettle.letisland.home.HomeCommand;
import cn.lettle.letisland.home.HomeManager;
import cn.lettle.letisland.home.HomelandScoreboardManager;
import cn.lettle.letisland.title.TitleManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * 中央缓存驱逐监听器
 * 在玩家退出时统一清理各管理器中该玩家相关的内存缓存，避免长期运行内存泄漏
 */
public class CacheEvictionListener implements Listener {

    private final HomeManager homeManager;
    private final EconomyManager economyManager;
    private final FishingManager fishingManager;
    private final TitleManager titleManager;
    private final FishingListener fishingListener;
    private final HomeCommand homeCommand;
    private final HomelandScoreboardManager scoreboardManager;

    public CacheEvictionListener(@NotNull HomeManager homeManager,
                                 @NotNull EconomyManager economyManager,
                                 @NotNull FishingManager fishingManager,
                                 @NotNull TitleManager titleManager,
                                 @NotNull FishingListener fishingListener,
                                 @NotNull HomeCommand homeCommand,
                                 @NotNull HomelandScoreboardManager scoreboardManager) {
        this.homeManager = homeManager;
        this.economyManager = economyManager;
        this.fishingManager = fishingManager;
        this.titleManager = titleManager;
        this.fishingListener = fishingListener;
        this.homeCommand = homeCommand;
        this.scoreboardManager = scoreboardManager;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(@NotNull PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        homeManager.evictCache(uuid);
        economyManager.evictCache(uuid);
        fishingManager.evictCache(uuid);
        titleManager.evictCache(uuid);
        fishingListener.evictCooldown(uuid);
        homeCommand.evictInvite(uuid);
        scoreboardManager.onQuit(uuid);
    }
}
