package cn.lettle.letisland.security;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 安全防护系统命令
 *
 * /security status              - 查看当前防护状态与统计
 * /security list                - 列出当前被封禁的 IP
 * /security block <ip> [分钟]   - 手动封禁 IP（不填分钟则永久）
 * /security unblock <ip>        - 解除 IP 封禁
 * /security reload              - 热重载配置
 * /security enable|disable      - 开关防护系统
 *
 * 权限：letisland.security.admin（默认仅 OP）
 */
public class SecurityCommand implements CommandExecutor, TabCompleter {

    private static final SimpleDateFormat DATE_FMT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    private final SecurityManager securityManager;

    public SecurityCommand(@NotNull SecurityManager securityManager) {
        this.securityManager = securityManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("letisland.security.admin")) {
            sender.sendMessage("§c你没有权限执行此操作");
            return true;
        }

        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "status" -> handleStatus(sender);
            case "list" -> handleList(sender);
            case "block" -> handleBlock(sender, args);
            case "unblock" -> handleUnblock(sender, args);
            case "reload" -> handleReload(sender);
            case "enable" -> handleToggle(sender, true);
            case "disable" -> handleToggle(sender, false);
            default -> sendHelp(sender);
        }
        return true;
    }

    private void handleStatus(@NotNull CommandSender sender) {
        sender.sendMessage("§6===== 安全防护系统 =====");
        sender.sendMessage("§7状态: " + (securityManager.isEnabled() ? "§a启用" : "§c关闭"));
        sender.sendMessage("§7当前被封禁 IP 数: §e" + securityManager.getActiveBlockCount());
        sender.sendMessage("§7正在追踪的 IP 数: §e" + securityManager.getTrackedIPCount());
        sender.sendMessage("§7查看拦截日志: §e/letisland log type SECURITY_BLOCK");
    }

    private void handleList(@NotNull CommandSender sender) {
        List<SecurityManager.BlockEntry> blocks = securityManager.getBlockedIPs();
        if (blocks.isEmpty()) {
            sender.sendMessage("§e当前没有被封禁的 IP");
            return;
        }
        sender.sendMessage("§6===== 封禁列表（" + blocks.size() + " 条）=====");
        for (SecurityManager.BlockEntry entry : blocks) {
            String untilStr = entry.untilMillis() == 0
                    ? "§4永久封禁"
                    : "§e" + DATE_FMT.format(new java.util.Date(entry.untilMillis()));
            sender.sendMessage("§c" + entry.ip() + " §7| " + untilStr);
            sender.sendMessage("   §7原因: §f" + entry.reason());
        }
    }

    private void handleBlock(@NotNull CommandSender sender, @NotNull String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§c用法: /security block <IP> [分钟(0=永久)]");
            return;
        }
        String ip = args[1];
        int minutes = -1; // -1 表示未指定，默认永久
        if (args.length >= 3) {
            try {
                minutes = Integer.parseInt(args[2]);
                if (minutes < 0) {
                    sender.sendMessage("§c分钟数不能为负数");
                    return;
                }
            } catch (NumberFormatException e) {
                sender.sendMessage("§c分钟数必须是数字");
                return;
            }
        }
        String reason = args.length >= 4
                ? String.join(" ", Arrays.copyOfRange(args, 3, args.length))
                : "管理员手动封禁";
        boolean permanent = minutes < 0 || minutes == 0;
        securityManager.manualBlock(ip, reason, permanent ? 0 : minutes);
        sender.sendMessage("§a已封禁 IP §e" + ip + "§a，时长: " +
                (permanent ? "§4永久" : "§e" + minutes + " 分钟"));
        Bukkit.broadcastMessage("§6[安全] §a管理员已封禁 IP §e" + ip +
                (permanent ? " §4(永久)" : " §7(" + minutes + " 分钟)"));
    }

    private void handleUnblock(@NotNull CommandSender sender, @NotNull String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§c用法: /security unblock <IP>");
            return;
        }
        String ip = args[1];
        if (securityManager.unblockIP(ip)) {
            sender.sendMessage("§a已解除 IP §e" + ip + " §a的封禁");
        } else {
            sender.sendMessage("§c该 IP 不在封禁列表中");
        }
    }

    private void handleReload(@NotNull CommandSender sender) {
        securityManager.loadConfig();
        sender.sendMessage("§a安全防护配置已热重载");
    }

    private void handleToggle(@NotNull CommandSender sender, boolean enable) {
        if (securityManager.isEnabled() == enable) {
            sender.sendMessage("§e安全防护系统已经是" + (enable ? "启用" : "关闭") + "状态");
            return;
        }
        securityManager.setEnabled(enable);
        sender.sendMessage("§a安全防护系统已" + (enable ? "启用" : "关闭"));
        Bukkit.broadcastMessage("§6[安全] §a安全防护系统已被管理员" +
                (enable ? "§a启用" : "§c关闭"));
    }

    private void sendHelp(@NotNull CommandSender sender) {
        sender.sendMessage("§6===== Letisland 安全防护系统 =====");
        sender.sendMessage("§e/security status §7- 查看防护状态与统计");
        sender.sendMessage("§e/security list §7- 列出当前被封禁的 IP");
        sender.sendMessage("§e/security block <IP> [分钟] [原因] §7- 封禁 IP（不填分钟=永久）");
        sender.sendMessage("§e/security unblock <IP> §7- 解除 IP 封禁");
        sender.sendMessage("§e/security reload §7- 热重载配置");
        sender.sendMessage("§e/security enable §7- 启用防护系统");
        sender.sendMessage("§e/security disable §7- 关闭防护系统");
        sender.sendMessage("§7查看拦截日志: §e/letisland log type SECURITY_BLOCK");
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("letisland.security.admin")) {
            return new ArrayList<>();
        }
        if (args.length == 1) {
            List<String> subs = Arrays.asList(
                    "status", "list", "block", "unblock", "reload", "enable", "disable");
            return subs.stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("unblock")) {
            return securityManager.getBlockedIPs().stream()
                    .map(SecurityManager.BlockEntry::ip)
                    .filter(ip -> ip.startsWith(args[1]))
                    .collect(Collectors.toList());
        }
        return new ArrayList<>();
    }
}
