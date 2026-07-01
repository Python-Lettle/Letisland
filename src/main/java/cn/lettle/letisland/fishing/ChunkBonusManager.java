package cn.lettle.letisland.fishing;

import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 区块钓鱼加成管理器
 * 玩家进入新区块后，前 maxCharges 次钓鱼获得高级鱼权重提升；
 * 次数耗尽后该区块进入冷却，冷却结束后恢复次数。
 * 状态按 玩家×区块 维度存储，仅在内存中（玩家退出时清理）。
 */
public class ChunkBonusManager {

    private boolean enabled;
    private int maxCharges;
    private long cooldownMillis;
    private double bonusPerCharge;
    private int rarityThreshold;

    /** 玩家 -> (区块key -> 状态) */
    private final Map<UUID, Map<Long, ChunkState>> cache = new ConcurrentHashMap<>();

    /** 单个区块的加成状态：剩余次数 + 冷却开始时间戳（0 表示未开始冷却） */
    private record ChunkState(int charges, long cooldownStartMillis) {}

    /** tryConsume 的返回结果，供监听器构造提示信息 */
    public record ConsumeResult(
            double bonus,
            int chargesRemaining,
            long cooldownRemainingMillis,
            boolean justStartedCooldown,
            boolean justRefilled
    ) {}

    public ChunkBonusManager(boolean enabled, int maxCharges, long cooldownMillis,
                             double bonusPerCharge, int rarityThreshold) {
        this.enabled = enabled;
        this.maxCharges = maxCharges;
        this.cooldownMillis = cooldownMillis;
        this.bonusPerCharge = bonusPerCharge;
        this.rarityThreshold = rarityThreshold;
    }

    /** 热重载时更新配置（缓存状态保留） */
    public void reloadConfig(boolean enabled, int maxCharges, long cooldownMillis,
                             double bonusPerCharge, int rarityThreshold) {
        this.enabled = enabled;
        this.maxCharges = maxCharges;
        this.cooldownMillis = cooldownMillis;
        this.bonusPerCharge = bonusPerCharge;
        this.rarityThreshold = rarityThreshold;
    }

    public boolean isEnabled() { return enabled; }
    public int getRarityThreshold() { return rarityThreshold; }

    /** 由区块坐标编码为唯一 long key（与 Bukkit Chunk.getChunkKey 一致） */
    public static long chunkKey(int cx, int cz) {
        return ((long) cx & 0xFFFFFFFFL) << 32 | ((long) cz & 0xFFFFFFFFL);
    }

    /**
     * 尝试消耗一次区块加成。
     * 每次消耗立即开始5分钟冷却，冷却结束后补满次数。
     * 返回本次获得的加成倍率（0 表示无加成，如冷却中），以及状态信息供提示。
     */
    @NotNull
    public ConsumeResult tryConsume(@NotNull UUID playerId, long chunkKey) {
        if (!enabled) {
            return new ConsumeResult(0.0, 0, 0, false, false);
        }
        long now = System.currentTimeMillis();
        Map<Long, ChunkState> playerChunks = cache.computeIfAbsent(playerId, k -> new HashMap<>());

        ChunkState state = playerChunks.get(chunkKey);
        boolean justRefilled = false;

        if (state == null) {
            // 新区块，首次进入
            state = new ChunkState(maxCharges, 0);
        }

        // 检查冷却是否已结束
        if (state.cooldownStartMillis() > 0) {
            long elapsed = now - state.cooldownStartMillis();
            if (elapsed >= cooldownMillis) {
                // 冷却已结束，补满次数
                state = new ChunkState(maxCharges, 0);
                justRefilled = true;
            }
        }

        if (state.charges() <= 0) {
            // 充能已耗尽，冷却中，计算剩余冷却时间
            long cooldownRemaining = 0;
            if (state.cooldownStartMillis() > 0) {
                long elapsed = now - state.cooldownStartMillis();
                cooldownRemaining = Math.max(0, cooldownMillis - elapsed);
            }
            return new ConsumeResult(0.0, 0, cooldownRemaining, false, false);
        }

        // 消耗一次充能
        int newCharges = state.charges() - 1;
        boolean justStartedCooldown = false;
        long newCooldownStart = state.cooldownStartMillis();

        // 如果之前没有冷却，立即开始冷却
        if (state.cooldownStartMillis() == 0) {
            newCooldownStart = now;
            justStartedCooldown = true;
        }

        playerChunks.put(chunkKey, new ChunkState(newCharges, newCooldownStart));

        // 计算剩余冷却时间
        long cooldownRemaining = 0;
        if (newCooldownStart > 0) {
            long elapsed = now - newCooldownStart;
            cooldownRemaining = Math.max(0, cooldownMillis - elapsed);
        }

        return new ConsumeResult(
                bonusPerCharge,
                newCharges,
                cooldownRemaining,
                justStartedCooldown,
                justRefilled
        );
    }

    /** 玩家退出时清理其所有区块状态 */
    public void evictCache(@NotNull UUID playerId) {
        cache.remove(playerId);
    }
}
