package cn.shang.charging.charge.rules.compositetime;

import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.BillingContext;
import cn.shang.charging.billing.pojo.BillingSegmentResult;
import cn.shang.charging.billing.pojo.BillingUnit;
import cn.shang.charging.billing.pojo.CalculationWindow;
import cn.shang.charging.charge.rules.AbstractTimeBasedRule;
import cn.shang.charging.charge.rules.BillingRule;
import cn.shang.charging.charge.rules.BoundaryProvider;
import cn.shang.charging.charge.rules.BoundaryProviders;
import cn.shang.charging.charge.rules.HomogeneousSegment;
import cn.shang.charging.promotion.PromotionAggregateUtil;
import cn.shang.charging.promotion.pojo.FreeMinuteAllocationResult;
import cn.shang.charging.promotion.pojo.FreeTimeRange;
import cn.shang.charging.promotion.pojo.PromotionAggregate;
import cn.shang.charging.promotion.pojo.PromotionUsage;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 混合时间计费规则
 * <p>
 * 核心逻辑：
 * 1. 从计费起点开始，按 24 小时划分周期
 * 2. 每个周期内按相对时间段划分，每个时间段可有不同的单元长度
 * 3. 每个时间段内按自然时段配置不同的价格
 * 4. 支持时间段独立封顶和周期封顶
 */
public class CompositeTimeRule extends AbstractTimeBasedRule<CompositeTimeConfig> {

    private static final int MINUTES_PER_DAY = 1440;

    private final CompositeTimeContinuousCalculator continuousCalculator = new CompositeTimeContinuousCalculator();
    private final CompositeTimePeriodResolver periodResolver = new CompositeTimePeriodResolver();
    private final CompositeTimeCrossPeriodPriceResolver crossPeriodPriceResolver = new CompositeTimeCrossPeriodPriceResolver();
    private final CompositeTimeContinuousCapHandler continuousCapHandler = new CompositeTimeContinuousCapHandler();
    private final CompositeTimeSimplifiedCycleStateManager simplifiedCycleStateManager = new CompositeTimeSimplifiedCycleStateManager();

