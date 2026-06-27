package cn.lettle.letisland.title;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 称号命令处理器
 * /title         - 打开称号图鉴GUI
 * /title list    - 列出所有称号
 * /title set <id> - 佩戴已解锁的称号（set none 取消佩戴）
 * /title reload  - 热重载配置
 * /title enable|disable - 开关系统
 */
public class TitleCommand implements CommandExecutor, TabCompleter {

    private final TitleManager titleManager;
    private final TitleGUI titleGUI;

    public TitleCommand(@NotNull TitleManager titleManager) {
        this.titleManager = titleManager;
        this.titleGUI = new TitleGUI(titleManager);
    }

    /**
     * 获取TitleGUI实例（用于注册事件监听）
     */
    @NotNull
    public TitleGUI getTitleGUI() {
        return titleGUI;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            handleCodex(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "codex" -> handleCodex(sender);
            case "list" -> handleList(sender);
            case "set" -> handleSet(sender, args);
            case "reload" -> handleReload(sender);
            case "enable" -> handleToggle(sender, true);
            case "disable" -> handleToggle(sender, false);
            default -> sendHelp(sender);
        }
        return true;
    }

    private void handleCodex(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c只有玩家可以打开GUI");
            return;
        }
        if (!titleManager.isEnabled()) {
            sender.sendMessage("§c称号系统已关闭");
            return;
        }
        titleGUI.openCodexGUI(player);
    }

    private void handleList(CommandSender sender) {
        if (!titleManager.isEnabled()) {
            sender.sendMessage("§c称号系统已关闭");
            return;
        }
        sender.sendMessage("§6===== 称号列表 =====");
        if (sender instanceof Player player) {
            String current = titleManager.getCurrentTitle(player.getUniqueId());
            for (TitleManager.TitleConfig title : titleManager.getTitleConfigs().values()) {
                boolean unlocked = titleManager.isUnlocked(player.getUniqueId(), title.getId());
                String status = unlocked ? "§a[已解锁]" : "§c[未解锁]";
                String wearing = title.getId().equals(current) ? " §e[佩戴中]" : "";
                sender.sendMessage(status + " " + title.getColor() + title.getName() +
                        " §7- " + title.getUnlockMethod() + wearing);
            }
        } else {
            for (TitleManager.TitleConfig title : titleManager.getTitleConfigs().values()) {
                sender.sendMessage("§7- " + title.getColor() + title.getName() +
                        " §7- " + title.getUnlockMethod());
            }
        }
    }

    private void handleSet(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c只有玩家可以佩戴称号");
            return;
        }
        if (!titleManager.isEnabled()) {
            sender.sendMessage("§c称号系统已关闭");
            return;
        }
        if (args.length < 2) {
            sender.sendMessage("§c用法: /title set <称号ID> 或 /title set none");
            return;
        }
        String titleId = args[1];

        // 取消佩戴
        if (titleId.equalsIgnoreCase("none") || titleId.equalsIgnoreCase("null")) {
            titleManager.setCurrentTitle(player.getUniqueId(), null);
            sender.sendMessage("§a已取消佩戴称号");
            return;
        }

        TitleManager.TitleConfig title = titleManager.getTitleConfig(titleId);
        if (title == null) {
            sender.sendMessage("§c称号不存在: " + titleId);
            return;
        }
        if (!titleManager.isUnlocked(player.getUniqueId(), titleId)) {
            sender.sendMessage("§c你还未解锁此称号");
            return;
        }
        titleManager.setCurrentTitle(player.getUniqueId(), titleId);
        sender.sendMessage("§a已佩戴称号: §r" + title.getColor() + title.getName());
    }

    private void handleReload(CommandSender sender) {
        if (!sender.hasPermission("letisland.title.admin")) {
            sender.sendMessage("§c你没有权限执行此操作");
            return;
        }
        titleManager.reload();
        sender.sendMessage("§a称号配置已热重载");
    }

    private void handleToggle(CommandSender sender, boolean enable) {
        if (!sender.hasPermission("letisland.title.admin")) {
            sender.sendMessage("§c你没有权限执行此操作");
            return;
        }
        if (titleManager.isEnabled() == enable) {
            sender.sendMessage("§e称号系统已经是" + (enable ? "启用" : "关闭") + "状态");
            return;
        }
        titleManager.setEnabled(enable);
        sender.sendMessage("§a称号系统已" + (enable ? "启用" : "关闭"));
        Bukkit.broadcastMessage("§b[称号] §a称号系统已被管理员" + (enable ? "启用" : "关闭"));
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage("§6===== 称号系统 =====");
        sender.sendMessage("§e/title §7- 打开称号图鉴");
        sender.sendMessage("§e/title list §7- 查看所有称号");
        sender.sendMessage("§e/title set <ID> §7- 佩戴已解锁的称号");
        sender.sendMessage("§e/title set none §7- 取消佩戴称号");
        if (sender.hasPermission("letisland.title.admin")) {
            sender.sendMessage("§e/title reload §7- 热重载配置");
            sender.sendMessage("§e/title enable §7- 启用称号系统");
            sender.sendMessage("§e/title disable §7- 关闭称号系统");
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String label, @NotNull String[] args) {
        if (args.length == 1) {
            List<String> subs = new ArrayList<>();
            subs.add("codex");
            subs.add("list");
            subs.add("set");
            if (sender.hasPermission("letisland.title.admin")) {
                subs.add("reload");
                subs.add("enable");
                subs.add("disable");
            }
            return subs.stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("set")) {
            List<String> ids = new ArrayList<>(titleManager.getTitleConfigs().keySet());
            ids.add("none");
            return ids.stream()
                    .filter(s -> s.toLowerCase().startsWith(args[1].toLowerCase()))
                    .collect(Collectors.toList());
        }
        return new ArrayList<>();
    }
}
