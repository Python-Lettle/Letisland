package cn.lettle.letisland.log;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;

import java.util.Set;
import java.util.UUID;

/**
 * 日志事件监听器
 * 自动记录玩家登录/登出，以及敏感管理员命令
 */
public class LogListener implements Listener {

    private final LogManager logManager;

    /** 敏感子命令关键词（出现在命令中即视为敏感操作） */
    private static final Set<String> SENSITIVE_KEYWORDS = Set.of(
            "give", "take", "set", "reload", "enable", "disable", "reset"
    );

    public LogListener(@NotNull LogManager logManager) {
        this.logManager = logManager;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(@NotNull PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        String ip = player.getAddress() != null
                ? player.getAddress().getAddress().getHostAddress()
                : null;
        logManager.logLogin(playerId, player.getName(), ip);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(@NotNull PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        String ip = player.getAddress() != null
                ? player.getAddress().getAddress().getHostAddress()
                : null;
        logManager.logLogout(playerId, player.getName(), ip);
    }

    /**
     * 监听玩家执行的敏感命令
     * 只记录包含管理员关键词的命令，避免日志过于冗余
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(@NotNull PlayerCommandPreprocessEvent event) {
        String message = event.getMessage().toLowerCase();
        if (!isSensitiveCommand(message)) {
            return;
        }
        Player player = event.getPlayer();
        logManager.logSensitive(
                player.getUniqueId(),
                player.getName(),
                event.getMessage()
        );
    }

    /**
     * 监听控制台执行的敏感命令
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onServerCommand(@NotNull org.bukkit.event.server.ServerCommandEvent event) {
        String message = event.getCommand().toLowerCase();
        if (!isSensitiveCommand(message)) {
            return;
        }
        // 控制台操作以 "CONSOLE" 记录
        logManager.logSensitive(
                new UUID(0, 0),
                "CONSOLE",
                "/" + event.getCommand()
        );
    }

    /**
     * 判断命令是否包含敏感关键词
     * 检查命令是否包含 give/take/set/reload/enable/disable/reset 等子命令
     */
    private boolean isSensitiveCommand(@NotNull String command) {
        // 只关注插件自己的命令前缀，避免误捕获其他插件的命令
        if (!command.startsWith("/economy") && !command.startsWith("/eco") &&
            !command.startsWith("/money") &&
            !command.startsWith("/fishing") && !command.startsWith("/fish") &&
            !command.startsWith("/shop") && !command.startsWith("/store") &&
            !command.startsWith("/generator") && !command.startsWith("/gen") &&
            !command.startsWith("/title") && !command.startsWith("/titles")) {
            return false;
        }
        for (String keyword : SENSITIVE_KEYWORDS) {
            if (command.contains(" " + keyword)) {
                return true;
            }
        }
        return false;
    }
}
