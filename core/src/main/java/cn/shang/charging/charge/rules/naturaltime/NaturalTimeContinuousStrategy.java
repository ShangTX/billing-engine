package cn.shang.charging.charge.rules.naturaltime;

import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.BillingContext;
import cn.shang.charging.billing.pojo.BillingSegmentResult;
import cn.shang.charging.billing.pojo.BillingUnit;
import cn.shang.charging.charge.rules.BillingRule;
import cn.shang.charging.charge.rules.BoundaryDrivenLoop;
import cn.shang.charging.charge.rules.BoundaryProvider;
import cn.shang.charging.charge.rules.BoundaryProviders;
import cn.shang.charging.charge.rules.ContinuousStrategy;
import cn.shang.charging.charge.rules.HomogeneousSegment;
import cn.shang.charging.charge.rules.RuleSupport;
import cn.shang.charging.charge.rules.compositetime.CrossPeriodMode;
import cn.shang.charging.charge.rules.compositetime.NaturalPeriod;
import cn.shang.charging.promotion.pojo.FreeMinuteAllocationResult;
import cn.shang.charging.promotion.pojo.FreeTimeRange;
import cn.shang.charging.promotion.pojo.PromotionAggregate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * `naturalTime` 规则在 CONTINUOUS 模式下的策略实现。
 * <p>
 * 承载 CONTINUOUS 语义：边界驱动切断 + 自然日 24h 封顶。
 * 复用不足单元计费等公共基础设施（{@link ContinuousStrategy}）与 FREE_MINUTES 时段化（{@link RuleSupport}），
 * 不再继承旧基类 {@code AbstractTimeBasedRule}（TODO-20260706-002 阶段7 废弃）。
 * <p>
 * 由 {@link NaturalTimeRule} 门面按 calculationMode=CONTINUOUS 分派调用，不独立注册。
 * 从 {@code NaturalTimeRule} 的 CONTINUOUS 逻辑迁移而来（TODO-20260706-002 阶段2b）。
 */
final class NaturalTimeContinuousStrategy implements BillingRule<NaturalTimeConfig> {

    private static final int MINUTES_PER_CYCLE = 1440; // 24小时

    private final NaturalTimePeriodResolver periodResolver = new NaturalTimePeriodResolver();
    private final NaturalTimeCrossPeriodPriceResolver priceResolver = new NaturalTimeCrossPeriodPriceResolver();
    private final NaturalTimeSemantics naturalTimeSemantics = new NaturalTimeSemantics();

    @Override
    public Class<NaturalTimeConfig> configClass() {
        return NaturalTimeConfig.class;
    }

    /**
     * 本策略仅承载 CONTINUOUS 模式；门面 {@link NaturalTimeRule} 声明完整的 supportedCalculationModes。
     * 此方法为接口契约所需，不被 Calculator 直接调用（Calculator 校验门面的 supportedCalculationModes）。
     */
    @Override
    public Set<BConstants.CalculationMode> supportedCalculationModes() {
        return EnumSet.of(BConstants.CalculationMode.CONTINUOUS);
    }

