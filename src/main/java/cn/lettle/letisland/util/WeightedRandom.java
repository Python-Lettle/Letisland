package cn.lettle.letisland.util;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.ToDoubleFunction;

/**
 * 加权随机选择工具类
 * 替代散布在各管理器中的重复加权随机算法
 */
public final class WeightedRandom {

    private WeightedRandom() {}

    /**
     * 从列表中按权重随机选择一个元素
     *
     * @param items       候选列表
     * @param weightFn    从元素提取权重的函数
     * @param totalWeight 所有权重之和（避免重复计算）
     * @return 选中的元素，列表为空返回 null
     */
    @Nullable
    public static <T> T pick(@NotNull List<T> items, @NotNull ToDoubleFunction<T> weightFn, double totalWeight) {
        if (items.isEmpty() || totalWeight <= 0) return null;
        double r = ThreadLocalRandom.current().nextDouble(totalWeight);
        double cumulative = 0;
        for (T item : items) {
            cumulative += weightFn.applyAsDouble(item);
            if (r <= cumulative) {
                return item;
            }
        }
        return items.get(items.size() - 1);
    }
}
