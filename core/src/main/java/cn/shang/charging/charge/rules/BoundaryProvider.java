package cn.shang.charging.charge.rules;

import java.time.LocalDateTime;

/**
 * 边界驱动循环的边界来源。
 * <p>
 * 每个规则注册自己的边界来源；公共循环查询所有来源并跳到最近边界。
 * 返回的边界必须严格大于 {@code current} 且不大于 {@code calcEnd}。
 * <p>
 * 边界提供器只负责提供边界，不携带计费语义或修改外部状态。
 */
@FunctionalInterface
public interface BoundaryProvider {

    /**
     * 返回严格大于 {@code current}、不大于 {@code calcEnd} 的最近边界。
     * <p>
     * @param current 当前位置（排他下界）
     * @param calcEnd 计算窗口终点（含上界）
     * @return 最近边界；没有候选时返回 {@code null}
     */
    LocalDateTime nextBoundary(LocalDateTime current, LocalDateTime calcEnd);
}
