package cn.lettle.letisland.economy;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 经济系统核心管理器
 * 负责玩家余额的存储、查询、增减和转账操作
 */
public class EconomyManager {

    /** 起始余额 */
    private static final double DEFAULT_BALANCE = 0.0;

    private final JavaPlugin plugin;
    private final File dataFile;
    private FileConfiguration dataConfig;
    private final String currencySymbol;

    public EconomyManager(@NotNull JavaPlugin plugin, @NotNull String currencySymbol) {
        this.plugin = plugin;
        this.currencySymbol = currencySymbol;
        this.dataFile = new File(plugin.getDataFolder(), "economy.yml");
        loadData();
    }

    /**
     * 加载经济数据文件
     */
    private void loadData() {
        if (!dataFile.exists()) {
            try {
                dataFile.getParentFile().mkdirs();
                dataFile.createNewFile();
            } catch (IOException e) {
                throw new RuntimeException("无法创建经济数据文件: " + e.getMessage(), e);
            }
        }
        dataConfig = YamlConfiguration.loadConfiguration(dataFile);
    }

    /**
     * 保存经济数据到磁盘
     */
    public void saveData() {
        try {
            dataConfig.save(dataFile);
        } catch (IOException e) {
            throw new RuntimeException("无法保存经济数据文件: " + e.getMessage(), e);
        }
    }

    /**
     * 重新加载数据文件
     */
    public void reload() {
        loadData();
    }

    /**
     * 获取玩家余额
     */
    public double getBalance(@NotNull OfflinePlayer player) {
        return getBalance(player.getUniqueId());
    }

    /**
     * 获取玩家余额
     */
    public double getBalance(@NotNull UUID playerId) {
        return round(dataConfig.getDouble("players." + playerId + ".balance", DEFAULT_BALANCE));
    }

    /**
     * 设置玩家余额
     */
    public void setBalance(@NotNull OfflinePlayer player, double amount) {
        setBalance(player.getUniqueId(), amount);
    }

    /**
     * 设置玩家余额
     */
    public void setBalance(@NotNull UUID playerId, double amount) {
        double rounded = round(amount);
        double before = getBalance(playerId);
        dataConfig.set("players." + playerId + ".balance", rounded);
        saveData();

        OfflinePlayer player = Bukkit.getOfflinePlayer(playerId);
        callTransactionEvent(player, EconomyTransactionEvent.TransactionType.DEPOSIT,
                rounded - before, before, rounded);
    }

    /**
     * 检查玩家是否拥有账户
     */
    public boolean hasAccount(@NotNull OfflinePlayer player) {
        return hasAccount(player.getUniqueId());
    }

    /**
     * 检查玩家是否拥有账户
     */
    public boolean hasAccount(@NotNull UUID playerId) {
        return dataConfig.contains("players." + playerId);
    }

    /**
     * 创建新账户
     */
    public void createAccount(@NotNull OfflinePlayer player) {
        createAccount(player.getUniqueId());
    }

    /**
     * 创建新账户
     */
    public void createAccount(@NotNull UUID playerId) {
        if (!hasAccount(playerId)) {
            dataConfig.set("players." + playerId + ".balance", DEFAULT_BALANCE);
            saveData();

            OfflinePlayer player = Bukkit.getOfflinePlayer(playerId);
            callTransactionEvent(player, EconomyTransactionEvent.TransactionType.INITIALIZE,
                    DEFAULT_BALANCE, 0.0, DEFAULT_BALANCE);
        }
    }

    /**
     * 存入金额
     */
    public boolean deposit(@NotNull OfflinePlayer player, double amount) {
        return deposit(player.getUniqueId(), amount);
    }

    /**
     * 存入金额
     */
    public boolean deposit(@NotNull UUID playerId, double amount) {
        if (amount <= 0) {
            return false;
        }
        double before = getBalance(playerId);
        double after = round(before + amount);
        dataConfig.set("players." + playerId + ".balance", after);
        saveData();

        OfflinePlayer player = Bukkit.getOfflinePlayer(playerId);
        callTransactionEvent(player, EconomyTransactionEvent.TransactionType.DEPOSIT,
                amount, before, after);
        return true;
    }

    /**
     * 取出金额
     */
    public boolean withdraw(@NotNull OfflinePlayer player, double amount) {
        return withdraw(player.getUniqueId(), amount);
    }

    /**
     * 取出金额
     */
    public boolean withdraw(@NotNull UUID playerId, double amount) {
        if (amount <= 0) {
            return false;
        }
        double before = getBalance(playerId);
        if (before < amount) {
            return false;
        }
        double after = round(before - amount);
        dataConfig.set("players." + playerId + ".balance", after);
        saveData();

        OfflinePlayer player = Bukkit.getOfflinePlayer(playerId);
        callTransactionEvent(player, EconomyTransactionEvent.TransactionType.WITHDRAW,
                amount, before, after);
        return true;
    }

    /**
     * 检查玩家是否拥有足够余额
     */
    public boolean has(@NotNull OfflinePlayer player, double amount) {
        return has(player.getUniqueId(), amount);
    }

    /**
     * 检查玩家是否拥有足够余额
     */
    public boolean has(@NotNull UUID playerId, double amount) {
        return getBalance(playerId) >= amount;
    }

    /**
     * 转账
     */
    public boolean transfer(@NotNull OfflinePlayer from, @NotNull OfflinePlayer to, double amount) {
        return transfer(from.getUniqueId(), to.getUniqueId(), amount);
    }

    /**
     * 转账
     */
    public boolean transfer(@NotNull UUID fromId, @NotNull UUID toId, double amount) {
        if (amount <= 0 || fromId.equals(toId)) {
            return false;
        }
        if (!has(fromId, amount)) {
            return false;
        }

        double fromBefore = getBalance(fromId);
        double toBefore = getBalance(toId);
        double fromAfter = round(fromBefore - amount);
        double toAfter = round(toBefore + amount);

        dataConfig.set("players." + fromId + ".balance", fromAfter);
        dataConfig.set("players." + toId + ".balance", toAfter);
        saveData();

        OfflinePlayer fromPlayer = Bukkit.getOfflinePlayer(fromId);
        OfflinePlayer toPlayer = Bukkit.getOfflinePlayer(toId);

        callTransactionEvent(fromPlayer, EconomyTransactionEvent.TransactionType.TRANSFER_OUT,
                amount, fromBefore, fromAfter);
        callTransactionEvent(toPlayer, EconomyTransactionEvent.TransactionType.TRANSFER_IN,
                amount, toBefore, toAfter);
        return true;
    }

    /**
     * 格式化金额显示
     */
    @NotNull
    public String format(double amount) {
        BigDecimal bd = new BigDecimal(amount).setScale(2, RoundingMode.HALF_UP);
        return currencySymbol + bd.toPlainString();
    }

    /**
     * 获取货币符号
     */
    @NotNull
    public String getCurrencySymbol() {
        return currencySymbol;
    }

    /**
     * 四舍五入到两位小数
     */
    private double round(double amount) {
        return BigDecimal.valueOf(amount).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    /**
     * 触发交易事件（异步执行）
     */
    private void callTransactionEvent(@NotNull OfflinePlayer player,
                                      @NotNull EconomyTransactionEvent.TransactionType type,
                                      double amount, double before, double after) {
        CompletableFuture.runAsync(() -> {
            EconomyTransactionEvent event = new EconomyTransactionEvent(player, type, amount, before, after);
            Bukkit.getScheduler().runTask(plugin, () -> Bukkit.getPluginManager().callEvent(event));
        });
    }
}
