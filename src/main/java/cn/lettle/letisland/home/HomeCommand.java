package cn.lettle.letisland.home;

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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 家园系统命令 (/homeland，别名 /hd)
 */
public class HomeCommand implements CommandExecutor, TabCompleter {

    private static final long INVITE_EXPIRE_MILLIS = TimeUnit.MINUTES.toMillis(5);

    private final HomeManager homeManager;
    private final HomeListener homeListener;

    /** 待接受的邀请：被邀请玩家UUID → 邀请信息 */
    private final Map<UUID, Invite> pendingInvites = new ConcurrentHashMap<>();

    public HomeCommand(@NotNull HomeManager homeManager, @NotNull HomeListener homeListener) {
        this.homeManager = homeManager;
        this.homeListener = homeListener;
    }

    /** 玩家退出时清理其待处理邀请 */
    public void evictInvite(@NotNull UUID uuid) {
        pendingInvites.remove(uuid);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c此命令只能由玩家执行");
            return true;
        }
        if (!homeManager.isEnabled()) {
            player.sendMessage("§c家园系统未启用");
            return true;
        }
        if (args.length == 0) {
            homeListener.openMain(player);
            return true;
        }
        String sub = args[0].toLowerCase();
        switch (sub) {
            case "create" -> handleCreate(player, args);
            case "invite" -> handleInvite(player, args);
            case "accept" -> handleAccept(player);
            case "decline" -> handleDecline(player);
            case "leave" -> handleLeave(player);
            case "info" -> handleInfo(player);
            case "disband" -> handleDisband(player);
            case "reload" -> handleReload(player);
            case "admin" -> handleAdmin(player, args);
            case "help" -> sendHelp(player);
            default -> {
                player.sendMessage("§c未知子命令，使用 §e/hd help §c查看帮助");
                sendHelp(player);
            }
        }
        return true;
    }

    // ==================== 子命令处理 ====================

    private void handleCreate(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage("§c用法: §e/hd create <家园名>");
            return;
        }
        String name = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        HomeManager.CreateResult result = homeManager.createHomeland(
                player.getUniqueId(), player.getName(), name);
        player.sendMessage(result.message());
        if (result.success()) {
            player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
            Bukkit.broadcastMessage("§6[家园] §e" + player.getName() + " §7创建了家园 §6" + name);
        }
    }

    private void handleInvite(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage("§c用法: §e/hd invite <玩家名>");
            return;
        }
        var opt = homeManager.getHomelandByMember(player.getUniqueId());
        if (opt.isEmpty()) {
            player.sendMessage("§c你还没有家园");
            return;
        }
        int homelandId = opt.get();
        HomeManager.HomelandInfo home = homeManager.getHomeland(homelandId);
        if (home == null) {
            player.sendMessage("§c家园数据异常");
            return;
        }
        // 只有拥有者能邀请
        if (!home.ownerUuid().equals(player.getUniqueId().toString())) {
            player.sendMessage("§c只有家园拥有者才能邀请成员");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null || !target.isOnline()) {
            player.sendMessage("§c该玩家不在线");
            return;
        }
        if (target.getUniqueId().equals(player.getUniqueId())) {
            player.sendMessage("§c不能邀请自己");
            return;
        }
        if (homeManager.getHomelandByMember(target.getUniqueId()).isPresent()) {
            player.sendMessage("§c该玩家已属于一个家园");
            return;
        }
        pendingInvites.put(target.getUniqueId(),
                new Invite(player.getUniqueId(), player.getName(), homelandId, home.name(),
                        System.currentTimeMillis() + INVITE_EXPIRE_MILLIS));
        player.sendMessage("§a已向 §e" + target.getName() + " §a发送家园邀请（5分钟内有效）");
        target.sendMessage("§6[家园] §e" + player.getName() + " §7邀请你加入家园 §6" + home.name());
        target.sendMessage("§7输入 §a/hd accept §7接受 或 §c/hd decline §7拒绝");
    }

    private void handleAccept(Player player) {
        Invite invite = pendingInvites.remove(player.getUniqueId());
        if (invite == null) {
            player.sendMessage("§c你没有待处理的家园邀请");
            return;
        }
        if (System.currentTimeMillis() > invite.expiryMillis()) {
            player.sendMessage("§c邀请已过期");
            return;
        }
        if (homeManager.getHomelandByMember(player.getUniqueId()).isPresent()) {
            player.sendMessage("§c你已属于一个家园，请先 §e/hd leave");
            return;
        }
        boolean ok = homeManager.addMember(invite.homelandId(), player.getUniqueId(), player.getName());
        if (ok) {
            player.sendMessage("§a已加入家园 §6" + invite.homelandName());
            Player inviter = Bukkit.getPlayer(invite.inviterUuid());
            if (inviter != null && inviter.isOnline()) {
                inviter.sendMessage("§a" + player.getName() + " 已加入你的家园");
            }
        } else {
            player.sendMessage("§c加入失败（数据库错误）");
        }
    }

    private void handleDecline(Player player) {
        Invite invite = pendingInvites.remove(player.getUniqueId());
        if (invite == null) {
            player.sendMessage("§c你没有待处理的家园邀请");
            return;
        }
        player.sendMessage("§a已拒绝邀请");
        Player inviter = Bukkit.getPlayer(invite.inviterUuid());
        if (inviter != null && inviter.isOnline()) {
            inviter.sendMessage("§7" + player.getName() + " 拒绝了你的家园邀请");
        }
    }

    private void handleLeave(Player player) {
        HomeManager.LeaveResult result = homeManager.removeMember(player.getUniqueId());
        player.sendMessage(result.message());
    }

    private void handleInfo(Player player) {
        var opt = homeManager.getHomelandByMember(player.getUniqueId());
        if (opt.isEmpty()) {
            player.sendMessage("§c你还没有家园，使用 §e/hd create <名字> §c创建");
            return;
        }
        int homelandId = opt.get();
        HomeManager.HomelandInfo home = homeManager.getHomeland(homelandId);
        if (home == null) {
            player.sendMessage("§c家园数据异常");
            return;
        }
        long myContribution = homeManager.getContribution(player.getUniqueId());
        int memberCount = homeManager.getMemberCount(homelandId);
        player.sendMessage("§6===== 家园信息 =====");
        player.sendMessage("§7家园名: §6" + home.name());
        player.sendMessage("§7等级: §e" + home.level() + "§7/" + homeManager.getMaxLevel());
        player.sendMessage("§7成员数: §e" + memberCount);
        player.sendMessage("§7拥有者: §f" + home.ownerName());
        player.sendMessage("§7我的贡献值: §e" + myContribution);
    }

    private void handleDisband(Player player) {
        var opt = homeManager.getHomelandByMember(player.getUniqueId());
        if (opt.isEmpty()) {
            player.sendMessage("§c你还没有家园");
            return;
        }
        int homelandId = opt.get();
        HomeManager.HomelandInfo home = homeManager.getHomeland(homelandId);
        if (home == null) {
            player.sendMessage("§c家园数据异常");
            return;
        }
        if (!home.ownerUuid().equals(player.getUniqueId().toString())) {
            player.sendMessage("§c只有家园拥有者才能解散家园");
            return;
        }
        if (homeManager.disbandHomeland(homelandId, player.getUniqueId())) {
            player.sendMessage("§a已解散家园 §6" + home.name());
            Bukkit.broadcastMessage("§6[家园] §7家园 §e" + home.name() + " §7已被解散");
        } else {
            player.sendMessage("§c解散失败");
        }
    }

    private void handleReload(Player player) {
        if (!player.hasPermission("letisland.homeland.admin")) {
            player.sendMessage("§c你没有权限");
            return;
        }
        homeManager.reload();
        player.sendMessage("§a家园系统配置已重载");
    }

    private void handleAdmin(Player player, String[] args) {
        if (!player.hasPermission("letisland.homeland.admin")) {
            player.sendMessage("§c你没有权限");
            return;
        }
        if (args.length < 2) {
            player.sendMessage("§c用法: §e/hd admin setlevel <玩家> <等级> §7或 §e/hd admin forcemember <玩家> <家园名>");
            return;
        }
        String action = args[1].toLowerCase();
        switch (action) {
            case "setlevel" -> handleAdminSetLevel(player, args);
            case "forcemember" -> handleAdminForceMember(player, args);
            default -> player.sendMessage("§c未知管理操作: §e" + action);
        }
    }

    private void handleAdminSetLevel(Player player, String[] args) {
        if (args.length < 4) {
            player.sendMessage("§c用法: §e/hd admin setlevel <玩家> <等级>");
            return;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[2]);
        var opt = homeManager.getHomelandByMember(target.getUniqueId());
        if (opt.isEmpty()) {
            player.sendMessage("§c该玩家不属于任何家园");
            return;
        }
        int homelandId = opt.get();
        int level;
        try {
            level = Integer.parseInt(args[3]);
        } catch (NumberFormatException e) {
            player.sendMessage("§c等级必须是数字");
            return;
        }
        if (level < 1 || level > homeManager.getMaxLevel()) {
            player.sendMessage("§c等级范围: 1-" + homeManager.getMaxLevel());
            return;
        }
        // 直接更新等级（管理员强制设置，不扣资源）
        if (homeManager.setHomelandLevel(homelandId, level)) {
            player.sendMessage("§a已将家园ID §e" + homelandId + " §a的等级设置为 §e" + level);
        } else {
            player.sendMessage("§c设置失败");
        }
    }

    private void handleAdminForceMember(Player player, String[] args) {
        if (args.length < 4) {
            player.sendMessage("§c用法: §e/hd admin forcemember <玩家> <家园名>");
            return;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[2]);
        String homelandName = args[3];
        HomeManager.HomelandInfo home = homeManager.getHomelandByName(homelandName);
        if (home == null) {
            player.sendMessage("§c家园 §e" + homelandName + " §c不存在");
            return;
        }
        // 先离开原家园（若有）
        homeManager.removeMember(target.getUniqueId());
        boolean ok = homeManager.addMember(home.id(), target.getUniqueId(), target.getName());
        if (ok) {
            player.sendMessage("§a已将 §e" + target.getName() + " §a强制加入家园 §6" + home.name());
        } else {
            player.sendMessage("§c操作失败");
        }
    }

    private void sendHelp(Player player) {
        player.sendMessage("§6===== 家园系统帮助 =====");
        player.sendMessage("§e/hd §7- 打开家园主面板");
        player.sendMessage("§e/hd create <家园名> §7- 创建家园");
        player.sendMessage("§e/hd invite <玩家> §7- 邀请玩家加入你的家园（仅拥有者）");
        player.sendMessage("§e/hd accept §7- 接受家园邀请");
        player.sendMessage("§e/hd decline §7- 拒绝家园邀请");
        player.sendMessage("§e/hd leave §7- 离开家园");
        player.sendMessage("§e/hd info §7- 查看家园信息");
        player.sendMessage("§e/hd disband §7- 解散家园（仅拥有者）");
        if (player.hasPermission("letisland.homeland.admin")) {
            player.sendMessage("§e/hd reload §7- 重载配置");
            player.sendMessage("§e/hd admin setlevel <玩家> <等级> §7- 设置家园等级");
            player.sendMessage("§e/hd admin forcemember <玩家> <家园名> §7- 强制加入");
        }
    }

    // ==================== Tab 补全 ====================

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            List<String> base = new ArrayList<>(List.of("create", "invite", "accept", "decline",
                    "leave", "info", "disband", "help"));
            if (sender.hasPermission("letisland.homeland.admin")) {
                base.add("reload");
                base.add("admin");
            }
            return filterPrefix(base, args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("admin")) {
            return filterPrefix(List.of("setlevel", "forcemember"), args[1]);
        }
        if (args.length >= 2 && (args[0].equalsIgnoreCase("invite")
                || (args[0].equalsIgnoreCase("admin") && args.length == 3
                        && args[1].equalsIgnoreCase("setlevel"))
                || (args[0].equalsIgnoreCase("admin") && args.length == 3
                        && args[1].equalsIgnoreCase("forcemember")))) {
            List<String> names = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
            return filterPrefix(names, args[args.length - 1]);
        }
        return List.of();
    }

    private List<String> filterPrefix(List<String> list, String prefix) {
        String lower = prefix.toLowerCase();
        List<String> result = new ArrayList<>();
        for (String s : list) {
            if (s.toLowerCase().startsWith(lower)) result.add(s);
        }
        return result;
    }

    // ==================== 数据类 ====================

    private record Invite(UUID inviterUuid, String inviterName, int homelandId,
                          String homelandName, long expiryMillis) {}
}
