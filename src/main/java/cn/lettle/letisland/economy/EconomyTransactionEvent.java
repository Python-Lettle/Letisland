package cn.lettle.letisland.economy;

import org.bukkit.OfflinePlayer;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Event;
import org.jetbrains.annotations.NotNull;

/**
 * 经济交易事件
 * 当玩家发生经济变动时触发（存款、取款、转账等）
 */
public class EconomyTransactionEvent extends Event {

    private static final HandlerList handlers = new HandlerList();

    private final OfflinePlayer player;
    private final TransactionType type;
    private final double amount;
    private final double balanceBefore;
    private final double balanceAfter;

    public EconomyTransactionEvent(@NotNull OfflinePlayer player, @NotNull TransactionType type,
                                   double amount, double balanceBefore, double balanceAfter) {
        this.player = player;
        this.type = type;
        this.amount = amount;
        this.balanceBefore = balanceBefore;
        this.balanceAfter = balanceAfter;
    }

    @NotNull
    public OfflinePlayer getPlayer() {
        return player;
    }

    @NotNull
    public TransactionType getType() {
        return type;
    }

    public double getAmount() {
        return amount;
    }

    public double getBalanceBefore() {
        return balanceBefore;
    }

    public double getBalanceAfter() {
        return balanceAfter;
    }

    @NotNull
    @Override
    public HandlerList getHandlers() {
        return handlers;
    }

    @NotNull
    public static HandlerList getHandlerList() {
        return handlers;
    }

    /**
     * 交易类型
     */
    public enum TransactionType {
        /** 存入 */
        DEPOSIT,
        /** 取出 */
        WITHDRAW,
        /** 转账（转出） */
        TRANSFER_OUT,
        /** 转账（转入） */
        TRANSFER_IN,
        /** 初始化账户 */
        INITIALIZE
    }
}
