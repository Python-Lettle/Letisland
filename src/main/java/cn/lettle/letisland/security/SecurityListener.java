package cn.lettle.letisland.security;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * 安全防护事件监听器
 *
 * 在玩家登录流程的最早期（AsyncPlayerPreLoginEvent）执行拦截，
 * 命中黑名单/扫描器/可疑用户名/频率超限的连接会被立即拒绝，
 * 并通过 SecurityManager 写入 SECURITY_BLOCK 日志。
 *
 * 使用 EventPriority.LOWEST 确保在其它插件处理前完成拦截，
 * 减少扫描机器人对后续鉴权逻辑的资源消耗。
 */
public class SecurityListener implements Listener {

    private final SecurityManager securityManager;

    public SecurityListener(@NotNull SecurityManager securityManager) {
        this.securityManager = securityManager;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPreLogin(@NotNull AsyncPlayerPreLoginEvent event) {
        // 仅处理允许状态；若已被其它插件/白名单拒绝则不重复拦截
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }

        String ip = event.getAddress().getHostAddress();
        String name = event.getName();
        UUID uuid = event.getUniqueId(); // 离线模式可能为 null

        SecurityManager.CheckResult result = securityManager.checkLogin(ip, name, uuid);
        if (result != null) {
            // 拦截连接，使用 KICK_BANNED 让客户端显示封禁样式提示
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, result.message());
        }
    }
}
