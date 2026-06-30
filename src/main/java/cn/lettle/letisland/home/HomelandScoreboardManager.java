package cn.lettle.letisland.home;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 家园贡献榜计分板管理器
 * 在玩家屏幕右侧 SIDEBAR 显示家园名 + 成员贡献值排行
 * 玩家可通过主面板按钮开关
 */
public class HomelandScoreboardManager {

    private final HomeManager homeManager;
    private final Map<UUID, Boolean> toggleState = new ConcurrentHashMap<>();

    public HomelandScoreboardManager(@NotNull HomeManager homeManager) {
        this.homeManager = homeManager;
    }

    /**
     * 启动定时刷新任务（每30秒刷新一次所有已开启的计分板）
     */
    public void startRefreshTask(@NotNull JavaPlugin plugin) {
        Bukkit.getScheduler().runTaskTimer(plugin, this::refreshAll, 100L, 600L);
    }

    /**
     * 切换玩家的计分板显示状态
     */
    public void toggle(@NotNull Player player) {
        UUID uuid = player.getUniqueId();
        boolean current = toggleState.getOrDefault(uuid, false);
        if (current) {
            hide(player);
            toggleState.put(uuid, false);
            player.sendMessage("§6[家园] §7贡献榜计分板已 §c关闭");
        } else {
            var opt = homeManager.getHomelandByMember(uuid);
            if (opt.isEmpty()) {
                player.sendMessage("§c你还没有家园，无法显示贡献榜");
                return;
            }
            show(player, opt.get());
            toggleState.put(uuid, true);
            player.sendMessage("§6[家园] §a贡献榜计分板已开启");
        }
    }

    /**
     * 显示家园贡献榜计分板
     */
    public void show(@NotNull Player player, int homelandId) {
        HomeManager.HomelandInfo home = homeManager.getHomeland(homelandId);
        if (home == null) return;

        ScoreboardManager sbManager = Bukkit.getScoreboardManager();
        if (sbManager == null) return;

        Scoreboard sb = sbManager.getNewScoreboard();
        Component title = LegacyComponentSerializer.legacySection().deserialize("§6§l" + home.name());
        Objective obj = sb.registerNewObjective("homeland", "dummy", title);
        obj.setDisplaySlot(DisplaySlot.SIDEBAR);

        List<HomeManager.MemberInfo> members = homeManager.getMembers(homelandId);
        members.sort((a, b) -> Long.compare(b.contribution(), a.contribution()));

        UUID myUuid = player.getUniqueId();
        int count = 0;
        for (HomeManager.MemberInfo m : members) {
            if (count >= 15) break;
            String entry = m.playerUuid().equals(myUuid)
                    ? "§e" + m.playerName()
                    : "§f" + m.playerName();
            int score = (int) Math.min(m.contribution(), Integer.MAX_VALUE);
            obj.getScore(entry).setScore(score);
            count++;
        }

        player.setScoreboard(sb);
    }

    /**
     * 隐藏计分板（恢复为主计分板）
     */
    public void hide(@NotNull Player player) {
        ScoreboardManager sbManager = Bukkit.getScoreboardManager();
        if (sbManager != null) {
            player.setScoreboard(sbManager.getMainScoreboard());
        }
    }

    /**
     * 刷新单个玩家的计分板（仅当已开启时）
     */
    public void refresh(@NotNull Player player) {
        UUID uuid = player.getUniqueId();
        if (!toggleState.getOrDefault(uuid, false)) return;
        var opt = homeManager.getHomelandByMember(uuid);
        if (opt.isEmpty()) {
            hide(player);
            toggleState.put(uuid, false);
            return;
        }
        show(player, opt.get());
    }

    /**
     * 刷新所有在线且已开启的玩家的计分板
     */
    private void refreshAll() {
        for (var entry : toggleState.entrySet()) {
            if (!entry.getValue()) continue;
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) continue;
            refresh(player);
        }
    }

    /**
     * 玩家是否已开启计分板
     */
    public boolean isShown(@NotNull UUID uuid) {
        return toggleState.getOrDefault(uuid, false);
    }

    /**
     * 玩家退出时清理状态
     */
    public void onQuit(@NotNull UUID uuid) {
        toggleState.remove(uuid);
    }
}
