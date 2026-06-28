package cn.lettle.letisland.trash;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * 垃圾桶命令处理器
 * /trash - 打开垃圾桶GUI，玩家可从中取出被定时清理的掉落物
 */
public class TrashBinCommand implements CommandExecutor {

    private final TrashBinListener listener;

    public TrashBinCommand(@NotNull TrashBinListener listener) {
        this.listener = listener;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c只有玩家可以打开垃圾桶");
            return true;
        }
        listener.openTrash(player);
        return true;
    }
}
