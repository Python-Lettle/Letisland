package cn.lettle.letisland.shop;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 商店命令处理器
 * 支持的命令:
 * /shop open        - 打开商店
 * /shop refresh     - 手动刷新商店库存（管理员）
 * /shop reload      - 热重载商店配置（管理员）
 * /shop enable      - 启用商店（管理员）
 * /shop disable     - 关闭商店（管理员）
 */
public class ShopCommand implements CommandExecutor, TabCompleter {

    private final ShopManager shopManager;
    private final ShopListener shopListener;

    public ShopCommand(@NotNull ShopManager shopManager, @NotNull ShopListener shopListener) {
        this.shopManager = shopManager;
        this.shopListener = shopListener;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            // 无参数默认打开商店（仅玩家）
            if (!(sender instanceof Player player)) {
                sender.sendMessage("§c控制台请使用: /shop <open|refresh|reload|enable|disable>");
                return true;
            }
            handleOpen(sender);
            return true;
        }

        String subCommand = args[0].toLowerCase();

        switch (subCommand) {
            case "open" -> handleOpen(sender);
            case "refresh" -> handleRefresh(sender);
            case "reload" -> handleReload(sender);
            case "enable" -> handleToggle(sender, true);
            case "disable" -> handleToggle(sender, false);
            default -> sendHelp(sender);
        }
        return true;
    }

    /**
     * 打开商店
     */
    private void handleOpen(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c只有玩家可以打开商店");
            return;
        }
        if (!shopManager.isEnabled()) {
            sender.sendMessage("§c商店系统已关闭，请联系管理员");
            return;
        }
        shopListener.openShop(player);
    }

    /**
     * 手动刷新商店
     */
    private void handleRefresh(CommandSender sender) {
        if (!sender.hasPermission("letisland.shop.admin")) {
            sender.sendMessage("§c你没有权限执行此操作");
            return;
        }
        shopManager.refresh();
        sender.sendMessage("§a商店库存已手动刷新");
    }

    /**
     * 热重载配置
     */
    private void handleReload(CommandSender sender) {
        if (!sender.hasPermission("letisland.shop.admin")) {
            sender.sendMessage("§c你没有权限执行此操作");
            return;
        }
        shopManager.reload();
        sender.sendMessage("§a商店配置已热重载并刷新库存");
    }

    /**
     * 启用/关闭商店
     */
    private void handleToggle(CommandSender sender, boolean enable) {
        if (!sender.hasPermission("letisland.shop.admin")) {
            sender.sendMessage("§c你没有权限执行此操作");
            return;
        }
        if (shopManager.isEnabled() == enable) {
            sender.sendMessage("§e商店系统已经是" + (enable ? "启用" : "关闭") + "状态");
            return;
        }
        shopManager.setEnabled(enable);
        sender.sendMessage("§a商店系统已" + (enable ? "启用" : "关闭"));
        org.bukkit.Bukkit.broadcastMessage("§6[商店] §a商店系统已被管理员" +
                (enable ? "启用" : "关闭"));
    }

    /**
     * 发送帮助信息
     */
    private void sendHelp(CommandSender sender) {
        sender.sendMessage("§6===== Letisland 商店系统 =====");
        sender.sendMessage("§e/shop open §7- 打开商店");
        if (sender.hasPermission("letisland.shop.admin")) {
            sender.sendMessage("§e/shop refresh §7- 手动刷新商店库存");
            sender.sendMessage("§e/shop reload §7- 热重载商店配置");
            sender.sendMessage("§e/shop enable §7- 启用商店系统");
            sender.sendMessage("§e/shop disable §7- 关闭商店系统");
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String label, @NotNull String[] args) {
        if (args.length == 1) {
            List<String> subs = new ArrayList<>();
            subs.add("open");
            if (sender.hasPermission("letisland.shop.admin")) {
                subs.add("refresh");
                subs.add("reload");
                subs.add("enable");
                subs.add("disable");
            }
            return subs.stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }
        return new ArrayList<>();
    }
}
