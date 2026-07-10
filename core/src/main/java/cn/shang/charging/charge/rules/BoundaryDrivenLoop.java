package cn.shang.charging.charge.rules;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 边界驱动循环工具：纯调度，零计费语义。
 * <p>
 * 从当前位置反复查询所有边界来源中最近的边界，跳到那里并调用段构造回调产出同质段，直到抵达 calcEnd。
 * 供 CONTINUOUS 策略和时长策略共享，UNIT_BASED 策略不走。
 * <p>
 * 提取自原 {@code AbstractTimeBasedRule}（已废弃），作为规则族共享的调度原语，不通过继承传递。
 */
public final class BoundaryDrivenLoop {

    private BoundaryDrivenLoop() {
    }

    /**
     * 运行边界驱动循环。
     *
     * @param calcBegin       计算窗口起点（含）
     * @param calcEnd         计算窗口终点（含上界）
     * @param providers       边界来源列表
     * @param segmentBuilder  段构造回调：给定一个同质区间 [current, next)，返回对应的 HomogeneousSegment
     * @param state           定价状态（在循环过程中传递给边界提供器和段构建器）
     * @return 按时间顺序排列的同质段列表
     */
    public static List<HomogeneousSegment> run(LocalDateTime calcBegin,
                                               LocalDateTime calcEnd,
                                               List<BoundaryProvider> providers,
                                               SegmentBuilder segmentBuilder,
                                               PricingState state) {
        List<HomogeneousSegment> segments = new ArrayList<>();
        LocalDateTime current = calcBegin;
        while (current.isBefore(calcEnd)) {
            // TODO 优化1 不必每次都全部计算一遍，完全可以保留没被使用的边界下次再继续使用
            LocalDateTime next = BoundaryProviders.findNearest(current, calcEnd, providers, state);
            if (!next.isAfter(current)) {
                // 防御：避免无限循环（所有边界都在 current 之前）
                break;
            }
            HomogeneousSegment segment = segmentBuilder.build(current, next, state);
            if (segment != null) {
                segments.add(segment);
            }
            current = next;
        }
        return segments;
    }

    /**
     * 段构造回调接口。
     * 给定 [begin, end) 同质区间，返回一个 HomogeneousSegment（或 null 跳过该段）。
     */
    @FunctionalInterface
    public interface SegmentBuilder {
        HomogeneousSegment build(LocalDateTime begin, LocalDateTime end, PricingState state);
    }
}
