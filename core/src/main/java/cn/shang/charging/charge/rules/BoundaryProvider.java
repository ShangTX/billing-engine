package cn.shang.charging.charge.rules;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 边界驱动循环的边界来源。
 * <p>
 * 每个规则注册自己的边界来源；公共循环查询所有来源并跳到最近边界。
 * 返回的边界必须严格大于 {@code current} 且不大于 {@code calcEnd}。
 * <p>
 * 边界提供器可以访问和修改定价状态（如价格切换）。
 */
@FunctionalInterface
public interface BoundaryProvider {

    /**
     * 返回所有严格大于 {@code current}、不大于 {@code calcEnd} 的边界候选。
     * <p>
     * 边界提供器可以访问和修改定价状态。
     * 例如：日夜边界提供器在遇到 dayBegin/dayEnd 边界时修改 currentUnitPrice。
     *
     * @param current 当前位置（排他下界）
     * @param calcEnd 计算窗口终点（含上界）
     * @param state 定价状态（可读写）
     * @return 边界候选列表，可为空
     */
    List<LocalDateTime> getBoundaries(LocalDateTime current, LocalDateTime calcEnd, PricingState state);
}
