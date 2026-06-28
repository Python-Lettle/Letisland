package cn.lettle.letisland.log;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 日志查询命令
 * /letisland log <player> [count]   - 查询某玩家最近日志
 * /letisland log recent [count]     - 查询全局最近日志
 * /letisland log type <type> [count]- 按类型查询
 * /letisland log help                - 帮助
 *
 * 权限：letisland.admin
 */
public class LogCommand implements CommandExecutor, TabCompleter {

    private final LogManager logManager;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    public LogCommand(@NotNull LogManager logManager) {
        this.logManager = logManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("letisland.admin")) {
            sender.sendMessage("§c你没有权限查询日志");
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sendHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "recent" -> handleRecent(sender, args);
            case "player" -> handlePlayer(sender, args);
            case "type" -> handleType(sender, args);
            default -> sendHelp(sender);
        }
        return true;
    }

    /**
     * 查询全局最近日志
     */
    private void handleRecent(CommandSender sender, String[] args) {
        int count = parseCount(args, 1, 10);
        List<LogManager.LogEntry> logs = logManager.getRecentLogs(count);
        sendLogs(sender, "最近日志", logs);
    }

    /**
     * 查询某玩家日志
     */
    private void handlePlayer(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§c用法: /letisland log player <玩家名> [数量]");
            return;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        UUID playerId = target.getUniqueId();
        int count = parseCount(args, 2, 10);
        List<LogManager.LogEntry> logs = logManager.getPlayerLogs(playerId, count);
        sendLogs(sender, "玩家 " + args[1] + " 的日志", logs);
    }

    /**
     * 按类型查询日志
     */
    private void handleType(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§c用法: /letisland log type <类型> [数量]");
            sender.sendMessage("§7可用类型: §eLOGIN §7/ §eLOGOUT §7/ §eCODEX_FISH §7/ §eCODEX_TITLE §7/ §eSENSITIVE §7/ §eSECURITY_BLOCK");
            return;
        }
        try {
            LogType type = LogType.valueOf(args[1].toUpperCase());
            int count = parseCount(args, 2, 10);
            List<LogManager.LogEntry> logs = logManager.getLogsByType(type, count);
            sendLogs(sender, type.getDisplayName() + " 类型日志", logs);
        } catch (IllegalArgumentException e) {
            sender.sendMessage("§c无效类型，可用: LOGIN / LOGOUT / CODEX_FISH / CODEX_TITLE / SENSITIVE / SECURITY_BLOCK");
        }
    }

    /**
     * 格式化输出日志列表
     */
    private void sendLogs(CommandSender sender, String title, List<LogManager.LogEntry> logs) {
        if (logs.isEmpty()) {
            sender.sendMessage("§e" + title + ": §7无记录");
            return;
        }
        sender.sendMessage("§6===== " + title + " =====");
        for (LogManager.LogEntry log : logs) {
            String time = dateFormat.format(log.createdAt());
            String typeColor = getTypeColor(log.type());
            String line = "§7[" + time + "] " + typeColor + log.type().getDisplayName() +
                    " §7| §f" + log.playerName();
            if (log.ip() != null) {
                line += " §7IP:§8" + log.ip();
            }
            if (log.detail() != null) {
                line += " §7| §b" + log.detail();
            }
            sender.sendMessage(line);
        }
        sender.sendMessage("§7共 §e" + logs.size() + " §7条记录");
    }

    private String getTypeColor(LogType type) {
        return switch (type) {
            case LOGIN -> "§a";
            case LOGOUT -> "§c";
            case CODEX_FISH -> "§b";
            case CODEX_TITLE -> "§d";
            case SENSITIVE -> "§6";
            case SECURITY_BLOCK -> "§4";
        };
    }

    private int parseCount(String[] args, int index, int defaultVal) {
        if (args.length > index) {
            try {
                return Math.max(1, Math.min(50, Integer.parseInt(args[index])));
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultVal;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage("§6===== Letisland 日志系统 =====");
        sender.sendMessage("§e/letisland log recent [数量] §7- 查看最近日志（默认10条，最多50条）");
        sender.sendMessage("§e/letisland log player <玩家名> [数量] §7- 查看某玩家的日志");
        sender.sendMessage("§e/letisland log type <类型> [数量] §7- 按类型查询日志");
        sender.sendMessage("§7类型: §aLOGIN §7/ §cLOGOUT §7/ §bCODEX_FISH §7/ §dCODEX_TITLE §7/ §6SENSITIVE §7/ §cSECURITY_BLOCK");
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("letisland.admin")) {
            return new ArrayList<>();
        }
        if (args.length == 1) {
            List<String> subs = List.of("recent", "player", "type", "help");
            return subs.stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("player")) {
            return Bukkit.getOnlinePlayers().stream()
                    .map(p -> p.getName())
                    .filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase()))
                    .collect(Collectors.toList());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("type")) {
            return java.util.Arrays.stream(LogType.values())
                    .map(Enum::name)
                    .filter(s -> s.startsWith(args[1].toUpperCase()))
                    .collect(Collectors.toList());
        }
        return new ArrayList<>();
    }
}