    @Override
    protected boolean hasComplexFeatures(CompositeTimeConfig config) {
        // CompositeTimeRule 支持时间段独立封顶
        if (config.getPeriods() != null) {
            for (CompositePeriod period : config.getPeriods()) {
                if (period.getMaxCharge() != null && period.getMaxCharge().compareTo(BigDecimal.ZERO) > 0) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    protected boolean isSimplifiedSupported(CompositeTimeConfig config) {
        // 当存在时间段独立封顶时不支持简化计算
        return !hasComplexFeatures(config);
    }

    @Override
    protected BigDecimal getCycleCapAmount(CompositeTimeConfig config) {
        return config.getMaxChargeOneCycle();
    }

    @Override
    public Class<CompositeTimeConfig> configClass() {
        return CompositeTimeConfig.class;
    }

    @Override
    public Set<BConstants.CalculationMode> supportedCalculationModes() {
        return Set.of(BConstants.CalculationMode.CONTINUOUS);
    }

    @Override
    public BillingSegmentResult calculate(BillingContext context,
                                          CompositeTimeConfig ruleConfig,
                                          PromotionAggregate promotionAggregate) {
        validateConfig(ruleConfig);
        return continuousCalculator.calculate(this, context, ruleConfig, promotionAggregate);
    }

    /**
     * 生成单个周期的计费单元
     */
    private List<BillingUnit> generateUnitsForSingleCycle(
            int cycleIndex,
            LocalDateTime calcBegin,
            LocalDateTime calcEnd,
            CompositeTimeConfig config,
            List<FreeTimeRange> freeTimeRanges,
            LocalDateTime billingOrigin) {

        LocalDateTime cycleStart = getCycleBoundary(cycleIndex, calcBegin);
        LocalDateTime cycleEnd = getCycleBoundary(cycleIndex + 1, calcBegin);

        // 限制在计算窗口内
        if (cycleStart.isBefore(calcBegin)) {
            cycleStart = calcBegin;
        }
        if (cycleEnd.isAfter(calcEnd)) {
            cycleEnd = calcEnd;
        }

        if (!cycleStart.isBefore(cycleEnd)) {
            return List.of();
        }

        // CONTINUOUS 风格：按免费时段切段后用 generateUnitsForCycle 生成
        // 简化路径仅用于无优惠周期（freeTimeRanges 为空），切段后只有一段非免费片段
        List<TimeFragment> fragments = splitTimeAxis(cycleStart, cycleEnd, freeTimeRanges);
        CycleFragments cycle = new CycleFragments(cycleStart, cycleEnd);
        cycle.fragments.addAll(fragments);
        List<BillingUnit> units = generateUnitsForCycle(cycle, config, billingOrigin);

        // 应用周期封顶
        applyCycleCapWithCarryOver(toCycleUnits(cycleStart, cycleEnd, units), config.getMaxChargeOneCycle(), BigDecimal.ZERO);

        // 为每个单元设置周期索引
        for (BillingUnit unit : units) {
            unit.setRuleData(cycleIndex);
        }

        return units;
    }

    /**
     * 把 BillingUnit 列表包装为 CycleUnits（供 applyCycleCapWithCarryOver 使用）。
     */
    private CycleUnits toCycleUnits(LocalDateTime cycleStart, LocalDateTime cycleEnd, List<BillingUnit> units) {
        CycleUnits cycle = new CycleUnits(cycleStart, cycleEnd);
        cycle.units.addAll(units);
        return cycle;
    }

    /**
     * CONTINUOUS 模式计算 - "气泡抽出"模型
     * <p>
     * 核心思想：
     * 1. 免费时段像气泡一样从时间轴中"抽出"
     * 2. 气泡前后的计费时间在相对位置上直接连接
     * 3. 每个片段的相对位置从原始计费起点开始计算
     * <p>
     * 示例：
     * 免费时段：10:30-11:30
     * 计费起点：08:00
     * 相对周期 1：0-120 分钟
     * 相对周期 2：120-1440 分钟
     * <p>
     * 片段 1：08:00-10:30
     * ├── 相对位置：0-150 分钟（从原始计费起点计算）
     * ├── 0-120 分钟：相对周期 1
     * └── 120-150 分钟：相对周期 2
     * <p>
     * 片段 2：11:30-12:00
     * ├── 相对位置：210-240 分钟（跳过免费时段，仍从 08:00 计算）
     * └── 210 > 120，所以在相对周期 2
     */
    BillingSegmentResult calculateContinuousInternal(BillingContext context,
                                                      CompositeTimeConfig config,
                                                      PromotionAggregate promotionAggregate) {
        // 获取计算窗口
        CalculationWindow window = context.getWindow();
        LocalDateTime calcBegin = window.getCalculationBegin();
        LocalDateTime calcEnd = window.getCalculationEnd();

        // 获取计费起点（从分段信息获取）
        LocalDateTime billingOrigin = context.getSegment().getBeginTime();

        // 初始化单次计算周期跟踪状态
        RuleState state = AbstractTimeBasedRule.RuleState.builder()
                .cycleIndex(0)
                .cycleAccumulated(BigDecimal.ZERO)
                .cycleBoundary(billingOrigin.plusMinutes(getCycleMinutes()))
                .build();

        // 时段化 FREE_MINUTES（TODO-20260702-004：从 PromotionEngine 下放到策略侧）
        FreeMinuteAllocationResult materialized = materializeFreeMinutes(promotionAggregate, window);
        List<FreeTimeRange> freeTimeRanges = materialized.getFinalFreeRanges() != null
                ? materialized.getFinalFreeRanges() : List.of();

        // 按免费时段边界切分时间轴
        List<TimeFragment> fragments = splitTimeAxis(calcBegin, calcEnd, freeTimeRanges);

        // 按周期组织片段
        List<CycleFragments> cycles = organizeByCycle(calcBegin, calcEnd, fragments, billingOrigin);

        // 检查是否启用简化计算
        List<BillingUnit> allUnits = new ArrayList<>();
        boolean simplificationEnabled = context.getBillingConfigResolver() != null
            && isSimplificationEnabled(config, context.getBillingConfigResolver(), context);
        int threshold = context.getBillingConfigResolver() != null
            ? context.getBillingConfigResolver().getSimplifiedCycleThreshold()
            : 0;

        if (simplificationEnabled && cycles.size() > threshold) {
            Set<Integer> cyclesWithPromotion = findCyclesWithPromotion(calcBegin, calcEnd, promotionAggregate);

            if (cyclesWithPromotion != null) {
                // 使用简化计算
                allUnits = generateSimplifiedUnitsForContinuous(cycles, cyclesWithPromotion,
                    threshold, config, calcBegin, billingOrigin, state);
            }
        }

        // 如果未使用简化，使用边界驱动循环
        if (allUnits.isEmpty()) {
            BigDecimal carryOverAccumulated = BigDecimal.ZERO;
            BigDecimal maxCharge = config.getMaxChargeOneCycle();

            // 边界来源：period 边界 + cycle 边界 + 免费时段起止 + 单元对齐 + calcEnd
            List<BoundaryProvider> providers = new ArrayList<>();
            // period 边界（相对位置）
            providers.add((current, end) -> {
                long minutesFromOrigin = Duration.between(billingOrigin, current).toMinutes();
                long positionInCycle = ((minutesFromOrigin % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY;
                long cycleCount = minutesFromOrigin / MINUTES_PER_DAY;
                if (minutesFromOrigin < 0 && minutesFromOrigin % MINUTES_PER_DAY != 0) cycleCount--;
                LocalDateTime cycleStart = billingOrigin.plusMinutes(cycleCount * MINUTES_PER_DAY);
                for (CompositePeriod period : config.getPeriods()) {
                    long periodEndMinute = period.getEndMinute();
                    if (periodEndMinute > positionInCycle) {
                        LocalDateTime boundary = cycleStart.plusMinutes(periodEndMinute);
                        if (boundary.isAfter(current) && !boundary.isAfter(end)) {
                            return List.of(boundary);
                        }
                        break;
                    }
                }
                return List.of();
            });
            providers.add(BoundaryProviders.cycleEnd(billingOrigin, MINUTES_PER_DAY));
            providers.add(BoundaryProviders.freeRangeEdges(freeTimeRanges));
            // 单元对齐（当前 period 的 unitMinutes）
            providers.add((current, end) -> {
                long minutesFromOrigin = Duration.between(billingOrigin, current).toMinutes();
                long positionInCycle = ((minutesFromOrigin % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY;
                CompositePeriod period = periodResolver.findPeriodForMinute((int) positionInCycle, config.getPeriods());
                LocalDateTime next = current.plusMinutes(period.getUnitMinutes());
                if (next.isAfter(current) && !next.isAfter(end)) {
                    return List.of(next);
                }
                return List.of();
            });
            providers.add(BoundaryProviders.calcEnd(calcEnd));

            // 边界驱动循环
            List<HomogeneousSegment> segments = runBoundaryDrivenLoop(calcBegin, calcEnd, providers, (current, next) -> {
                for (FreeTimeRange range : freeTimeRanges) {
                    if (!range.getBeginTime().isAfter(current) && !range.getEndTime().isBefore(next)) {
                        return new HomogeneousSegment(current, next, BigDecimal.ZERO, BigDecimal.ZERO,
                                true, range.getId(), null);
                    }
                }
                long minutesFromOrigin = Duration.between(billingOrigin, current).toMinutes();
                int positionInCycle = (int) (((minutesFromOrigin % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY);
                CompositePeriod period = periodResolver.findPeriodForMinute(positionInCycle, config.getPeriods());
                BigDecimal unitPrice = crossPeriodPriceResolver.calculateUnitPrice(current, next, period);
                return new HomogeneousSegment(current, next, unitPrice, unitPrice,
                        false, null, null);
            });

            // 转换为 BillingUnit（封顶 + 累计 + compact + 截断）
            ContinuousResult result = applyCapAndAccumulate(segments, maxCharge, carryOverAccumulated,
                    context, config, billingOrigin, calcBegin);
            allUnits = result.units;
            // 状态续算已下线（CONTINUE 移除），不再输出周期状态
        }

        BigDecimal totalAmount = allUnits.stream()
                .map(BillingUnit::getChargedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);


        // 标记最后一个单元是否被截断
        if (!allUnits.isEmpty()) {
            BillingUnit lastUnit = allUnits.get(allUnits.size() - 1);
            // 获取最后一个单元对应的单元长度
            int minutesFromBillingOrigin = (int) Duration.between(billingOrigin, lastUnit.getBeginTime()).toMinutes();
            int positionInCycle = minutesFromBillingOrigin % MINUTES_PER_DAY;
            if (positionInCycle < 0) {
                positionInCycle += MINUTES_PER_DAY;
            }
            CompositePeriod period = periodResolver.findPeriodForMinute(positionInCycle, config.getPeriods());
            int unitMinutes = period.getUnitMinutes();
            if (lastUnit.getDurationMinutes() < unitMinutes && lastUnit.getEndTime().equals(calcEnd)) {
                lastUnit.setIsTruncated(true);
            }
        }

        // 产出 FREE_RANGE 的 PromotionUsage（CONTINUOUS 免费单元 originalAmount=0）
        final List<BillingUnit> finalAllUnits = allUnits;
        List<PromotionUsage> freeRangeUsages = PromotionAggregateUtil.buildFreeRangeUsages(
                freeTimeRanges, calcBegin, calcEnd,
                rangeId -> finalAllUnits.stream()
                        .filter(u -> u.isFree() && rangeId.equals(u.getFreePromotionId()))
                        .map(BillingUnit::getOriginalAmount)
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
     * 为 CONTINUOUS 模式生成简化单元
     */
    private List<BillingUnit> generateSimplifiedUnitsForContinuous(
            List<CycleFragments> cycles,
            Set<Integer> cyclesWithPromotion,
            int threshold,
            CompositeTimeConfig config,
            LocalDateTime calcBegin,
            LocalDateTime billingOrigin,
            RuleState state) {

        List<BillingUnit> allUnits = new ArrayList<>();
        BigDecimal cycleCapAmount = getCycleCapAmount(config);
        int cycleMinutes = getCycleMinutes();

        int consecutiveSimplified = 0;
        int simplifiedStartIndex = -1;
        BigDecimal carryOverAccumulated = BigDecimal.ZERO;

        for (int cycleIdx = 0; cycleIdx < cycles.size(); cycleIdx++) {
            CycleFragments cycle = cycles.get(cycleIdx);
            // 计算周期索引（基于原始计费起点）
            int cycleIndex = (int) Duration.between(billingOrigin, cycle.cycleStart).toMinutes() / cycleMinutes;

            boolean hasPromotion = cyclesWithPromotion.contains(cycleIndex);

            if (!hasPromotion) {
                // 无优惠周期，累计
                if (consecutiveSimplified == 0) {
                    simplifiedStartIndex = cycleIndex;
                }
                consecutiveSimplified++;
            } else {
                // 处理之前的简化段
                if (consecutiveSimplified > threshold) {
                    BillingUnit simplifiedUnit = buildSimplifiedUnit(
                        simplifiedStartIndex, consecutiveSimplified, cycleCapAmount, calcBegin);
                    allUnits.add(simplifiedUnit);
                    carryOverAccumulated = BigDecimal.ZERO; // 简化后重置
                } else if (consecutiveSimplified > 0) {
                    // 不足阈值，正常生成
                    for (int i = simplifiedStartIndex; i < simplifiedStartIndex + consecutiveSimplified; i++) {
                        List<FreeTimeRange> emptyRanges = List.of();
                        List<BillingUnit> cycleUnits = generateUnitsForSingleCycle(i, calcBegin, cycle.cycleEnd, config, emptyRanges, billingOrigin);
                        allUnits.addAll(cycleUnits);
                    }
                }
                consecutiveSimplified = 0;

                // 生成当前有优惠周期的详细单元
                List<BillingUnit> cycleUnits = generateUnitsForCycle(cycle, config, billingOrigin);
                continuousCapHandler.applyWithCarryOver(
                        cycleUnits,
                        config.getMaxChargeOneCycle(),
                        carryOverAccumulated
                );
                allUnits.addAll(cycleUnits);
                carryOverAccumulated = BigDecimal.ZERO;
            }
        }

        // 处理最后的简化段
        if (consecutiveSimplified > threshold) {
            BillingUnit simplifiedUnit = buildSimplifiedUnit(
                simplifiedStartIndex, consecutiveSimplified, cycleCapAmount, calcBegin);
            allUnits.add(simplifiedUnit);
        } else if (consecutiveSimplified > 0) {
            for (int i = simplifiedStartIndex; i < simplifiedStartIndex + consecutiveSimplified; i++) {
                List<FreeTimeRange> emptyRanges = List.of();
                List<BillingUnit> cycleUnits = generateUnitsForSingleCycle(i, calcBegin, cycles.get(cycles.size() - 1).cycleEnd, config, emptyRanges, billingOrigin);
                allUnits.addAll(cycleUnits);
            }
        }

        return allUnits;
    }

    /**
     * CONTINUOUS 边界驱动结果：单元列表 + 最后一个周期的累计金额。
     */
    private static final class ContinuousResult {
        final List<BillingUnit> units;
        final BigDecimal lastCycleAccumulated;

        ContinuousResult(List<BillingUnit> units, BigDecimal lastCycleAccumulated) {
            this.units = units;
            this.lastCycleAccumulated = lastCycleAccumulated;
        }
    }

    /**
     * 把同质段列表转换为 BillingUnit，并应用周期封顶、累计金额、compact 合并、截断标记。
     */
    private ContinuousResult applyCapAndAccumulate(List<HomogeneousSegment> segments,
                                                    BigDecimal maxCharge,
                                                    BigDecimal carryOverAccumulated,
                                                    BillingContext context,
                                                    CompositeTimeConfig config,
                                                    LocalDateTime billingOrigin,
                                                    LocalDateTime calcBegin) {
        List<BillingUnit> units = new ArrayList<>();
        if (segments.isEmpty()) {
            return new ContinuousResult(units, carryOverAccumulated != null ? carryOverAccumulated : BigDecimal.ZERO);
        }

        LocalDateTime calcEnd = context.getWindow().getCalculationEnd();
        BigDecimal cycleAccumulated = carryOverAccumulated != null ? carryOverAccumulated : BigDecimal.ZERO;
        long calcBeginOffset = Duration.between(billingOrigin, calcBegin).toMinutes();
        long nextCycleBoundaryOffset = ((calcBeginOffset / MINUTES_PER_DAY) + 1) * MINUTES_PER_DAY;
        BigDecimal lastCycleAccumulated = cycleAccumulated;

        // 时段独立封顶跟踪：当前 period 及其在 units 列表的起始索引
        CompositePeriod currentPeriod = null;
        int periodStartIndex = 0;

        BigDecimal accumulated = BigDecimal.ZERO;

        for (int i = 0; i < segments.size(); i++) {
            HomogeneousSegment seg = segments.get(i);
            boolean isLast = (i == segments.size() - 1);
            int segMinutes = seg.durationMinutes();
            int positionInCycle = (int) (((Duration.between(billingOrigin, seg.getBeginTime()).toMinutes() % MINUTES_PER_DAY)
                    + MINUTES_PER_DAY) % MINUTES_PER_DAY);
            CompositePeriod period = periodResolver.findPeriodForMinute(positionInCycle, config.getPeriods());

            // 截断判定提前
            int unitMinutes = period.getUnitMinutes();
            int subCount = unitMinutes > 0 ? segMinutes / unitMinutes : 1;
            if (subCount < 1) subCount = 1;

            boolean isTruncated = isLast
                    && unitMinutes > 0
                    && segMinutes < unitMinutes
                    && seg.getEndTime().equals(calcEnd);

            // 时段切换：对前一 period 应用独立封顶
            if (currentPeriod == null) {
                currentPeriod = period;
                periodStartIndex = units.size();
            } else if (!period.equals(currentPeriod)) {
                applyPeriodCapToUnits(units, periodStartIndex, currentPeriod.getMaxCharge());
                currentPeriod = period;
                periodStartIndex = units.size();
            }

            boolean cycleCapped = false;
            if (maxCharge != null && maxCharge.compareTo(BigDecimal.ZERO) > 0
                    && !seg.isFree() && cycleAccumulated.compareTo(maxCharge) >= 0) {
                cycleCapped = true;
            }

            // 不足单元是否按模式免费
            boolean incompleteFree = isTruncated && !seg.isFree() && !cycleCapped
                    && isIncompleteFree(segMinutes, unitMinutes, config.getIncompleteUnitChargeMode(),
                            config.getThresholdMinutes(), config.getThresholdRatio());

            BigDecimal originalPerSub = seg.getOriginalAmount() != null
                    ? seg.getOriginalAmount() : BigDecimal.ZERO;
            BigDecimal unitPrice = seg.getUnitPrice() != null ? seg.getUnitPrice() : BigDecimal.ZERO;

            BigDecimal charged;
            if (seg.isFree() || cycleCapped || incompleteFree) {
                charged = BigDecimal.ZERO;
            } else if (isTruncated) {
                charged = computeIncompleteCharge(unitPrice, segMinutes, unitMinutes,
                        config.getIncompleteUnitChargeMode(),
                        config.getThresholdMinutes(), config.getThresholdRatio());
            } else {
                BigDecimal budget = maxCharge != null
                        ? maxCharge.subtract(cycleAccumulated)
                        : null;
                if (budget != null && budget.signum() < 0) budget = BigDecimal.ZERO;
                BigDecimal fullTotal = originalPerSub.multiply(BigDecimal.valueOf(subCount));
                if (budget != null && fullTotal.compareTo(budget) > 0) {
                    charged = budget.setScale(2, RoundingMode.HALF_UP);
                } else {
                    charged = fullTotal;
                }
            }

            accumulated = accumulated.add(charged);
            if (!seg.isFree() && !cycleCapped && !incompleteFree) {
                cycleAccumulated = cycleAccumulated.add(charged);
            }
            lastCycleAccumulated = cycleAccumulated;

            boolean isCompact = !isTruncated && subCount > 1;

            BillingUnit unit = BillingUnit.builder()
                    .beginTime(seg.getBeginTime())
                    .endTime(seg.getEndTime())
                    .durationMinutes(segMinutes)
                    .unitPrice(unitPrice)
                    .originalAmount(originalPerSub.multiply(BigDecimal.valueOf(subCount)))
                    .free(seg.isFree() || cycleCapped || incompleteFree)
                    .freePromotionId(cycleCapped ? "CYCLE_CAP"
                            : (incompleteFree ? "INCOMPLETE_FREE" : seg.getFreePromotionId()))
                    .chargedAmount(charged)
                    .accumulatedAmount(accumulated)
                    .ruleData(seg.getRuleData())
                    .compact(isCompact)
                    .count(isCompact ? subCount : 1)
                    .isTruncated(isTruncated)
                    .build();
            units.add(unit);

            // 24h 周期切换
            long offsetFromOrigin = Duration.between(billingOrigin, seg.getEndTime()).toMinutes();
            if (offsetFromOrigin >= nextCycleBoundaryOffset) {
                nextCycleBoundaryOffset = ((offsetFromOrigin / MINUTES_PER_DAY) + 1) * MINUTES_PER_DAY;
                cycleAccumulated = BigDecimal.ZERO;
                lastCycleAccumulated = BigDecimal.ZERO;
            }
        }

        // 对最后一个 period 应用独立封顶
        if (currentPeriod != null) {
            applyPeriodCapToUnits(units, periodStartIndex, currentPeriod.getMaxCharge());
        }

        // 时段封顶削减后，重新计算累计金额（削减改变了 chargedAmount）
        if (hasPeriodCap(config)) {
            recomputeAccumulatedAmounts(units, context);
        }

        return new ContinuousResult(units, lastCycleAccumulated);
    }

    /**
     * 是否配置了任意时段独立封顶。
     */
    private boolean hasPeriodCap(CompositeTimeConfig config) {
        if (config.getPeriods() == null) return false;
        for (CompositePeriod period : config.getPeriods()) {
            if (period.getMaxCharge() != null && period.getMaxCharge().compareTo(BigDecimal.ZERO) > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * 对 units 列表中 [startIndex, end) 范围内的收费单元应用时段独立封顶。
     * 从最后一个收费单元开始削减，削减为 0 标记 free + PERIOD_CAP。
     * 削减会破坏 compact 合并前提，命中单元标记为非 compact。
     */
    private void applyPeriodCapToUnits(List<BillingUnit> units, int startIndex, BigDecimal maxCharge) {
        if (maxCharge == null || maxCharge.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        if (startIndex >= units.size()) {
            return;
        }
        List<BillingUnit> periodUnits = units.subList(startIndex, units.size());
        List<BillingUnit> chargeableUnits = new ArrayList<>(periodUnits.stream()
                .filter(u -> !u.isFree())
                .toList());

        if (chargeableUnits.isEmpty()) {
            return;
        }

        BigDecimal totalCharge = chargeableUnits.stream()
                .map(BillingUnit::getChargedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (totalCharge.compareTo(maxCharge) <= 0) {
            return;
        }

        BigDecimal excess = totalCharge.subtract(maxCharge);

        for (int i = chargeableUnits.size() - 1; i >= 0 && excess.compareTo(BigDecimal.ZERO) > 0; i--) {
            BillingUnit unit = chargeableUnits.get(i);
            BigDecimal charged = unit.getChargedAmount();

            if (charged.compareTo(excess) >= 0) {
                unit.setChargedAmount(charged.subtract(excess).setScale(2, RoundingMode.HALF_UP));
                if (unit.getChargedAmount().compareTo(BigDecimal.ZERO) == 0) {
                    unit.setFree(true);
                    unit.setFreePromotionId("PERIOD_CAP");
                }
                excess = BigDecimal.ZERO;
            } else {
                unit.setChargedAmount(BigDecimal.ZERO);
                unit.setFree(true);
                unit.setFreePromotionId("PERIOD_CAP");
                excess = excess.subtract(charged);
            }
            // 削减破坏 compact 合并前提，标记为非 compact
            if (unit.isCompact()) {
                unit.setCompact(false);
                unit.setCount(1);
            }
        }
    }

    /**
     * 时段封顶削减后重新计算 accumulatedAmount。
     * 削减只改变 chargedAmount，需重算前缀累计。
     */
    private void recomputeAccumulatedAmounts(List<BillingUnit> units, BillingContext context) {
        BigDecimal accumulated = BigDecimal.ZERO;
        for (int i = 0; i < units.size(); i++) {
            BillingUnit unit = units.get(i);
            BigDecimal charged = unit.getChargedAmount();
            accumulated = accumulated.add(charged);
            unit.setAccumulatedAmount(accumulated);
        }
    }

    /**
     * 为一个周期生成计费单元
     */
    private List<BillingUnit> generateUnitsForCycle(CycleFragments cycle, CompositeTimeConfig config, LocalDateTime billingOrigin) {
        List<BillingUnit> units = new ArrayList<>();

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
                // 使用"气泡抽出"模型计算相对位置
                units.addAll(generateUnitsForFragment(fragment, cycle, config, billingOrigin));
            }
        }

        return units;
    }

    /**
     * 为一个片段生成计费单元（气泡抽出模型）
     * <p>
     * 相对位置计算：
     * 从原始计费起点开始，减去已经过的免费时段，得到相对位置
     */
    private List<BillingUnit> generateUnitsForFragment(TimeFragment fragment, CycleFragments cycle,
                                                        CompositeTimeConfig config, LocalDateTime billingOrigin) {
        List<BillingUnit> units = new ArrayList<>();

        // 计算片段开始时间相对于计费起点的原始分钟偏移
        long rawMinutesFromOrigin = Duration.between(billingOrigin, fragment.beginTime).toMinutes();

        // 减去已过的免费时段，得到相对位置
        long relativePosition = rawMinutesFromOrigin - calculateFreeMinutesBefore(billingOrigin, fragment.beginTime, config);

        // 确定当前相对位置属于哪个周期
        long cycleIndex = relativePosition / MINUTES_PER_DAY;
        long positionInCycle = relativePosition % MINUTES_PER_DAY;
        if (positionInCycle < 0) {
            positionInCycle += MINUTES_PER_DAY;
        }

        LocalDateTime current = fragment.beginTime;

        while (current.isBefore(fragment.endTime)) {
            // 根据相对位置找到对应的 CompositePeriod
            CompositePeriod period = periodResolver.findPeriodForMinute((int) positionInCycle, config.getPeriods());
            int unitMinutes = period.getUnitMinutes();

            LocalDateTime unitEnd = current.plusMinutes(unitMinutes);

            // 截断到片段边界
            if (unitEnd.isAfter(fragment.endTime)) {
                unitEnd = fragment.endTime;
            }

            // 截断到周期边界（基于相对位置）
            int periodEndMinute = period.getEndMinute();
            // 计算当前相对位置到下一个时间段边界的分钟数
            long minutesToPeriodEnd = periodEndMinute - positionInCycle;
            LocalDateTime periodBoundary = current.plusMinutes(minutesToPeriodEnd);
            if (unitEnd.isAfter(periodBoundary) && periodBoundary.isAfter(current)) {
                unitEnd = periodBoundary;
            }

            // 截断到周期边界（24小时）
            if (unitEnd.isAfter(cycle.cycleEnd)) {
                unitEnd = cycle.cycleEnd;
            }

            int duration = (int) Duration.between(current, unitEnd).toMinutes();

            // 计算单元价格（基于自然时段）
            BigDecimal unitPrice = crossPeriodPriceResolver.calculateUnitPrice(current, unitEnd, period);

            // 不足单元也收全额
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

            // 更新当前位置和相对位置
            long minutesAdvanced = Duration.between(current, unitEnd).toMinutes();
            current = unitEnd;
            positionInCycle += minutesAdvanced;

            // 跨越周期边界时重置
            if (positionInCycle >= MINUTES_PER_DAY) {
                positionInCycle -= MINUTES_PER_DAY;
                cycleIndex++;
            }
        }

        return units;
    }

    /**
     * 计算在指定时间之前已经过的免费分钟数
     * 注：这里指的是配置中固定的时间段内免费分钟，而非动态的优惠券免费时段
     * 对于 CONTINUOUS 模式，免费分钟来自 PromotionAggregate 的免费时段
     */
    private long calculateFreeMinutesBefore(LocalDateTime origin, LocalDateTime target, CompositeTimeConfig config) {
        // 对于 CONTINUOUS 模式，免费时段已经在 splitTimeAxis 中处理
        // 这里返回 0，因为相对位置的计算不需要再次扣除
        return 0;
    }

    /**
     * CONTINUOUS 模式封顶处理（考虑结转的累计金额）
     */
    private BigDecimal applyContinuousCapWithCarryOver(List<BillingUnit> units, BigDecimal maxCharge, BigDecimal carryOverAccumulated) {
        if (maxCharge == null || maxCharge.compareTo(BigDecimal.ZERO) <= 0) {
            BigDecimal total = carryOverAccumulated;
            for (BillingUnit unit : units) {
                if (!unit.isFree()) {
                    total = total.add(unit.getChargedAmount());
                }
            }
            return total;
        }

        // 如果继承的累计金额已达到封顶，将所有收费单元合并为一个免费单元
        if (carryOverAccumulated.compareTo(maxCharge) >= 0) {
            List<BillingUnit> chargeableUnits = units.stream()
                    .filter(u -> !u.isFree())
                    .toList();

            if (!chargeableUnits.isEmpty()) {
                BillingUnit firstChargeable = chargeableUnits.get(0);
                BillingUnit lastChargeable = chargeableUnits.get(chargeableUnits.size() - 1);

                units.removeIf(u -> !u.isFree());

                BillingUnit mergedFreeUnit = BillingUnit.builder()
                        .beginTime(firstChargeable.getBeginTime())
                        .endTime(lastChargeable.getEndTime())
                        .durationMinutes((int) Duration.between(firstChargeable.getBeginTime(), lastChargeable.getEndTime()).toMinutes())
                        .unitPrice(BigDecimal.ZERO)
                        .originalAmount(BigDecimal.ZERO)
                        .free(true)
                        .freePromotionId("CYCLE_CAP")
                        .chargedAmount(BigDecimal.ZERO)
                        .build();
                units.add(mergedFreeUnit);
            }
            return maxCharge;
        }

        BigDecimal accumulated = carryOverAccumulated;
        int capIndex = -1;
        BigDecimal lastChargeAmount = null;

        for (int i = 0; i < units.size(); i++) {
            BillingUnit unit = units.get(i);
            if (unit.isFree()) {
                continue;
            }

            BigDecimal newAccumulated = accumulated.add(unit.getChargedAmount());

            if (newAccumulated.compareTo(maxCharge) >= 0) {
                capIndex = i;
                lastChargeAmount = maxCharge.subtract(accumulated);
                break;
            }

            accumulated = newAccumulated;
        }

        if (capIndex < 0) {
            return accumulated;
        }

        units.get(capIndex).setChargedAmount(lastChargeAmount.setScale(2, RoundingMode.HALF_UP));
        if (units.get(capIndex).getChargedAmount().compareTo(BigDecimal.ZERO) == 0) {
            units.get(capIndex).setFree(true);
            units.get(capIndex).setFreePromotionId("CYCLE_CAP");
        }

        if (capIndex < units.size() - 1) {
            BillingUnit firstAfterCap = units.get(capIndex + 1);
            BillingUnit lastAfterCap = units.get(units.size() - 1);

            BillingUnit mergedFreeUnit = BillingUnit.builder()
                    .beginTime(firstAfterCap.getBeginTime())
                    .endTime(lastAfterCap.getEndTime())
                    .durationMinutes((int) Duration.between(firstAfterCap.getBeginTime(), lastAfterCap.getEndTime()).toMinutes())
                    .unitPrice(BigDecimal.ZERO)
                    .originalAmount(BigDecimal.ZERO)
                    .free(true)
                    .freePromotionId("CYCLE_CAP")
                    .chargedAmount(BigDecimal.ZERO)
                    .build();

            units.subList(capIndex + 1, units.size()).clear();
            units.add(mergedFreeUnit);
        }

        return maxCharge;
    }

    /**
     * 周期计费单元容器
     */
    private static class CycleUnits {
        final LocalDateTime cycleStart;
        final LocalDateTime cycleEnd;
        final List<BillingUnit> units = new ArrayList<>();
        BigDecimal accumulatedBeforeCap;

        CycleUnits(LocalDateTime cycleStart, LocalDateTime cycleEnd) {
            this.cycleStart = cycleStart;
            this.cycleEnd = cycleEnd;
        }
    }

    private void applyCycleCapWithCarryOver(CycleUnits cycle, BigDecimal maxCharge, BigDecimal carryOverAccumulated) {
        if (maxCharge == null || maxCharge.compareTo(BigDecimal.ZERO) <= 0) {
            BigDecimal newAmount = cycle.units.stream()
                    .filter(u -> !u.isFree())
                    .map(BillingUnit::getChargedAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            cycle.accumulatedBeforeCap = carryOverAccumulated.add(newAmount);
            return;
        }

        List<BillingUnit> chargeableUnits = cycle.units.stream()
                .filter(u -> !u.isFree())
                .toList();

        BigDecimal cycleNewAmount = chargeableUnits.stream()
                .map(BillingUnit::getChargedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalAccumulated = carryOverAccumulated.add(cycleNewAmount);

        if (totalAccumulated.compareTo(maxCharge) < 0) {
            cycle.accumulatedBeforeCap = totalAccumulated;
            return;
        }

        BigDecimal excess = totalAccumulated.subtract(maxCharge);

        for (int i = chargeableUnits.size() - 1; i >= 0 && excess.compareTo(BigDecimal.ZERO) > 0; i--) {
            BillingUnit unit = chargeableUnits.get(i);
            BigDecimal charged = unit.getChargedAmount();

            if (charged.compareTo(excess) >= 0) {
                unit.setChargedAmount(charged.subtract(excess).setScale(2, RoundingMode.HALF_UP));
                if (unit.getChargedAmount().compareTo(BigDecimal.ZERO) == 0) {
                    unit.setFree(true);
                    unit.setFreePromotionId("CYCLE_CAP");
                }
                excess = BigDecimal.ZERO;
            } else {
                unit.setChargedAmount(BigDecimal.ZERO);
                unit.setFree(true);
                unit.setFreePromotionId("CYCLE_CAP");
                excess = excess.subtract(charged);
            }
        }
        cycle.accumulatedBeforeCap = maxCharge;
    }



    /**
     * 查找下一个相对时间段边界
     * @param current 当前时间点
     * @param billingOrigin 计费起点
     * @param config 规则配置
     * @return 下一个时间段边界时间，如果没有则返回 null
     */
    private LocalDateTime findNextRelativePeriodBoundary(LocalDateTime current, LocalDateTime billingOrigin, CompositeTimeConfig config) {
        if (config.getPeriods() == null || config.getPeriods().isEmpty()) {
            return null;
        }

        // 计算当前位置相对于计费起点的分钟偏移
        long minutesFromBillingOrigin = Duration.between(billingOrigin, current).toMinutes();

        // 计算当前在周期内的位置（取模）
        long positionInCycle = minutesFromBillingOrigin % MINUTES_PER_DAY;
        if (positionInCycle < 0) {
            positionInCycle += MINUTES_PER_DAY;
        }

        // 当前周期的起点
        long cycleCount = minutesFromBillingOrigin / MINUTES_PER_DAY;
        LocalDateTime cycleStart = billingOrigin.plusMinutes(cycleCount * MINUTES_PER_DAY);

        // 遍历所有相对时间段，找到第一个大于当前位置的边界
        for (CompositePeriod period : config.getPeriods()) {
            long periodEndMinute = period.getEndMinute();
            if (periodEndMinute > positionInCycle) {
                return cycleStart.plusMinutes(periodEndMinute);
            }
        }

        // 如果当前周期内没有，返回下一个周期的起点
        return cycleStart.plusMinutes(MINUTES_PER_DAY);
    }

    /**
     * 查找下一个周期边界
     * @param current 当前时间点
     * @param billingOrigin 计费起点
     * @return 下一个周期边界时间（24小时后）
     */
    private LocalDateTime findNextCycleBoundary(LocalDateTime current, LocalDateTime billingOrigin) {
        LocalDateTime cycleStart = billingOrigin;
        while (cycleStart.plusMinutes(MINUTES_PER_DAY).isBefore(current) ||
               cycleStart.plusMinutes(MINUTES_PER_DAY).equals(current)) {
            cycleStart = cycleStart.plusMinutes(MINUTES_PER_DAY);
        }
        return cycleStart.plusMinutes(MINUTES_PER_DAY);
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
        if (periods.get(periods.size() - 1).getEndMinute() != MINUTES_PER_DAY) {
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
                totalCovered += (MINUTES_PER_DAY - period.getBeginMinute()) + period.getEndMinute();
            }
        }
        if (totalCovered != MINUTES_PER_DAY) {
            throw new IllegalArgumentException("自然时段必须覆盖全天（0-1440分钟）");
        }
    }
}
