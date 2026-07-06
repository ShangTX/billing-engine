package cn.shang.charging.charge.rules.daynight;

import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.BillingContext;
import cn.shang.charging.billing.pojo.BillingSegmentResult;
import cn.shang.charging.billing.pojo.BillingUnit;
import cn.shang.charging.charge.rules.AbstractTimeBasedRule;
import cn.shang.charging.charge.rules.BoundaryProvider;
import cn.shang.charging.charge.rules.BoundaryProviders;
import cn.shang.charging.charge.rules.ContinuousStrategy;
import cn.shang.charging.charge.rules.HomogeneousSegment;
import cn.shang.charging.promotion.PromotionAggregateUtil;
import cn.shang.charging.promotion.pojo.FreeMinuteAllocationResult;
import cn.shang.charging.promotion.pojo.FreeTimeRange;
import cn.shang.charging.promotion.pojo.PromotionAggregate;
import cn.shang.charging.promotion.pojo.PromotionUsage;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * `dayNight` 规则在 CONTINUOUS 模式下的策略实现。
 * <p>
 * 承载 CONTINUOUS 语义：边界驱动切断 + 自然日封顶 + 简化计算（全局空隙实现，决策 C）。
 * 继承 {@link AbstractTimeBasedRule}（CONTINUOUS 策略基类），复用不足单元计费等公共基础设施。
 * 封顶 + 累计逻辑由通用 {@link ContinuousStrategy#applyCapAndAccumulate} 承载
 * （TODO-20260706-002 阶段3：4 份合并为 1 份），周期切换/cap 标记等差异由 {@link DayNightSemantics} 注入。
 * <p>
 * 简化路径改"全局视角算无优惠空隙"实现（TODO-20260706-002 阶段3b）：
 * 从 {@code freeTimeRanges} 直接算无优惠空隙，每个 gap 对齐周期边界，
 * 覆盖周期数 > 阈值则生成简化单元，否则与优惠段一起走边界驱动。
 * 旧切段模型（splitTimeAxis/organizeByCycle/TimeFragment/CycleFragments）已迁移到全局空隙实现。
 * <p>
 * 由 {@link DayNightRule} 门面按 calculationMode=CONTINUOUS 分派调用，不独立注册。
 */
final class DayNightContinuousStrategy extends AbstractTimeBasedRule<DayNightConfig> {

    private final DayNightPriceResolver priceResolver = new DayNightPriceResolver();
    private final DayNightSemantics dayNightSemantics = new DayNightSemantics();

    @Override
    protected boolean hasComplexFeatures(DayNightConfig config) {
        return false;
    }

    @Override
    protected boolean isSimplifiedSupported(DayNightConfig config) {
        return true;
    }

    @Override
    protected BigDecimal getCycleCapAmount(DayNightConfig config) {
        return config.getMaxChargeOneDay();
    }

    @Override
    public Class<DayNightConfig> configClass() {
        return DayNightConfig.class;
    }

    @Override
    public Set<BConstants.CalculationMode> supportedCalculationModes() {
        return EnumSet.of(BConstants.CalculationMode.CONTINUOUS);
    }

    @Override
    public BillingSegmentResult calculate(BillingContext context, DayNightConfig config, PromotionAggregate promotionAggregate) {
        validateConfig(config);

        LocalDateTime calcBegin = context.getWindow().getCalculationBegin();
        LocalDateTime calcEnd = context.getWindow().getCalculationEnd();
        int unitMinutes = config.getUnitMinutes();

        FreeMinuteAllocationResult materialized = materializeFreeMinutes(promotionAggregate, context.getWindow());
        final List<FreeTimeRange> freeTimeRanges = materialized.getFinalFreeRanges() != null
                ? materialized.getFinalFreeRanges() : List.of();

        boolean simplificationEnabled = context.getBillingConfigResolver() != null
            && isSimplificationEnabled(config, context.getBillingConfigResolver(), context);
        int threshold = context.getBillingConfigResolver() != null
            ? context.getBillingConfigResolver().getSimplifiedCycleThreshold()
            : 0;
        // FREE_MINUTES 存在时保守地视为所有周期都有优惠，不启用简化
        boolean hasFreeMinutes = promotionAggregate != null && promotionAggregate.getFreeMinutes() > 0;

        // 全局空隙实现（决策 C）：算无优惠空隙，长空隙简化，短空隙与优惠段走边界驱动
        List<BillingUnit> allUnits;
        if (simplificationEnabled && threshold > 0 && !hasFreeMinutes) {
            allUnits = generateUnitsByGlobalGaps(calcBegin, calcEnd, context, config,
                    freeTimeRanges, threshold);
        } else {
            allUnits = calculateBoundaryDriven(calcBegin, calcEnd, context, config, freeTimeRanges);
        }

        BigDecimal totalAmount = allUnits.stream()
                .map(BillingUnit::getChargedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (!allUnits.isEmpty()) {
            BillingUnit lastUnit = allUnits.get(allUnits.size() - 1);
            if (lastUnit.getDurationMinutes() < unitMinutes && lastUnit.getEndTime().equals(calcEnd)) {
                lastUnit.setIsTruncated(true);
            }
        }

        BigDecimal accumulatedAmount = BigDecimal.ZERO;
        for (BillingUnit unit : allUnits) {
            accumulatedAmount = accumulatedAmount.add(unit.getChargedAmount());
            unit.setAccumulatedAmount(accumulatedAmount);
        }

        final List<BillingUnit> finalAllUnits = allUnits;
        List<PromotionUsage> freeRangeUsages = PromotionAggregateUtil.buildFreeRangeUsages(
                freeTimeRanges, calcBegin, calcEnd,
                rangeId -> finalAllUnits.stream()
                        .filter(u -> u.isFree() && rangeId.equals(u.getFreePromotionId()))
                        .map(u -> priceResolver.determineUnitPriceForContinuous(u.getBeginTime(), u.getEndTime(), config)
                                .multiply(BigDecimal.valueOf(u.getDurationMinutes()))
                                .divide(BigDecimal.valueOf(unitMinutes), 2, RoundingMode.HALF_UP))
                        .reduce(BigDecimal.ZERO, BigDecimal::add));
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
     *   <li>每个 gap 对齐周期边界，覆盖完整周期数 &gt; 阈值 → 简化单元；周期边界外的 gap 头尾片段走边界驱动</li>
     *   <li>优惠段（gap 之间的免费时段）走边界驱动生成明细</li>
     * </ol>
     * 所有片段按时间顺序拼接，封顶/累计由 {@link ContinuousStrategy#applyCapAndAccumulate} 在每个边界驱动片段内独立处理
     * （与旧实现一致：简化段与优惠段均以 carryOverAccumulated=0 起算）。
     */
    private List<BillingUnit> generateUnitsByGlobalGaps(LocalDateTime calcBegin, LocalDateTime calcEnd,
                                                        BillingContext context, DayNightConfig config,
                                                        List<FreeTimeRange> freeTimeRanges, int threshold) {
        // 1. 算无优惠空隙（freeTimeRanges 已合并排序，此处再防御性排序）
        List<FreeTimeRange> sortedRanges = new ArrayList<>();
        for (FreeTimeRange range : freeTimeRanges) {
            if (range.getEndTime().isAfter(calcBegin) && range.getBeginTime().isBefore(calcEnd)) {
                sortedRanges.add(range);
            }
        }
        sortedRanges.sort(Comparator.comparing(FreeTimeRange::getBeginTime));

        List<Range> gaps = new ArrayList<>();
        LocalDateTime cursor = calcBegin;
        for (FreeTimeRange range : sortedRanges) {
            LocalDateTime rangeBegin = range.getBeginTime().isBefore(calcBegin) ? calcBegin : range.getBeginTime();
            LocalDateTime rangeEnd = range.getEndTime().isAfter(calcEnd) ? calcEnd : range.getEndTime();
            if (rangeBegin.isAfter(cursor)) {
                gaps.add(new Range(cursor, rangeBegin));
            }
            if (rangeEnd.isAfter(cursor)) {
                cursor = rangeEnd;
            }
        }
        if (cursor.isBefore(calcEnd)) {
            gaps.add(new Range(cursor, calcEnd));
        }

        // 2. 把每个 gap 拆为"完整周期块（可简化）+ 头尾部分片段（走边界驱动）"，优惠段单独走边界驱动
        int cycleMinutes = getCycleMinutes();
        BigDecimal cycleCapAmount = getCycleCapAmount(config);
        List<BillingUnit> allUnits = new ArrayList<>();

        LocalDateTime promoCursor = calcBegin;
        for (Range gap : gaps) {
            // gap 之前的优惠段（promoCursor ~ gap.begin）走边界驱动
            if (gap.begin.isAfter(promoCursor)) {
                allUnits.addAll(calculateBoundaryDriven(promoCursor, gap.begin, context, config, freeTimeRanges));
            }

            long beginOffset = Duration.between(calcBegin, gap.begin).toMinutes();
            long endOffset = Duration.between(calcBegin, gap.end).toMinutes();
            // 第一个 >= gap.begin 的周期边界索引
            int startK = beginOffset % cycleMinutes == 0
                    ? (int) (beginOffset / cycleMinutes)
                    : (int) (beginOffset / cycleMinutes) + 1;
            // 第一个 <= gap.end 的周期边界索引
            int endK = (int) (endOffset / cycleMinutes);

            if (endK - startK > threshold) {
                // 头部部分片段（gap.begin ~ 周期边界）走边界驱动
                if (startK * cycleMinutes > beginOffset) {
                    LocalDateTime blockStart = calcBegin.plusMinutes((long) startK * cycleMinutes);
                    allUnits.addAll(calculateBoundaryDriven(gap.begin, blockStart, context, config, freeTimeRanges));
                }
                // 完整周期块 → 简化单元
                allUnits.add(buildSimplifiedUnit(startK, endK - startK, cycleCapAmount, calcBegin));
                // 尾部部分片段（周期边界 ~ gap.end）走边界驱动
                if ((long) endK * cycleMinutes < endOffset) {
                    LocalDateTime blockEnd = calcBegin.plusMinutes((long) endK * cycleMinutes);
                    allUnits.addAll(calculateBoundaryDriven(blockEnd, gap.end, context, config, freeTimeRanges));
                }
            } else {
                // 完整周期数不足阈值，整个 gap 走边界驱动
                allUnits.addAll(calculateBoundaryDriven(gap.begin, gap.end, context, config, freeTimeRanges));
            }

            promoCursor = gap.end;
        }
        // 末尾优惠段（最后一个 gap 之后到 calcEnd）走边界驱动
        if (promoCursor.isBefore(calcEnd)) {
            allUnits.addAll(calculateBoundaryDriven(promoCursor, calcEnd, context, config, freeTimeRanges));
        }

        return allUnits;
    }

    /**
     * 边界驱动路径：构造 providers + {@link #runBoundaryDrivenLoop} + {@link ContinuousStrategy#applyCapAndAccumulate}。
     * 供非简化路径与简化路径的头尾/优惠段复用。
     */
    private List<BillingUnit> calculateBoundaryDriven(LocalDateTime begin, LocalDateTime end,
            BillingContext context, DayNightConfig config, List<FreeTimeRange> freeTimeRanges) {
        if (!begin.isBefore(end)) {
            return new ArrayList<>();
        }
        LocalDateTime cycleOriginBegin = context.getBeginTime();
        int dayBeginMin = config.getDayBeginMinute();
        int dayEndMin = config.getDayEndMinute();
        int unitMinutes = config.getUnitMinutes();

        List<BoundaryProvider> providers = new ArrayList<>();
        providers.add(BoundaryProviders.cycleEnd(cycleOriginBegin, getCycleMinutes()));
        providers.add((current, e) -> {
            List<LocalDateTime> result = new ArrayList<>();
            LocalDateTime day = current.toLocalDate().atStartOfDay();
            LocalDateTime candidateBegin = day.plusMinutes(dayBeginMin);
            if (!candidateBegin.isAfter(current)) {
                candidateBegin = candidateBegin.plusDays(1);
            }
            while (!candidateBegin.isAfter(e)) {
                LocalDateTime candidateEnd;
                if (dayBeginMin < dayEndMin) {
                    candidateEnd = candidateBegin.toLocalDate().atStartOfDay().plusMinutes(dayEndMin);
                } else {
                    candidateEnd = candidateBegin.toLocalDate().plusDays(1).atStartOfDay().plusMinutes(dayEndMin);
                }
                if (candidateBegin.isAfter(current) && !candidateBegin.isAfter(e)) {
                    result.add(candidateBegin);
                }
                if (candidateEnd.isAfter(current) && !candidateEnd.isAfter(e)) {
                    result.add(candidateEnd);
                }
                candidateBegin = candidateBegin.plusDays(1);
            }
            return result;
        });
        providers.add(BoundaryProviders.freeRangeEdges(freeTimeRanges));
        providers.add((current, e) -> {
            List<LocalDateTime> result = new ArrayList<>();
            LocalDateTime next = current.plusMinutes(unitMinutes);
            while (!next.isAfter(e)) {
                result.add(next);
                next = next.plusMinutes(unitMinutes);
            }
            return result;
        });
        providers.add(BoundaryProviders.calcEnd(end));

        List<HomogeneousSegment> segments = runBoundaryDrivenLoop(begin, end, providers,
                (current, next) -> buildSegmentForDayNight(current, next, config, freeTimeRanges, end));

        return ContinuousStrategy.applyCapAndAccumulate(segments, dayNightSemantics, context, config,
                cycleOriginBegin, begin, null);
    }

    private void validateConfig(DayNightConfig config) {
        if (config.getMaxChargeOneDay() == null) {
            throw new IllegalArgumentException("maxChargeOneDay is required");
        }
        if (config.getMaxChargeOneDay().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("maxChargeOneDay must be positive");
        }
        if (config.getUnitMinutes() == null || config.getUnitMinutes() <= 0) {
            throw new IllegalArgumentException("unitMinutes must be positive");
        }
        if (config.getDayUnitPrice() == null || config.getDayUnitPrice().compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("dayUnitPrice must be non-negative");
        }
        if (config.getNightUnitPrice() == null || config.getNightUnitPrice().compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("nightUnitPrice must be non-negative");
        }
        if (config.getDayBeginMinute() == null || config.getDayEndMinute() == null) {
            throw new IllegalArgumentException("dayBeginMinute and dayEndMinute are required");
        }
        if (config.getBlockWeight() == null ||
            config.getBlockWeight().compareTo(BigDecimal.ZERO) < 0 ||
            config.getBlockWeight().compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("blockWeight must be between 0 and 1");
        }
    }

    private HomogeneousSegment buildSegmentForDayNight(LocalDateTime current,
                                                       LocalDateTime next,
                                                       DayNightConfig config,
                                                       List<FreeTimeRange> freeTimeRanges,
                                                       LocalDateTime calcEnd) {
        LocalDateTime pricingEnd = current.plusMinutes(config.getUnitMinutes());
        if (pricingEnd.isAfter(calcEnd)) {
            pricingEnd = calcEnd;
        }
        for (FreeTimeRange range : freeTimeRanges) {
            if (!range.getBeginTime().isAfter(current) && !range.getEndTime().isBefore(next)) {
                return new HomogeneousSegment(current, next, BigDecimal.ZERO, BigDecimal.ZERO,
                        true, range.getId(), null);
            }
        }
        BigDecimal unitPrice = priceResolver.determineUnitPriceForContinuous(current, pricingEnd, config);
        return new HomogeneousSegment(current, next, unitPrice, unitPrice,
                false, null, null);
    }

    /** 时间区间（用于全局空隙计算）。 */
    private static final class Range {
        final LocalDateTime begin;
        final LocalDateTime end;

        Range(LocalDateTime begin, LocalDateTime end) {
            this.begin = begin;
            this.end = end;
        }
    }
}
