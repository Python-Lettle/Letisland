package cn.lettle.letisland.economy;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
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
 * 经济系统命令处理器
 * 支持的命令:
 * /economy balance [player]          - 查询余额
 * /economy pay <player> <amount>     - 向玩家转账
 * /economy give <player> <amount>    - 给予玩家金额（管理员）
 * /economy take <player> <amount>    - 扣除玩家金额（管理员）
 * /economy set <player> <amount>     - 设置玩家余额（管理员）
 * /economy reload                    - 重载数据
 */
public class EconomyCommand implements CommandExecutor, TabCompleter {

    private final EconomyManager economyManager;

    public EconomyCommand(@NotNull EconomyManager economyManager) {
        this.economyManager = economyManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String subCommand = args[0].toLowerCase();

        switch (subCommand) {
            case "balance" -> handleBalance(sender, args);
            case "pay" -> handlePay(sender, args);
            case "give" -> handleGive(sender, args);
            case "take" -> handleTake(sender, args);
            case "set" -> handleSet(sender, args);
            case "reload" -> handleReload(sender);
            default -> sendHelp(sender);
        }
        return true;
    }

    /**
     * 查询余额
     */
    private void handleBalance(CommandSender sender, String[] args) {
        if (args.length == 1) {
            // 查询自己的余额
            if (!(sender instanceof Player player)) {
                sender.sendMessage("§c控制台请指定玩家: /economy balance <player>");
                return;
            }
            double balance = economyManager.getBalance(player);
            sender.sendMessage("§a你的余额: §e" + economyManager.format(balance));
        } else {
            // 查询他人余额（需要权限）
            if (!sender.hasPermission("letisland.economy.balance.others")) {
                sender.sendMessage("§c你没有权限查询其他玩家的余额");
                return;
            }
            OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
            double balance = economyManager.getBalance(target);
            sender.sendMessage("§a" + target.getName() + " 的余额: §e" + economyManager.format(balance));
        }
    }

    /**
     * 向玩家转账
     */
    private void handlePay(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c只有玩家可以使用此命令");
            return;
        }
        if (args.length < 3) {
            sender.sendMessage("§c用法: /economy pay <player> <amount>");
            return;
        }

        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        double amount;
        try {
            amount = Double.parseDouble(args[2]);
        } catch (NumberFormatException e) {
            sender.sendMessage("§c金额必须是数字");
            return;
        }

        if (amount <= 0) {
            sender.sendMessage("§c转账金额必须大于 0");
            return;
        }

        if (player.getUniqueId().equals(target.getUniqueId())) {
            sender.sendMessage("§c不能向自己转账");
            return;
        }

        if (!economyManager.has(player, amount)) {
            sender.sendMessage("§c你的余额不足");
            return;
        }