    /**
     * CONTINUOUS 模式计算
     */
    @Override
    public BillingSegmentResult calculate(BillingContext context,
                                          NaturalTimeConfig config,
                                          PromotionAggregate promotionAggregate) {
        validateConfig(config);

        LocalDateTime calcBegin = context.getWindow().getCalculationBegin();
        LocalDateTime calcEnd = context.getWindow().getCalculationEnd();
        int unitMinutes = config.getUnitMinutes();
        List<NaturalPeriod> periods = config.getPeriods();
        CrossPeriodMode crossPeriodMode = config.getCrossPeriodMode();

        // 时段化 FREE_MINUTES（TODO-20260702-004：从 PromotionEngine 下放到策略侧）
        FreeMinuteAllocationResult materialized = RuleSupport.materializeFreeMinutes(promotionAggregate, context.getWindow());
        List<FreeTimeRange> freeTimeRanges = materialized.getFinalFreeRanges() != null
                ? materialized.getFinalFreeRanges() : List.of();

        // CONTINUOUS 模式不支持 BUBBLE 免费时段（bubble 需 effective 周期，CONTINUOUS 未消费）
        ContinuousStrategy.assertNoBubbleSupported(freeTimeRanges);

        // 边界来源：自然时段边界 + 免费时段起止 + 计费单元对齐 + calcEnd
        // 周期对齐在跨周期封顶处由 cycleAccumulated 单独处理
        List<BoundaryProvider> providers = new ArrayList<>();
        providers.add((current, end) -> {
            int currentMinute = current.getHour() * 60 + current.getMinute();
            int periodEnd = periodResolver.findNextPeriodBoundary(currentMinute, periods);
            LocalDateTime periodBoundary = current.plusMinutes(periodEnd - currentMinute);
            if (periodBoundary.isAfter(current) && !periodBoundary.isAfter(end)) {
                return periodBoundary;
            }
            return null;
        });
        providers.add(BoundaryProviders.freeRangeEdges(freeTimeRanges));
        providers.add(BoundaryProviders.calcEnd(calcEnd));

        // 边界驱动循环
        List<HomogeneousSegment> segments = BoundaryDrivenLoop.run(calcBegin, calcEnd, providers, (current, next) -> {
            // 检查是否在免费时段内
            FreeTimeRange covering = findCoveringRange(current, freeTimeRanges);
            if (covering != null) {
                return new HomogeneousSegment(current, next, BigDecimal.ZERO, BigDecimal.ZERO,
                        true, covering.getId(), null);
            }
            // 计费段
            BigDecimal unitPrice = priceResolver.calculateUnitPrice(current, next, periods, crossPeriodMode);
            return new HomogeneousSegment(current, next, unitPrice, unitPrice,
                    false, null, null);
        });

        // 转换为 BillingUnit（compact 合并 + 累计金额 + 封顶处理）
        List<BillingUnit> billingUnits = ContinuousStrategy.applyCapAndAccumulate(segments, naturalTimeSemantics,
                context, config, context.getBeginTime(), calcBegin, null);

        BigDecimal totalAmount = billingUnits.stream()
                .map(BillingUnit::getChargedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);


        // 状态续算已下线（CONTINUE 移除），单次计算周期跟踪从 calcBegin 初始化

        return BillingSegmentResult.builder()
                .segmentId(context.getSegment().getId())
                .segmentStartTime(context.getSegment().getBeginTime())
                .segmentEndTime(context.getSegment().getEndTime())
                .calculationStartTime(calcBegin)
                .calculationEndTime(calcEnd)
                .chargedAmount(totalAmount)
                .billingUnits(billingUnits)
                .promotionAggregate(promotionAggregate)
                .build();
    }

    // ==================== 辅助方法 ====================

    private void validateConfig(NaturalTimeConfig config) {
        if (config.getPeriods() == null || config.getPeriods().isEmpty()) {
            throw new IllegalArgumentException("periods 不能为空");
        }

        if (config.getUnitMinutes() <= 0) {
            throw new IllegalArgumentException("unitMinutes 必须大于 0");
        }

        validatePeriodsCoverage(config.getPeriods());
    }

    private void validatePeriodsCoverage(List<NaturalPeriod> periods) {
        int totalCovered = 0;
        int prevEnd = 0;

        for (int i = 0; i < periods.size(); i++) {
            NaturalPeriod period = periods.get(i);

            if (i > 0 && period.getBeginMinute() != prevEnd) {
                throw new IllegalArgumentException("时段不连续");
            }

            if (period.getBeginMinute() < period.getEndMinute()) {
                totalCovered += period.getEndMinute() - period.getBeginMinute();
            } else {
                totalCovered += (MINUTES_PER_CYCLE - period.getBeginMinute()) + period.getEndMinute();
            }

            prevEnd = period.getEndMinute();
        }

        if (totalCovered != MINUTES_PER_CYCLE) {
            throw new IllegalArgumentException("时段未覆盖全天");
        }
    }

    /**
     * 查找在 {@code at} 时刻覆盖当前点的免费时段。
     */
    private FreeTimeRange findCoveringRange(LocalDateTime at, List<FreeTimeRange> freeTimeRanges) {
        for (FreeTimeRange range : freeTimeRanges) {
            if (!at.isBefore(range.getBeginTime()) && at.isBefore(range.getEndTime())) {
                return range;
            }
        }
        return null;
    }

}
