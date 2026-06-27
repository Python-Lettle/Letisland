package cn.lettle.letisland.fishing;

import cn.lettle.letisland.economy.EconomyManager;
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
 * 钓鱼命令处理器
 * /fishing         - 查看自己的钓鱼等级信息
 * /fishing shop     - 打开钓鱼等级升级GUI
 * /fishing sell     - 打开鱼出售GUI
 * /fishing upgrade  - 快捷升级
 * /fishing set <player> <level> - 管理员设置玩家等级
 * /fishing reload   - 热重载配置
 * /fishing enable|disable - 开关系统
 */
public class FishingCommand implements CommandExecutor, TabCompleter {

    private final FishingManager fishingManager;
    private final FishingGUI fishingGUI;

    public FishingCommand(@NotNull FishingManager fishingManager, @NotNull EconomyManager economyManager) {
        this.fishingManager = fishingManager;
        this.fishingGUI = new FishingGUI(fishingManager, economyManager);
    }

    /**
     * 获取FishingGUI实例（用于注册事件监听）
     */
    @NotNull
    public FishingGUI getFishingGUI() {
        return fishingGUI;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            handleInfo(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "info" -> handleInfo(sender);
            case "sell" -> handleSell(sender);
            case "upgrade" -> handleUpgrade(sender);
            case "codex" -> handleCodex(sender);
            case "autosell" -> handleAutoSell(sender, args);
            case "set" -> handleSet(sender, args);
            case "reload" -> handleReload(sender);
            case "enable" -> handleToggle(sender, true);
            case "disable" -> handleToggle(sender, false);
            default -> sendHelp(sender);
        }
        return true;
    }

    private void handleInfo(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c只有玩家可以查看钓鱼信息");
            return;
        }
        int level = fishingManager.getPlayerLevel(player.getUniqueId());
        int exp = fishingManager.getPlayerExp(player.getUniqueId());
        FishingManager.LevelConfig config = fishingManager.getLevelConfigs().get(level);
        int expToNext = config != null ? config.getExpToNext() : 0;
        String levelName = config != null ? config.getName() : "未知";

