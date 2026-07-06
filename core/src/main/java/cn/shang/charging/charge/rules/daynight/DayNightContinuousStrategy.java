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
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * `dayNight` 规则在 CONTINUOUS 模式下的策略实现。
 * <p>
 * 承载 CONTINUOUS 语义：边界驱动切断 + 24h 周期封顶 + 简化计算。
 * 继承 {@link AbstractTimeBasedRule}（CONTINUOUS 策略基类），复用时间轴切分、周期组织、简化单元、
 * 不足单元计费等公共基础设施。封顶 + 累计逻辑由通用 {@link ContinuousStrategy#applyCapAndAccumulate}
 * 承载（TODO-20260706-002 阶段3：4 份合并为 1 份），周期切换/cap 标记等差异由 {@link DayNightSemantics} 注入。
 * <p>
 * 由 {@link DayNightRule} 门面按 calculationMode=CONTINUOUS 分派调用，不独立注册。
 */
final class DayNightContinuousStrategy extends AbstractTimeBasedRule<DayNightConfig> {

    private final DayNightPriceResolver priceResolver = new DayNightPriceResolver();
    private final DayNightCycleStateManager cycleStateManager = new DayNightCycleStateManager();
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
        LocalDateTime cycleOriginBegin = context.getBeginTime();
        int unitMinutes = config.getUnitMinutes();

        RuleState state = initializeState(calcBegin);

        FreeMinuteAllocationResult materialized = materializeFreeMinutes(promotionAggregate, context.getWindow());
        final List<FreeTimeRange> freeTimeRanges = materialized.getFinalFreeRanges() != null
                ? materialized.getFinalFreeRanges() : List.of();

        boolean simplificationEnabled = context.getBillingConfigResolver() != null
            && isSimplificationEnabled(config, context.getBillingConfigResolver(), context);
        int threshold = context.getBillingConfigResolver() != null
            ? context.getBillingConfigResolver().getSimplifiedCycleThreshold()
            : 0;
        long totalMinutes = Duration.between(calcBegin, calcEnd).toMinutes();
        int totalCycles = (int) (totalMinutes / getCycleMinutes());

        List<BillingUnit> allUnits;
        boolean usedSimplification = false;

        if (simplificationEnabled && totalCycles > threshold) {
            List<TimeFragment> fragments = splitTimeAxis(calcBegin, calcEnd, freeTimeRanges);
            List<CycleFragments> cycles = organizeByCycle(calcBegin, calcEnd, fragments, cycleOriginBegin);
            Set<Integer> cyclesWithPromotion = findCyclesWithPromotion(calcBegin, calcEnd, promotionAggregate);
            if (cyclesWithPromotion != null && !cycles.isEmpty()) {
                allUnits = generateSimplifiedUnitsForContinuous(cycles, cyclesWithPromotion,
                    threshold, config, calcBegin, cycleOriginBegin, state);
                usedSimplification = true;
            } else {
                allUnits = new ArrayList<>();
            }
        } else {
            allUnits = new ArrayList<>();
        }

        if (!usedSimplification) {
            int dayBeginMin = config.getDayBeginMinute();
            int dayEndMin = config.getDayEndMinute();

            List<BoundaryProvider> providers = new ArrayList<>();
            providers.add(BoundaryProviders.cycleEnd(cycleOriginBegin, getCycleMinutes()));
            providers.add((current, end) -> {
                List<LocalDateTime> result = new ArrayList<>();
                LocalDateTime day = current.toLocalDate().atStartOfDay();
                LocalDateTime candidateBegin = day.plusMinutes(dayBeginMin);
                if (!candidateBegin.isAfter(current)) {
                    candidateBegin = candidateBegin.plusDays(1);
                }
                while (!candidateBegin.isAfter(end)) {
                    LocalDateTime candidateEnd;
                    if (dayBeginMin < dayEndMin) {
                        candidateEnd = candidateBegin.toLocalDate().atStartOfDay().plusMinutes(dayEndMin);
                    } else {
                        candidateEnd = candidateBegin.toLocalDate().plusDays(1).atStartOfDay().plusMinutes(dayEndMin);
                    }
                    if (candidateBegin.isAfter(current) && !candidateBegin.isAfter(end)) {
                        result.add(candidateBegin);
                    }
                    if (candidateEnd.isAfter(current) && !candidateEnd.isAfter(end)) {
                        result.add(candidateEnd);
                    }
                    candidateBegin = candidateBegin.plusDays(1);
                }
                return result;
            });
            providers.add(BoundaryProviders.freeRangeEdges(freeTimeRanges));
            providers.add((current, end) -> {
                List<LocalDateTime> result = new ArrayList<>();
                LocalDateTime next = current.plusMinutes(unitMinutes);
                while (!next.isAfter(end)) {
                    result.add(next);
                    next = next.plusMinutes(unitMinutes);
                }
                return result;
            });
            providers.add(BoundaryProviders.calcEnd(calcEnd));

            List<HomogeneousSegment> segments = runBoundaryDrivenLoop(calcBegin, calcEnd, providers,
                    (current, next) -> buildSegmentForDayNight(current, next, config, freeTimeRanges, calcEnd));

            allUnits = ContinuousStrategy.applyCapAndAccumulate(segments, dayNightSemantics, context, config,
                    cycleOriginBegin, calcBegin, null);
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

    private List<BillingUnit> generateUnitsForSingleCycle(
            int cycleIndex,
            LocalDateTime calcBegin,
            LocalDateTime calcEnd,
            DayNightConfig config,
            List<FreeTimeRange> freeTimeRanges) {

        LocalDateTime cycleStart = getCycleBoundary(cycleIndex, calcBegin);
        LocalDateTime cycleEnd = getCycleBoundary(cycleIndex + 1, calcBegin);

        if (cycleStart.isBefore(calcBegin)) {
            cycleStart = calcBegin;
        }
        if (cycleEnd.isAfter(calcEnd)) {
            cycleEnd = calcEnd;
        }

        if (!cycleStart.isBefore(cycleEnd)) {
            return List.of();
        }

        List<TimeFragment> fragments = splitTimeAxis(cycleStart, cycleEnd, freeTimeRanges);
        CycleFragments cycle = new CycleFragments(cycleStart, cycleEnd);
        cycle.fragments.addAll(fragments);
        List<BillingUnit> units = generateUnitsForCycle(cycle, config);

        for (BillingUnit unit : units) {
            unit.setRuleData(cycleIndex);
        }

        return units;
    }

    private List<BillingUnit> generateSimplifiedUnitsForContinuous(
            List<CycleFragments> cycles,
            Set<Integer> cyclesWithPromotion,
            int threshold,
            DayNightConfig config,
            LocalDateTime calcBegin,
            LocalDateTime cycleOriginBegin,
            RuleState state) {

        List<BillingUnit> allUnits = new ArrayList<>();
        BigDecimal cycleCapAmount = getCycleCapAmount(config);
        int cycleMinutes = getCycleMinutes();

        int consecutiveSimplified = 0;
        int simplifiedStartIndex = -1;
        BigDecimal carryOverAccumulated = BigDecimal.ZERO;

        for (int cycleIdx = 0; cycleIdx < cycles.size(); cycleIdx++) {
            CycleFragments cycle = cycles.get(cycleIdx);
            int cycleIndex = (int) Duration.between(cycleOriginBegin, cycle.cycleStart).toMinutes() / cycleMinutes;

            boolean hasPromotion = cyclesWithPromotion.contains(cycleIndex);

            if (!hasPromotion) {
                if (consecutiveSimplified == 0) {
                    simplifiedStartIndex = cycleIndex;
                }
                consecutiveSimplified++;
            } else {
                if (consecutiveSimplified > threshold) {
                    BillingUnit simplifiedUnit = buildSimplifiedUnit(
                        simplifiedStartIndex, consecutiveSimplified, cycleCapAmount, calcBegin);
                    allUnits.add(simplifiedUnit);
                    carryOverAccumulated = BigDecimal.ZERO;
                } else if (consecutiveSimplified > 0) {
                    for (int i = simplifiedStartIndex; i < simplifiedStartIndex + consecutiveSimplified; i++) {
                        List<BillingUnit> cycleUnits = generateUnitsForSingleCycle(i, calcBegin, cycle.cycleEnd, config, List.of());
                        allUnits.addAll(cycleUnits);
                    }
                }
                consecutiveSimplified = 0;

                List<BillingUnit> cycleUnits = generateUnitsForCycle(cycle, config);
                cycleStateManager.applyDailyCapWithCarryOver(
                        cycleUnits,
                        config,
                        carryOverAccumulated);
                allUnits.addAll(cycleUnits);
                carryOverAccumulated = BigDecimal.ZERO;
            }
        }

        if (consecutiveSimplified > threshold) {
            BillingUnit simplifiedUnit = buildSimplifiedUnit(
                simplifiedStartIndex, consecutiveSimplified, cycleCapAmount, calcBegin);
            allUnits.add(simplifiedUnit);
        } else if (consecutiveSimplified > 0) {
            for (int i = simplifiedStartIndex; i < simplifiedStartIndex + consecutiveSimplified; i++) {
                List<BillingUnit> cycleUnits = generateUnitsForSingleCycle(i, calcBegin, cycles.get(cycles.size() - 1).cycleEnd, config, List.of());
                allUnits.addAll(cycleUnits);
            }
        }

        return allUnits;
    }

    @Override
    protected TimeFragment createFragment(LocalDateTime beginTime, LocalDateTime endTime) {
        return new TimeFragment(beginTime, endTime);
    }

    private List<BillingUnit> generateUnitsForCycle(CycleFragments cycle, DayNightConfig config) {
        List<BillingUnit> units = new ArrayList<>();
        int unitMinutes = config.getUnitMinutes();
        for (TimeFragment fragment : cycle.fragments) {
            if (fragment.isFree) {
                BillingUnit unit = BillingUnit.builder()
                        .beginTime(fragment.beginTime)
                        .endTime(fragment.endTime)
                        .durationMinutes((int) Duration.between(fragment.beginTime, fragment.endTime).toMinutes())
                        .unitPrice(BigDecimal.ZERO)
                        .originalAmount(BigDecimal.ZERO)
                        .free(true)
                        .freePromotionId(fragment.freePromotionId)
                        .chargedAmount(BigDecimal.ZERO)
                        .build();
                units.add(unit);
            } else {
                LocalDateTime current = fragment.beginTime;
                while (current.isBefore(fragment.endTime)) {
                    LocalDateTime pricingEnd = resolvePricingEnd(current, unitMinutes, cycle.cycleEnd);
                    LocalDateTime unitEnd = pricingEnd;
                    if (unitEnd.isAfter(fragment.endTime)) {
                        unitEnd = fragment.endTime;
                    }

                    int duration = (int) Duration.between(current, unitEnd).toMinutes();

                    BigDecimal unitPrice = determineUnitPriceForContinuous(current, pricingEnd, config);

                    BigDecimal originalAmount = unitPrice;

                    BillingUnit unit = BillingUnit.builder()
                            .beginTime(current)
                            .endTime(unitEnd)
                            .durationMinutes(duration)
                            .unitPrice(unitPrice)
                            .originalAmount(originalAmount)
                            .free(false)
                            .chargedAmount(originalAmount)
                            .build();

                    units.add(unit);
                    current = unitEnd;
                }
            }
        }

        return units;
    }

    private LocalDateTime resolvePricingEnd(LocalDateTime unitBegin, int unitMinutes, LocalDateTime cycleEnd) {
        LocalDateTime pricingEnd = unitBegin.plusMinutes(unitMinutes);
        if (pricingEnd.isAfter(cycleEnd)) {
            return cycleEnd;
        }
        return pricingEnd;
    }

    private BigDecimal determineUnitPriceForContinuous(LocalDateTime begin, LocalDateTime end, DayNightConfig config) {
        return priceResolver.determineUnitPriceForContinuous(begin, end, config);
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
}
