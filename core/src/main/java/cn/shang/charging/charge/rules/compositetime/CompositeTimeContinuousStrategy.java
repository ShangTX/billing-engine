package cn.shang.charging.charge.rules.compositetime;

import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.BillingContext;
import cn.shang.charging.billing.pojo.BillingSegmentResult;
import cn.shang.charging.billing.pojo.BillingUnit;
import cn.shang.charging.billing.pojo.CalculationWindow;
import cn.shang.charging.charge.rules.BillingRule;
import cn.shang.charging.charge.rules.BoundaryDrivenLoop;
import cn.shang.charging.charge.rules.BoundaryProvider;
import cn.shang.charging.charge.rules.BoundaryProviders;
import cn.shang.charging.charge.rules.ContinuousStrategy;
import cn.shang.charging.charge.rules.HomogeneousSegment;
import cn.shang.charging.charge.rules.PricingState;
import cn.shang.charging.charge.rules.RuleSupport;
import cn.shang.charging.charge.rules.SimplificationSupport;
import cn.shang.charging.promotion.PromotionAggregateUtil;
import cn.shang.charging.promotion.pojo.FreeMinuteAllocationResult;
import cn.shang.charging.promotion.pojo.FreeTimeRange;
import cn.shang.charging.promotion.pojo.PromotionAggregate;
import cn.shang.charging.promotion.pojo.PromotionUsage;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * `compositeTime` 规则在 CONTINUOUS 模式下的策略实现。
 * <p>
 * 承载 CONTINUOUS 语义：边界驱动切断 + 24h 周期封顶 + 时段独立封顶（periodCap）+ 简化计算。
 * 复用不足单元计费等公共基础设施（{@link ContinuousStrategy}）与 FREE_MINUTES 时段化（{@link RuleSupport}），
 * 不再继承旧基类 {@code AbstractTimeBasedRule}（TODO-20260706-002 阶段7 废弃）。
 * 封顶 + 累计逻辑由通用 {@link ContinuousStrategy#applyCapAndAccumulate} 承载
 * （TODO-20260706-002 阶段3：4 份合并为 1 份），周期切换/periodCap 等差异由 {@link CompositeTimeSemantics} 注入。
 * <p>
 * 简化路径改"全局视角算无优惠空隙"实现（TODO-20260706-002 阶段3b，决策 C）：
 * 从 {@code freeTimeRanges} 直接算无优惠空隙，每个 gap 对齐周期边界（基于 billingOrigin），
 * 覆盖周期数 &gt; 阈值则生成简化单元，否则与优惠段一起走边界驱动。
 * 旧切段模型（splitTimeAxis/organizeByCycle/TimeFragment/CycleFragments）已迁移到全局空隙实现。
 * <p>
 * 由 {@link CompositeTimeRule} 门面按 calculationMode=CONTINUOUS 分派调用，不独立注册。
 * 从 {@code CompositeTimeRule} 的 CONTINUOUS 逻辑迁移而来（TODO-20260706-002 阶段2c）。
 */
final class CompositeTimeContinuousStrategy implements BillingRule<CompositeTimeConfig> {

    private static final int MINUTES_PER_CYCLE = 1440; // 24小时

    private final CompositeTimePeriodResolver periodResolver = new CompositeTimePeriodResolver();
    private final CompositeTimeCrossPeriodPriceResolver crossPeriodPriceResolver = new CompositeTimeCrossPeriodPriceResolver();
    private final CompositeTimeSemantics compositeTimeSemantics = new CompositeTimeSemantics();

    @Override
    public Class<CompositeTimeConfig> configClass() {
        return CompositeTimeConfig.class;
    }

    /**
     * 本策略仅承载 CONTINUOUS 模式；门面 {@link CompositeTimeRule} 声明完整的 supportedCalculationModes。
     * 此方法为接口契约所需，不被 Calculator 直接调用（Calculator 校验门面的 supportedCalculationModes）。
     */
    @Override
    public Set<BConstants.CalculationMode> supportedCalculationModes() {
        return EnumSet.of(BConstants.CalculationMode.CONTINUOUS);
    }

    /**
     * CONTINUOUS 模式计算。
     * <p>
     * 非简化路径：边界驱动切断（period 边界 + cycleEnd + freeRangeEdges + 单元对齐 + calcEnd），
     * 封顶/累计/periodCap 由通用 {@link ContinuousStrategy#applyCapAndAccumulate} 处理。
     * 简化路径：{@link #generateUnitsByGlobalGaps}（决策 C，全局空隙实现）。
     */
    @Override
    public BillingSegmentResult calculate(BillingContext context,
                                          CompositeTimeConfig config,
                                          PromotionAggregate promotionAggregate) {
        validateConfig(config);

        // 获取计算窗口
        CalculationWindow window = context.getWindow();
        LocalDateTime calcBegin = window.getCalculationBegin();
        LocalDateTime calcEnd = window.getCalculationEnd();

        // 获取计费起点（从分段信息获取）
        LocalDateTime billingOrigin = context.getSegment().getBeginTime();

        // 时段化 FREE_MINUTES（TODO-20260702-004：从 PromotionEngine 下放到策略侧）
        FreeMinuteAllocationResult materialized = RuleSupport.materializeFreeMinutes(promotionAggregate, window);
        List<FreeTimeRange> freeTimeRanges = materialized.getFinalFreeRanges() != null
                ? materialized.getFinalFreeRanges() : List.of();

        // CONTINUOUS 模式不支持 BUBBLE 免费时段（bubble 需 effective 周期，CONTINUOUS 未消费）
        ContinuousStrategy.assertNoBubbleSupported(freeTimeRanges);

        // 检查是否启用简化计算
        BigDecimal cycleCapAmount = compositeTimeSemantics.cycleCap(config);
        boolean simplificationEnabled = context.getBillingConfigResolver() != null
            && ContinuousStrategy.isSimplificationEnabled(config, context.getBillingConfigResolver(), context, cycleCapAmount);
        int threshold = context.getBillingConfigResolver() != null
            ? context.getBillingConfigResolver().getSimplifiedCycleThreshold()
            : 0;
        // 全局空隙实现（决策 C）：算无优惠空隙，长空隙简化，短空隙与优惠段走边界驱动
        // FREE_MINUTES 时段化后免费段参与 gaps，简化能正确处理，不再保守排除
        List<BillingUnit> allUnits;
        if (simplificationEnabled && threshold > 0) {
            allUnits = generateUnitsByGlobalGaps(calcBegin, calcEnd, context, config,
                    freeTimeRanges, threshold, billingOrigin);
        } else {
            allUnits = calculateBoundaryDriven(calcBegin, calcEnd, context, config, freeTimeRanges, billingOrigin);
        }

        BigDecimal totalAmount = allUnits.stream()
                .map(BillingUnit::getChargedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 标记最后一个单元是否被截断
        if (!allUnits.isEmpty()) {
            BillingUnit lastUnit = allUnits.get(allUnits.size() - 1);
            // 获取最后一个单元对应的单元长度
            int minutesFromBillingOrigin = (int) Duration.between(billingOrigin, lastUnit.getBeginTime()).toMinutes();
            int positionInCycle = minutesFromBillingOrigin % MINUTES_PER_CYCLE;
            if (positionInCycle < 0) {
                positionInCycle += MINUTES_PER_CYCLE;
            }
            CompositePeriod period = periodResolver.findPeriodForMinute(positionInCycle, config.getPeriods());
            int unitMinutes = period.getUnitMinutes();
            if (lastUnit.getDurationMinutes() < unitMinutes && lastUnit.getEndTime().equals(calcEnd)) {
                lastUnit.setIsTruncated(true);
            }
        }

        // 产出 FREE_RANGE 的 PromotionUsage（CONTINUOUS 免费单元 originalAmount=0）
        List<PromotionUsage> freeRangeUsages = PromotionAggregateUtil.buildFreeRangeUsages(
                freeTimeRanges, calcBegin, calcEnd);
        List<PromotionUsage> allUsages = new ArrayList<>(freeRangeUsages);
        if (materialized.getPromotionUsages() != null) {
            allUsages.addAll(materialized.getPromotionUsages());
        }

        return BillingSegmentResult.builder()
                .segmentId(context.getSegment().getId())
                .segmentStartTime(context.getSegment().getBeginTime())
                .segmentEndTime(context.getSegment().getEndTime())
                .calculationStartTime(calcBegin)
                .calculationEndTime(calcEnd)
                .chargedAmount(totalAmount)
                .billingUnits(allUnits)
                .promotionUsages(allUsages)
                .promotionAggregate(promotionAggregate)
                .build();
    }

    /**
     * 全局空隙实现（决策 C）：
     * <ol>
     *   <li>从 {@code freeTimeRanges} 算无优惠空隙（gap = 优惠段之间的间隙 + 头尾）</li>
     *   <li>每个 gap 对齐周期边界（基于 billingOrigin），覆盖完整周期数 &gt; 阈值 → 简化单元；
     *       周期边界外的 gap 头尾片段走边界驱动</li>
     *   <li>优惠段（gap 之间的免费时段）走边界驱动生成明细</li>
     * </ol>
     * 核心算法（computeGaps + findSimplifiedBlock）由 {@link SimplificationSupport} 承载，
     * 与 DayNight/PERIOD 简化共用一份。CONTINUOUS 已校验无 bubble，bubbleRanges 传空，
     * effective offset = 自然 offset（相对 billingOrigin）。
     * 封顶/累计/periodCap 由 {@link ContinuousStrategy#applyCapAndAccumulate}
     * 在每个边界驱动片段内独立处理（简化段与优惠段均以 carryOverAccumulated=0 起算）。
     * <p>
     * 与 DayNight 的关键差异：周期边界基于 {@code billingOrigin}（分段起点），不是 {@code context.getBeginTime()}。
     */
    private List<BillingUnit> generateUnitsByGlobalGaps(LocalDateTime calcBegin, LocalDateTime calcEnd,
                                                        BillingContext context, CompositeTimeConfig config,
                                                        List<FreeTimeRange> freeTimeRanges, int threshold,
                                                        LocalDateTime billingOrigin) {
        // 1. 算无优惠空隙（CONTINUOUS 已校验无 bubble）
        List<SimplificationSupport.Range> gaps = SimplificationSupport.computeGaps(calcBegin, calcEnd, freeTimeRanges);

        // 2. 把每个 gap 拆为"完整周期块（可简化）+ 头尾部分片段（走边界驱动）"，优惠段单独走边界驱动
        int cycleMinutes = MINUTES_PER_CYCLE;
        BigDecimal cycleCapAmount = compositeTimeSemantics.cycleCap(config);
        List<BillingUnit> allUnits = new ArrayList<>();

        LocalDateTime promoCursor = calcBegin;
        for (SimplificationSupport.Range gap : gaps) {
            // gap 之前的优惠段（promoCursor ~ gap.begin）走边界驱动
            if (gap.begin.isAfter(promoCursor)) {
                allUnits.addAll(calculateBoundaryDriven(promoCursor, gap.begin, context, config,
                        freeTimeRanges, billingOrigin));
            }

            // CONTINUOUS 无 bubble，bubbleRanges=List.of()，effective offset = 自然 offset（相对 billingOrigin）
            SimplificationSupport.SimplifiedBlock block = SimplificationSupport.findSimplifiedBlock(
                    gap, billingOrigin, List.of(), cycleMinutes, threshold);

            if (block != null) {
                // 头部部分片段（gap.begin ~ 简化块起点）走边界驱动
                if (block.begin.isAfter(gap.begin)) {
                    allUnits.addAll(calculateBoundaryDriven(gap.begin, block.begin, context, config,
                            freeTimeRanges, billingOrigin));
                }
                // 完整周期块 → 简化单元（基于 billingOrigin 锚定周期边界）
                allUnits.add(buildSimplifiedUnitFromOrigin(block.startK, block.cycleCount, cycleCapAmount, billingOrigin));
                // 尾部部分片段（简化块终点 ~ gap.end）走边界驱动
                if (block.end.isBefore(gap.end)) {
                    allUnits.addAll(calculateBoundaryDriven(block.end, gap.end, context, config,
                            freeTimeRanges, billingOrigin));
                }
            } else {
                // 完整周期数不足阈值，整个 gap 走边界驱动
                allUnits.addAll(calculateBoundaryDriven(gap.begin, gap.end, context, config,
                        freeTimeRanges, billingOrigin));
            }

            promoCursor = gap.end;
        }
        // 末尾优惠段（最后一个 gap 之后到 calcEnd）走边界驱动
        if (promoCursor.isBefore(calcEnd)) {
            allUnits.addAll(calculateBoundaryDriven(promoCursor, calcEnd, context, config,
                    freeTimeRanges, billingOrigin));
        }

        return allUnits;
    }

    /**
     * 边界驱动路径：构造 providers + {@link BoundaryDrivenLoop#run} + {@link ContinuousStrategy#applyCapAndAccumulate}。
     * 供非简化路径与简化路径的头尾/优惠段复用。{@code begin}/{@code end} 为子区间起点/终点。
     */
    private List<BillingUnit> calculateBoundaryDriven(LocalDateTime begin, LocalDateTime end,
            BillingContext context, CompositeTimeConfig config, List<FreeTimeRange> freeTimeRanges,
            LocalDateTime billingOrigin) {
        if (!begin.isBefore(end)) {
            return new ArrayList<>();
        }

        // 边界来源：period 边界 + cycle 边界 + 免费时段起止 + 单元对齐 + calcEnd
        List<BoundaryProvider> providers = new ArrayList<>();
        // period 边界（相对位置）
        providers.add((current, e, state) -> {
            long minutesFromOrigin = Duration.between(billingOrigin, current).toMinutes();
            long positionInCycle = ((minutesFromOrigin % MINUTES_PER_CYCLE) + MINUTES_PER_CYCLE) % MINUTES_PER_CYCLE;
            long cycleCount = minutesFromOrigin / MINUTES_PER_CYCLE;
            if (minutesFromOrigin < 0 && minutesFromOrigin % MINUTES_PER_CYCLE != 0) cycleCount--;
            LocalDateTime cycleStart = billingOrigin.plusMinutes(cycleCount * MINUTES_PER_CYCLE);
            for (CompositePeriod period : config.getPeriods()) {
                long periodEndMinute = period.getEndMinute();
                if (periodEndMinute > positionInCycle) {
                    LocalDateTime boundary = cycleStart.plusMinutes(periodEndMinute);
                    if (boundary.isAfter(current) && !boundary.isAfter(e)) {
                        return List.of(boundary);
                    }
                    break;
                }
            }
            return List.of();
        });
        providers.add(BoundaryProviders.cycleEnd(billingOrigin, MINUTES_PER_CYCLE));
        providers.add(BoundaryProviders.freeRangeEdges(freeTimeRanges));
        providers.add(BoundaryProviders.calcEnd(end));

        // 边界驱动循环
        PricingState state = null; // 向后兼容：暂不需要状态管理
        List<HomogeneousSegment> segments = BoundaryDrivenLoop.run(begin, end, providers, (current, next, s) -> {
            for (FreeTimeRange range : freeTimeRanges) {
                if (!range.getBeginTime().isAfter(current) && !range.getEndTime().isBefore(next)) {
                    return new HomogeneousSegment(current, next, BigDecimal.ZERO, BigDecimal.ZERO,
                            true, range.getId(), null);
                }
            }
            long minutesFromOrigin = Duration.between(billingOrigin, current).toMinutes();
            int positionInCycle = (int) (((minutesFromOrigin % MINUTES_PER_CYCLE) + MINUTES_PER_CYCLE) % MINUTES_PER_CYCLE);
            CompositePeriod period = periodResolver.findPeriodForMinute(positionInCycle, config.getPeriods());
            BigDecimal unitPrice = crossPeriodPriceResolver.calculateUnitPrice(current, next, period);
            return new HomogeneousSegment(current, next, unitPrice, unitPrice,
                    false, null, null);
        }, state);

        // 转换为 BillingUnit（封顶 + 累计 + periodCap + compact + 截断）
        // cycleOrigin=billingOrigin，calcBegin=子区间起点；periodCap 由通用方法经 CompositeTimeSemantics 自动处理
        return ContinuousStrategy.applyCapAndAccumulate(segments, compositeTimeSemantics,
                context, config, billingOrigin, begin, null);
    }

    /**
     * 构建简化单元（周期边界基于 billingOrigin 锚定）。
     * <p>
     * 与 {@link ContinuousStrategy#buildSimplifiedUnit} 一致，但周期边界用
     * {@code billingOrigin + k * cycleMinutes} 而非 {@code calcBegin + k * cycleMinutes}
     * （CompositeTime 周期以分段起点为原点，calcBegin 不一定是周期边界）。
     */
    private BillingUnit buildSimplifiedUnitFromOrigin(int beginCycleIndex, int cycleCount,
                                                     BigDecimal cycleCapAmount, LocalDateTime billingOrigin) {
        LocalDateTime beginTime = billingOrigin.plusMinutes((long) beginCycleIndex * MINUTES_PER_CYCLE);
        LocalDateTime endTime = billingOrigin.plusMinutes((long) (beginCycleIndex + cycleCount) * MINUTES_PER_CYCLE);
        BigDecimal totalAmount = cycleCapAmount.multiply(BigDecimal.valueOf(cycleCount));

        Map<String, Object> ruleData = new HashMap<>();
        ruleData.put("cycleIndex", beginCycleIndex);
        ruleData.put("simplifiedCycleCount", cycleCount);
        ruleData.put("simplifiedCycleAmount", cycleCapAmount);
        ruleData.put("isSimplified", true);

        return BillingUnit.builder()
                .beginTime(beginTime)
                .endTime(endTime)
                .durationMinutes((int) Duration.between(beginTime, endTime).toMinutes())
                .unitPrice(cycleCapAmount)
                .originalAmount(totalAmount)
                .chargedAmount(totalAmount)
                .ruleData(ruleData)
                .build();
    }

    /**
     * 校验配置
     */
    private void validateConfig(CompositeTimeConfig config) {
        if (config.getMaxChargeOneCycle() == null || config.getMaxChargeOneCycle().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("maxChargeOneCycle 必填且必须为正数");
        }

        List<CompositePeriod> periods = config.getPeriods();
        if (periods == null || periods.isEmpty()) {
            throw new IllegalArgumentException("periods 不能为空");
        }

        validatePeriodsContinuous(periods);

        for (CompositePeriod period : periods) {
            validateNaturalPeriodsCoverage(period.getNaturalPeriods());
        }
    }

    /**
     * 校验相对时间段首尾相连
     */
    private void validatePeriodsContinuous(List<CompositePeriod> periods) {
        if (periods.get(0).getBeginMinute() != 0) {
            throw new IllegalArgumentException("第一个时间段必须从 0 分钟开始");
        }
        if (periods.get(periods.size() - 1).getEndMinute() != MINUTES_PER_CYCLE) {
            throw new IllegalArgumentException("最后一个时间段必须结束于 1440 分钟");
        }
        for (int i = 0; i < periods.size() - 1; i++) {
            if (periods.get(i).getEndMinute() != periods.get(i + 1).getBeginMinute()) {
                throw new IllegalArgumentException("相邻时间段必须首尾相连");
            }
        }
    }

    /**
     * 校验自然时段覆盖全天
     */
    private void validateNaturalPeriodsCoverage(List<NaturalPeriod> naturalPeriods) {
        if (naturalPeriods == null || naturalPeriods.isEmpty()) {
            throw new IllegalArgumentException("naturalPeriods 不能为空");
        }
        int totalCovered = 0;
        for (NaturalPeriod period : naturalPeriods) {
            if (period.getBeginMinute() < period.getEndMinute()) {
                totalCovered += period.getEndMinute() - period.getBeginMinute();
            } else {
                totalCovered += (MINUTES_PER_CYCLE - period.getBeginMinute()) + period.getEndMinute();
            }
        }
        if (totalCovered != MINUTES_PER_CYCLE) {
            throw new IllegalArgumentException("自然时段必须覆盖全天（0-1440分钟）");
        }
    }
}