        sender.sendMessage("§6===== 钓鱼科技信息 =====");
        sender.sendMessage("§7系统状态: " + (fishingManager.isEnabled() ? "§a启用" : "§c关闭"));
        sender.sendMessage("§7当前等级: §e" + level + " §7- §a" + levelName);
        if (expToNext > 0) {
            sender.sendMessage("§7经验值: §e" + exp + "§7/§e" + expToNext);
            double percent = Math.min(100.0 * exp / expToNext, 100.0);
            sender.sendMessage("§7进度: §e" + String.format("%.1f%%", percent));
        } else {
            sender.sendMessage("§a已达最高等级！");
        }
        sender.sendMessage("§7可钓鱼类等级: §e" + FishingManager.getTierName(level) + " §7及以下");
    }

    private void handleSell(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c只有玩家可以打开GUI");
            return;
        }
        if (!fishingManager.isEnabled()) {
            sender.sendMessage("§c钓鱼系统已关闭");
            return;
        }
        fishingGUI.openSellGUI(player);
    }

    private void handleUpgrade(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c只有玩家可以打开GUI");
            return;
        }
        if (!fishingManager.isEnabled()) {
            sender.sendMessage("§c钓鱼系统已关闭");
            return;
        }
        fishingGUI.openLevelGUI(player);
    }

    private void handleCodex(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c只有玩家可以打开GUI");
            return;
        }
        if (!fishingManager.isEnabled()) {
            sender.sendMessage("§c钓鱼系统已关闭");
            return;
        }
        fishingGUI.openCodexGUI(player);
    }

    private void handleAutoSell(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c只有玩家可以设置自动出售");
            return;
        }
        if (!fishingManager.isEnabled()) {
            sender.sendMessage("§c钓鱼系统已关闭");
            return;
        }
        if (!fishingManager.isAutoSellEnabled()) {
            sender.sendMessage("§c自动出售功能已被管理员关闭");
            return;
        }

        if (args.length < 2) {
            int current = fishingManager.getAutoSellTier(player.getUniqueId());
            sender.sendMessage("§6===== 自动出售设置 =====");
            sender.sendMessage("§7当前等级: §e" + (current == 0 ? "关闭" : FishingManager.getTierName(current)));
            sender.sendMessage("§7说明: 设置为N时，等级≤N的鱼将自动出售为金币");
            sender.sendMessage("§7可用: §e/fishing autosell 0 §7(关闭) ~ §e/fishing autosell 5");
            return;
        }

        try {
            int tier = Integer.parseInt(args[1]);
            if (tier < 0 || tier > 5) {
                sender.sendMessage("§c等级范围: 0-5（0=关闭，1-5=对应品质等级）");
                return;
            }
            fishingManager.setAutoSellTier(player.getUniqueId(), tier);
            if (tier == 0) {
                player.sendMessage("§a自动出售已关闭");
            } else {
                player.sendMessage("§a自动出售已设置为 §e" + FishingManager.getTierName(tier) +
                        " §a及以下品质的鱼将自动出售");
            }
        } catch (NumberFormatException e) {
            sender.sendMessage("§c请输入有效的数字（0-5）");
        }
    }

    private void handleSet(CommandSender sender, String[] args) {
        if (!sender.hasPermission("letisland.fishing.admin")) {
            sender.sendMessage("§c你没有权限执行此操作");
            return;
        }
        if (args.length < 3) {
            sender.sendMessage("§c用法: /fishing set <玩家> <等级>");
            return;
        }
        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            sender.sendMessage("§c玩家不在线或不存在");
            return;
        }
        try {
            int level = Integer.parseInt(args[2]);
            if (level < 1 || level > fishingManager.getMaxLevel()) {
                sender.sendMessage("§c等级范围: 1 - " + fishingManager.getMaxLevel());
                return;
            }
            fishingManager.setPlayerData(target.getUniqueId(), level, 0);
            sender.sendMessage("§a已将 §e" + target.getName() + " §a的钓鱼等级设置为 §e" + level);
            target.sendMessage("§a你的钓鱼等级已被管理员设置为 §e" + level);
        } catch (NumberFormatException e) {
            sender.sendMessage("§c请输入有效的数字");
        }
    }

    private void handleReload(CommandSender sender) {
        if (!sender.hasPermission("letisland.fishing.admin")) {
            sender.sendMessage("§c你没有权限执行此操作");
            return;
        }
        fishingManager.reload();
        sender.sendMessage("§a钓鱼配置已热重载");
    }

    private void handleToggle(CommandSender sender, boolean enable) {
        if (!sender.hasPermission("letisland.fishing.admin")) {
            sender.sendMessage("§c你没有权限执行此操作");
            return;
        }
        if (fishingManager.isEnabled() == enable) {
            sender.sendMessage("§e钓鱼系统已经是" + (enable ? "启用" : "关闭") + "状态");
            return;
        }
        fishingManager.setEnabled(enable);
        sender.sendMessage("§a钓鱼系统已" + (enable ? "启用" : "关闭"));
        Bukkit.broadcastMessage("§b[钓鱼] §a钓鱼系统已被管理员" + (enable ? "启用" : "关闭"));
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage("§6===== 钓鱼科技系统 =====");
        sender.sendMessage("§e/fishing §7- 查看钓鱼等级信息");
        sender.sendMessage("§e/fishing upgrade §7- 打开钓鱼等级升级GUI");
        sender.sendMessage("§e/fishing sell §7- 打开鱼出售GUI");
        sender.sendMessage("§e/fishing codex §7- 打开鱼类图鉴");
        sender.sendMessage("§e/fishing autosell [0-5] §7- 设置/查看自动出售等级");
        if (sender.hasPermission("letisland.fishing.admin")) {
            sender.sendMessage("§e/fishing set <玩家> <等级> §7- 设置玩家等级");
            sender.sendMessage("§e/fishing reload §7- 热重载配置");
            sender.sendMessage("§e/fishing enable §7- 启用钓鱼系统");
            sender.sendMessage("§e/fishing disable §7- 关闭钓鱼系统");
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String label, @NotNull String[] args) {
        if (args.length == 1) {
            List<String> subs = new ArrayList<>();
            subs.add("info");
            subs.add("upgrade");
            subs.add("sell");
            subs.add("codex");
            subs.add("autosell");
            if (sender.hasPermission("letisland.fishing.admin")) {
                subs.add("set");
                subs.add("reload");
                subs.add("enable");
                subs.add("disable");
            }
            return subs.stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("set") &&
                sender.hasPermission("letisland.fishing.admin")) {
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase()))
                    .collect(Collectors.toList());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("autosell")) {
            List<String> tiers = new ArrayList<>();
            for (int i = 0; i <= 5; i++) {
                tiers.add(String.valueOf(i));
            }
            return tiers.stream()
                    .filter(s -> s.startsWith(args[1]))
                    .collect(Collectors.toList());
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("set") &&
                sender.hasPermission("letisland.fishing.admin")) {
            List<String> levels = new ArrayList<>();
            for (int i = 1; i <= fishingManager.getMaxLevel(); i++) {
                levels.add(String.valueOf(i));
            }
            return levels.stream()
                    .filter(s -> s.startsWith(args[2]))
                    .collect(Collectors.toList());
        }
        return new ArrayList<>();
    }
}
