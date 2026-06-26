package cn.lettle.letisland.generator;

import cn.lettle.letisland.economy.EconomyManager;
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
 * 刷石机命令处理器
 * /generator info      - 查看当前等级和下一级信息
 * /generator upgrade   - 升级刷石机（消耗经济系统金钱）
 * /generator set <level> - 管理员设置等级
 * /generator reload    - 热重载配置
 * /generator enable    - 启用刷石机系统（管理员）
 * /generator disable   - 关闭刷石机系统（管理员）
 */
public class GeneratorCommand implements CommandExecutor, TabCompleter {

    private final GeneratorManager generatorManager;
    private final EconomyManager economyManager;

    public GeneratorCommand(@NotNull GeneratorManager generatorManager, @NotNull EconomyManager economyManager) {
        this.generatorManager = generatorManager;
        this.economyManager = economyManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            handleInfo(sender);
            return true;
        }

        String subCommand = args[0].toLowerCase();
        switch (subCommand) {
            case "info" -> handleInfo(sender);
            case "upgrade" -> handleUpgrade(sender);
            case "set" -> handleSet(sender, args);
            case "reload" -> handleReload(sender);
            case "enable" -> handleToggle(sender, true);
            case "disable" -> handleToggle(sender, false);
            default -> sendHelp(sender);
        }
        return true;
    }

    /**
     * 显示当前等级信息
     */
    private void handleInfo(CommandSender sender) {
        GeneratorManager.LevelConfig current = generatorManager.getCurrentLevelConfig();

        sender.sendMessage("§6===== 刷石机信息 =====");
        sender.sendMessage("§7系统状态: " + (generatorManager.isEnabled() ? "§a启用" : "§c关闭"));
        sender.sendMessage("§7当前等级: §e" + current.getLevel() + " §7- §a" + current.getName());
        sender.sendMessage("§7替换概率: §e" + String.format("%.1f%%", current.getReplaceChance() * 100));
        sender.sendMessage("§7矿物种类: §e" + current.getMinerals().size() + " §7种");

        if (generatorManager.isMaxLevel()) {
            sender.sendMessage("§a已达到最高等级！");
        } else {
            GeneratorManager.LevelConfig next = generatorManager.getLevelConfig(current.getLevel() + 1);
            if (next != null) {
                sender.sendMessage("§7----- 下一级 -----");
                sender.sendMessage("§7等级: §e" + next.getLevel() + " §7- §a" + next.getName());
                sender.sendMessage("§7替换概率: §e" + String.format("%.1f%%", next.getReplaceChance() * 100));
                sender.sendMessage("§7矿物种类: §e" + next.getMinerals().size() + " §7种");
                sender.sendMessage("§7升级费用: §e" + economyManager.format(next.getUpgradeCost()));
                if (sender instanceof Player) {
                    sender.sendMessage("§a使用 §e/generator upgrade §a进行升级");
                }
            }
        }
    }

    /**
     * 升级刷石机
     */
    private void handleUpgrade(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c只有玩家可以执行升级操作");
            return;
        }
        if (!generatorManager.isEnabled()) {
            sender.sendMessage("§c刷石机系统已关闭，无法升级");
            return;
        }

        GeneratorManager.UpgradeResult result = generatorManager.upgrade(player);
        player.sendMessage(result.message());

        if (result.success()) {
            // 全服广播
            GeneratorManager.LevelConfig config = generatorManager.getCurrentLevelConfig();
            org.bukkit.Bukkit.broadcastMessage("§6[刷石机] §a全服刷石机已升级到 §e" + config.getName() +
                    " §a！矿物掉落更丰富啦！");
        }
    }

    /**
     * 管理员设置等级
     */
    private void handleSet(CommandSender sender, String[] args) {
        if (!sender.hasPermission("letisland.generator.admin")) {
            sender.sendMessage("§c你没有权限执行此操作");
            return;
        }

        if (args.length < 2) {
            sender.sendMessage("§c用法: /generator set <等级>");
            return;
        }

        try {
            int level = Integer.parseInt(args[1]);
            if (level < 1 || level > generatorManager.getMaxLevel()) {
                sender.sendMessage("§c等级范围: 1 - " + generatorManager.getMaxLevel());
                return;
            }

            generatorManager.setCurrentLevel(level);
            GeneratorManager.LevelConfig config = generatorManager.getCurrentLevelConfig();
            sender.sendMessage("§a刷石机等级已设置为 §e" + config.getName());
            org.bukkit.Bukkit.broadcastMessage("§6[刷石机] §a全服刷石机等级已被管理员设置为 §e" + config.getName());
        } catch (NumberFormatException e) {
            sender.sendMessage("§c请输入有效的数字");
        }
    }

    /**
     * 热重载
     */
    private void handleReload(CommandSender sender) {
        if (!sender.hasPermission("letisland.generator.admin")) {
            sender.sendMessage("§c你没有权限执行此操作");
            return;
        }
        generatorManager.reload();
        sender.sendMessage("§a刷石机配置已热重载");
    }

    /**
     * 启用/关闭刷石机
     */
    private void handleToggle(CommandSender sender, boolean enable) {
        if (!sender.hasPermission("letisland.generator.admin")) {
            sender.sendMessage("§c你没有权限执行此操作");
            return;
        }
        if (generatorManager.isEnabled() == enable) {
            sender.sendMessage("§e刷石机系统已经是" + (enable ? "启用" : "关闭") + "状态");
            return;
        }
        generatorManager.setEnabled(enable);
        sender.sendMessage("§a刷石机系统已" + (enable ? "启用" : "关闭"));
        org.bukkit.Bukkit.broadcastMessage("§6[刷石机] §a刷石机系统已被管理员" +
                (enable ? "启用" : "关闭"));
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage("§6===== 刷石机系统 =====");
        sender.sendMessage("§e/generator info §7- 查看当前等级信息");
        sender.sendMessage("§e/generator upgrade §7- 升级刷石机");
        if (sender.hasPermission("letisland.generator.admin")) {
            sender.sendMessage("§e/generator set <等级> §7- 设置等级");
            sender.sendMessage("§e/generator reload §7- 热重载配置");
            sender.sendMessage("§e/generator enable §7- 启用刷石机系统");
            sender.sendMessage("§e/generator disable §7- 关闭刷石机系统");
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String label, @NotNull String[] args) {
        if (args.length == 1) {
            List<String> subs = new ArrayList<>();
            subs.add("info");
            subs.add("upgrade");
            if (sender.hasPermission("letisland.generator.admin")) {
                subs.add("set");
                subs.add("reload");
                subs.add("enable");
                subs.add("disable");
            }
            return subs.stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }

        // set 命令的等级补全
        if (args.length == 2 && args[0].equalsIgnoreCase("set") &&
                sender.hasPermission("letisland.generator.admin")) {
            List<String> levels = new ArrayList<>();
            for (int i = 1; i <= generatorManager.getMaxLevel(); i++) {
                levels.add(String.valueOf(i));
            }
            return levels.stream()
                    .filter(s -> s.startsWith(args[1]))
                    .collect(Collectors.toList());
        }

        return new ArrayList<>();
    }
}