        if (economyManager.transfer(player, target, amount)) {
            sender.sendMessage("§a已向 §e" + target.getName() + " §a转账 §e" + economyManager.format(amount));
            if (target.isOnline()) {
                target.getPlayer().sendMessage("§a收到来自 §e" + player.getName() +
                        " §a的转账 §e" + economyManager.format(amount));
            }
        } else {
            sender.sendMessage("§c转账失败");
        }
    }

    /**
     * 给予玩家金额（管理员）
     */
    private void handleGive(CommandSender sender, String[] args) {
        if (!sender.hasPermission("letisland.economy.admin")) {
            sender.sendMessage("§c你没有权限执行此操作");
            return;
        }
        if (args.length < 3) {
            sender.sendMessage("§c用法: /economy give <player> <amount>");
            return;
        }

        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        double amount;
        try {
            amount = Double.parseDouble(args[2]);
        } catch (NumberFormatException e) {
            sender.sendMessage("§c金额必须是数字");
            return;
        }

        if (amount <= 0) {
            sender.sendMessage("§c金额必须大于 0");
            return;
        }

        if (economyManager.deposit(target, amount)) {
            sender.sendMessage("§a已给予 §e" + target.getName() + " §a金额 §e" + economyManager.format(amount));
        } else {
            sender.sendMessage("§c操作失败");
        }
    }

    /**
     * 扣除玩家金额（管理员）
     */
    private void handleTake(CommandSender sender, String[] args) {
        if (!sender.hasPermission("letisland.economy.admin")) {
            sender.sendMessage("§c你没有权限执行此操作");
            return;
        }
        if (args.length < 3) {
            sender.sendMessage("§c用法: /economy take <player> <amount>");
            return;
        }

        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        double amount;
        try {
            amount = Double.parseDouble(args[2]);
        } catch (NumberFormatException e) {
            sender.sendMessage("§c金额必须是数字");
            return;
        }

        if (amount <= 0) {
            sender.sendMessage("§c金额必须大于 0");
            return;
        }

        if (economyManager.withdraw(target, amount)) {
            sender.sendMessage("§a已扣除 §e" + target.getName() + " §a金额 §e" + economyManager.format(amount));
        } else {
            sender.sendMessage("§c操作失败（余额不足）");
        }
    }

    /**
     * 设置玩家余额（管理员）
     */
    private void handleSet(CommandSender sender, String[] args) {
        if (!sender.hasPermission("letisland.economy.admin")) {
            sender.sendMessage("§c你没有权限执行此操作");
            return;
        }
        if (args.length < 3) {
            sender.sendMessage("§c用法: /economy set <player> <amount>");
            return;
        }

        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        double amount;
        try {
            amount = Double.parseDouble(args[2]);
        } catch (NumberFormatException e) {
            sender.sendMessage("§c金额必须是数字");
            return;
        }

        if (amount < 0) {
            sender.sendMessage("§c金额不能为负数");
            return;
        }

        economyManager.setBalance(target, amount);
        sender.sendMessage("§a已将 §e" + target.getName() + " §a的余额设置为 §e" + economyManager.format(amount));
    }

    /**
     * 重载数据
     */
    private void handleReload(CommandSender sender) {
        if (!sender.hasPermission("letisland.economy.admin")) {
            sender.sendMessage("§c你没有权限执行此操作");
            return;
        }
        economyManager.reload();
        sender.sendMessage("§a经济数据已重新加载");
    }

    /**
     * 发送帮助信息
     */
    private void sendHelp(CommandSender sender) {
        sender.sendMessage("§6===== Letisland 经济系统 =====");
        sender.sendMessage("§e/economy balance [player] §7- 查询余额");
        sender.sendMessage("§e/economy pay <player> <amount> §7- 向玩家转账");
        if (sender.hasPermission("letisland.economy.admin")) {
            sender.sendMessage("§e/economy give <player> <amount> §7- 给予玩家金额");
            sender.sendMessage("§e/economy take <player> <amount> §7- 扣除玩家金额");
            sender.sendMessage("§e/economy set <player> <amount> §7- 设置玩家余额");
            sender.sendMessage("§e/economy reload §7- 重载数据");
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String label, @NotNull String[] args) {
        if (args.length == 1) {
            List<String> subs = new ArrayList<>(Arrays.asList("balance", "pay"));
            if (sender.hasPermission("letisland.economy.admin")) {
                subs.addAll(Arrays.asList("give", "take", "set", "reload"));
            }
            return subs.stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }

        if (args.length == 2) {
            String subCommand = args[0].toLowerCase();
            if (subCommand.equals("pay") || subCommand.equals("give") ||
                subCommand.equals("take") || subCommand.equals("set") ||
                (subCommand.equals("balance") && sender.hasPermission("letisland.economy.balance.others"))) {
                return Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName)
                        .filter(name -> name.toLowerCase().startsWith(args[1].toLowerCase()))
                        .collect(Collectors.toList());
            }
        }

        return new ArrayList<>();
    }
}
