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
 * DURATION_GLOBAL 模式通用策略（层 2）：全局时长计费。
 * <p>
 * 规则族差异通过 {@link RuleSemantics} 注入，通用产出逻辑由 {@link DurationSupport} 承载。
 * 与 {@link DurationPeriodStrategy} 共享切分模型，仅封顶数学不同：
 * 无周期边界切断（segment 跨周期合并），周期封顶 = cap × 周期数（全局倍乘）。
 * 复用 {@link BoundaryDrivenLoop} 公共调度层，不继承 {@code AbstractTimeBasedRule}。
 * <p>
 * FREE_MINUTES 时段化（TODO-20260706-001）：与 PERIOD 同路径，免费段独立，DurationSegment 同质。
 * <p>
 * TODO-20260706-002 阶段4：从 DayNightDurationStrategy 拆出通用 GLOBAL 策略。
 */
public final class DurationGlobalStrategy {

    private DurationGlobalStrategy() {
    }

    /**
     * GLOBAL 模式计费入口。
     * <p>
     * 边界来源：时段边界 + 免费段起止 + calcEnd（不含周期边界，segment 跨周期合并）。
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
        FreeMinuteAllocationResult materialized = AbstractTimeBasedRule.materializeFreeMinutes(promotionAggregate, window);
        List<FreeTimeRange> freeTimeRanges = materialized.getFinalFreeRanges() != null
                ? materialized.getFinalFreeRanges() : List.of();

        // 边界来源：时段边界 + 免费段起止 + calcEnd（无周期边界，segment 跨周期合并）
        List<BoundaryProvider> providers = new ArrayList<>();
        providers.add(semantics.periodBoundaryProvider(config, cycleOrigin));
        providers.add(BoundaryProviders.freeRangeEdges(freeTimeRanges));
        providers.add(BoundaryProviders.calcEnd(calcEnd));

        // 边界驱动循环
        List<HomogeneousSegment> segments = BoundaryDrivenLoop.run(calcBegin, calcEnd, providers, (current, next) -> {
            // 检查免费时段
            for (FreeTimeRange range : freeTimeRanges) {
                if (!range.getBeginTime().isAfter(current) && !range.getEndTime().isBefore(next)) {
                    return new HomogeneousSegment(current, next, BigDecimal.ZERO, BigDecimal.ZERO,
                            true, range.getId(), null);
                }
            }
            // 计算单元单价
            BigDecimal unitPrice = semantics.priceAt(current, next, config, cycleOrigin);
            return new HomogeneousSegment(current, next, unitPrice, unitPrice, false, null, null);
        });

        // 转换为 DurationSegment（时段封顶 × 周期数 + 周期封顶 × 周期数）
        long totalMinutes = Duration.between(calcBegin, calcEnd).toMinutes();
        DurationSupport.DurationResult durationResult =
                DurationSupport.buildGlobalMode(segments, totalMinutes, cycleCap, semantics, config, cycleOrigin);

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
                .calculationMode(BConstants.CalculationMode.DURATION_GLOBAL)
                .cycleCapApplied(durationResult.cycleCapApplied)
                .promotionUsages(allUsages)
                .promotionAggregate(promotionAggregate)
                .build();
    }
}
