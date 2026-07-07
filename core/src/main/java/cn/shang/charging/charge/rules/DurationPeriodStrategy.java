package cn.shang.charging.charge.rules;

import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.BillingContext;
import cn.shang.charging.billing.pojo.BillingSegmentResult;
import cn.shang.charging.billing.pojo.CalculationWindow;
import cn.shang.charging.billing.pojo.DurationSegment;
import cn.shang.charging.billing.pojo.RuleConfig;
import cn.shang.charging.promotion.PromotionAggregateUtil;
import cn.shang.charging.promotion.pojo.FreeMinuteAllocationResult;
import cn.shang.charging.promotion.pojo.FreeTimeRange;
import cn.shang.charging.promotion.pojo.PromotionAggregate;
import cn.shang.charging.promotion.pojo.PromotionUsage;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * DURATION_PERIOD 模式通用策略（层 2）：周期内时长计费。
 * <p>
 * 规则族差异（周期边界、时段边界、单价、period 标签/封顶）通过 {@link RuleSemantics} 注入，
 * 通用产出逻辑（{@link DurationSegment} / 时段封顶 / 周期封顶）由 {@link DurationSupport} 承载。
 * 与 {@link DurationGlobalStrategy} 共享切分模型，仅封顶数学不同（周期内累计 vs 全局倍乘）。
 * 复用 {@link BoundaryDrivenLoop} 公共调度层，不继承 {@code AbstractTimeBasedRule}。
 * <p>
 * TODO-20260706-002 阶段4：从 DayNightDurationStrategy 拆出通用 PERIOD 策略。
 */
public final class DurationPeriodStrategy {

    private DurationPeriodStrategy() {
    }

    /**
     * PERIOD 模式计费入口。
     * <p>
     * 边界来源：周期结束 + 时段边界 + 免费段起止 + calcEnd。
     * 段构造：免费段判定 + {@link RuleSemantics#priceAt}。
     * 产出：DurationSegment 列表 + FREE_RANGE/FREE_MINUTES PromotionUsage。
     */
    public static <C extends RuleConfig> BillingSegmentResult calculate(
            RuleSemantics<C> semantics,
            BillingContext context,
            C config,
            PromotionAggregate promotionAggregate) {

        CalculationWindow window = context.getWindow();
        LocalDateTime calcBegin = window.getCalculationBegin();
        LocalDateTime calcEnd = window.getCalculationEnd();
        LocalDateTime cycleOrigin = semantics.cycleOrigin(context);
        BigDecimal cycleCap = semantics.cycleCap(config);

        // FREE_MINUTES 时段化（TODO-20260706-001：PERIOD/GLOBAL 统一，免费段独立，DurationSegment 同质）
        FreeMinuteAllocationResult materialized = RuleSupport.materializeFreeMinutes(promotionAggregate, window);
        List<FreeTimeRange> freeTimeRanges = materialized.getFinalFreeRanges() != null
                ? materialized.getFinalFreeRanges() : List.of();

        // 边界来源：周期结束（基于 cycleOrigin 对齐周期边界）+ 时段边界 + 免费段起止 + calcEnd
        List<BoundaryProvider> providers = new ArrayList<>();
        providers.add(BoundaryProviders.cycleEnd(cycleOrigin, semantics.cycleMinutes()));
        providers.add(semantics.periodBoundaryProvider(config, cycleOrigin));
        providers.add(BoundaryProviders.freeRangeEdges(freeTimeRanges));
        providers.add(BoundaryProviders.calcEnd(calcEnd));

        // 边界驱动循环
        List<HomogeneousSegment> segments = BoundaryDrivenLoop.run(calcBegin, calcEnd, providers, (current, next) -> {
            // 检查免费时段
            for (FreeTimeRange range : freeTimeRanges) {
                if (!range.getBeginTime().isAfter(current) && !range.getEndTime().isBefore(next)) {
                    return new HomogeneousSegment(current, next, BigDecimal.ZERO, BigDecimal.ZERO,
                            true, range.getId(), range.getRangeType(), null);
                }
            }
            // 计算单元单价
            BigDecimal unitPrice = semantics.priceAt(current, next, config, cycleOrigin);
            return new HomogeneousSegment(current, next, unitPrice, unitPrice, false, null, null);
        });

        // 转换为 DurationSegment（时段封顶 + 周期封顶）
        DurationSupport.DurationResult durationResult =
                DurationSupport.buildPeriodMode(segments, cycleCap, semantics, config, cycleOrigin);

        // 产出 FREE_RANGE 的 PromotionUsage（equivalentAmount 从 DurationSegment.originalAmount 聚合）
        final List<DurationSegment> finalSegments = durationResult.segments;
        List<PromotionUsage> freeRangeUsages = PromotionAggregateUtil.buildFreeRangeUsages(
                freeTimeRanges, calcBegin, calcEnd,
                rangeId -> finalSegments.stream()
                        .filter(ds -> rangeId.equals(ds.freePromotionId()))
                        .map(DurationSegment::originalAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add));
        List<PromotionUsage> allUsages = new ArrayList<>(freeRangeUsages);
        // FREE_MINUTES usage：PERIOD/GLOBAL 统一来自时段化
        List<PromotionUsage> freeMinutesUsages = materialized != null && materialized.getPromotionUsages() != null
                ? materialized.getPromotionUsages() : List.of();
        allUsages.addAll(freeMinutesUsages);

        return BillingSegmentResult.builder()
                .segmentId(context.getSegment().getId())
                .segmentStartTime(context.getSegment().getBeginTime())
                .segmentEndTime(context.getSegment().getEndTime())
                .calculationStartTime(calcBegin)
                .calculationEndTime(calcEnd)
                .chargedAmount(durationResult.chargedAmount)
                .billingUnits(List.of())  // 时长模式不产出 BillingUnit
                .durationSegments(durationResult.segments)
                .calculationMode(BConstants.CalculationMode.DURATION_PERIOD)
                .cycleCapApplied(durationResult.cycleCapApplied)
                .promotionUsages(allUsages)
                .promotionAggregate(promotionAggregate)
                .build();
    }
}
