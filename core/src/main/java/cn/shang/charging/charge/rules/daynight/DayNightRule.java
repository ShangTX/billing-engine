package cn.shang.charging.charge.rules.daynight;

import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.BillingContext;
import cn.shang.charging.billing.pojo.BillingSegmentResult;
import cn.shang.charging.billing.pojo.BillingUnit;
import cn.shang.charging.billing.value.FixedValueSpec;
import cn.shang.charging.billing.value.StepValueSpec;
import cn.shang.charging.billing.value.UnitValueProjection;
import cn.shang.charging.billing.value.UnitValueSpec;
import cn.shang.charging.charge.rules.AbstractTimeBasedRule;
import cn.shang.charging.charge.rules.BillingRule;
import cn.shang.charging.charge.rules.BoundaryProvider;
import cn.shang.charging.charge.rules.BoundaryProviders;
import cn.shang.charging.charge.rules.HomogeneousSegment;
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
 * 日夜分时段计费规则
 * <p>
 * 核心逻辑：
 * 1. 从计费起点开始，按24小时划分周期
 * 2. 每个周期内独立计算封顶
 * 3. 按unitMinutes划分计费单元，跨周期边界截断
 * 4. 跨日夜时段根据blockWeight判断使用白天价还是夜间价
 * 5. 免费时段完全覆盖计费单元则免费
 */
public class DayNightRule extends AbstractTimeBasedRule<DayNightConfig> {

    // 规则类型标识
    private static final String RULE_TYPE = "dayNight";
    private final DayNightContinuousCalculator continuousCalculator = new DayNightContinuousCalculator();
    private final DayNightUnitBasedCalculator unitBasedCalculator = new DayNightUnitBasedCalculator();
    private final DayNightPriceResolver priceResolver = new DayNightPriceResolver();
    private final DayNightValueSpecFactory valueSpecFactory = new DayNightValueSpecFactory();
    private final DayNightCycleStateManager cycleStateManager = new DayNightCycleStateManager();

    @Override
    protected String getRuleType() {
        return RULE_TYPE;
    }

    @Override
    protected boolean hasComplexFeatures(DayNightConfig config) {
        // DayNightRule 无时间段封顶等复杂特性
        return false;
    }

    @Override
    protected boolean isSimplifiedSupported(DayNightConfig config) {
        // DayNightRule 支持简化计算
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
    public Set<BConstants.BillingMode> supportedModes() {
        return EnumSet.of(BConstants.BillingMode.CONTINUOUS, BConstants.BillingMode.UNIT_BASED);
    }

    /**
     * 验证配置有效性
     */
    private void validateConfig(DayNightConfig config) {
        // 检查每日封顶金额（必填）
        if (config.getMaxChargeOneDay() == null) {
            throw new IllegalArgumentException("maxChargeOneDay is required");
        }
        if (config.getMaxChargeOneDay().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("maxChargeOneDay must be positive");
        }

        // 检查单元长度
        if (config.getUnitMinutes() == null || config.getUnitMinutes() <= 0) {
            throw new IllegalArgumentException("unitMinutes must be positive");
        }

        // 检查单价
        if (config.getDayUnitPrice() == null || config.getDayUnitPrice().compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("dayUnitPrice must be non-negative");
        }
        if (config.getNightUnitPrice() == null || config.getNightUnitPrice().compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("nightUnitPrice must be non-negative");
        }

        // 检查日夜时段配置
        if (config.getDayBeginMinute() == null || config.getDayEndMinute() == null) {
            throw new IllegalArgumentException("dayBeginMinute and dayEndMinute are required");
        }

        // 检查 blockWeight
        if (config.getBlockWeight() == null ||
            config.getBlockWeight().compareTo(BigDecimal.ZERO) < 0 ||
            config.getBlockWeight().compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("blockWeight must be between 0 and 1");
        }
    }

    @Override
    public BillingSegmentResult calculate(BillingContext context, DayNightConfig config, PromotionAggregate promotionAggregate) {
        if (context.getBillingMode() == BConstants.BillingMode.UNIT_BASED) {
            return unitBasedCalculator.calculate(this, context, config, promotionAggregate);
        } else {
            return continuousCalculator.calculate(this, context, config, promotionAggregate);
        }
    }

    /**
     * UNIT_BASED 模式计算
     * 固定从计费起点对齐，免费时段必须完全覆盖整个单元才免费
     */
    BillingSegmentResult calculateUnitBasedInternal(BillingContext context, DayNightConfig config, PromotionAggregate promotionAggregate) {
        // 验证配置
        validateConfig(config);

        // 获取计算窗口
        LocalDateTime calcBegin = context.getWindow().getCalculationBegin();
        LocalDateTime calcEnd = context.getWindow().getCalculationEnd();

        // 检查是否启用简化计算
        boolean simplificationEnabled = context.getBillingConfigResolver() != null
            && isSimplificationEnabled(config, context.getBillingConfigResolver(), context);

        // 计算总周期数
        long totalMinutes = Duration.between(calcBegin, calcEnd).toMinutes();
        int totalCycles = (int) (totalMinutes / getCycleMinutes());

        if (simplificationEnabled && totalCycles > context.getBillingConfigResolver().getSimplifiedCycleThreshold()) {
            // 预计算有优惠的周期
            Set<Integer> cyclesWithPromotion = findCyclesWithPromotion(calcBegin, calcEnd, promotionAggregate);

            // 如果所有周期都有优惠（freeMinutes > 0），不简化
            if (cyclesWithPromotion == null) {
                simplificationEnabled = false;
            } else {
                // 执行简化计算
                return calculateWithSimplification(context, config, promotionAggregate,
                    calcBegin, calcEnd, context.getBillingConfigResolver().getSimplifiedCycleThreshold(),
                    cyclesWithPromotion, totalCycles);
            }
        }

        // 恢复状态
        RuleState state = restoreState(context.getRuleState());
        if (state == null) {
            // FROM_SCRATCH: 初始化状态
            state = initializeState(calcBegin);
        } else {
            // CONTINUE: 更新周期状态
            while (state.getCycleBoundary() != null && !calcBegin.isBefore(state.getCycleBoundary())) {
                state.setCycleIndex(state.getCycleIndex() + 1);
                state.setCycleAccumulated(BigDecimal.ZERO);
                state.setCycleBoundary(state.getCycleBoundary().plusMinutes(getCycleMinutes()));
            }
        }

        // 按周期和单元划分，生成计费单元（传入初始周期索引）
        List<UnitWithContext> unitsWithContext = buildUnitsWithContextWithState(calcBegin, calcEnd, config, state);

        // 获取免费时段
        List<FreeTimeRange> freeTimeRanges = promotionAggregate.getFreeTimeRanges();
        if (freeTimeRanges == null) {
            freeTimeRanges = List.of();
        }

        // 计算每个单元
        List<BillingUnit> billingUnits = new ArrayList<>();
        List<PromotionUsage> promotionUsages = new ArrayList<>();

        for (UnitWithContext unitCtx : unitsWithContext) {
            BillingUnit unit = calculateUnit(unitCtx, config, freeTimeRanges);
            billingUnits.add(unit);
        }

        // 按周期应用封顶（考虑结转的累计金额），返回最后一个周期的累计金额
        BigDecimal lastCycleAccumulated = cycleStateManager.applyDailyCapWithCarryOver(
                billingUnits,
                config,
                state.getCycleAccumulated(),
                (beginTime, endTime) -> valueSpecFactory.createCappedSpec(
                        billingUnits.stream()
                                .filter(u -> beginTime.equals(u.getBeginTime()) && endTime.equals(u.getEndTime()))
                                .findFirst()
                                .map(BillingUnit::getValueSpec)
                                .orElse(null),
                        beginTime,
                        endTime,
                        billingUnits.stream()
                                .filter(u -> beginTime.equals(u.getBeginTime()) && endTime.equals(u.getEndTime()))
                                .findFirst()
                                .map(BillingUnit::getChargedAmount)
                                .orElse(BigDecimal.ZERO)
                )
        );

        // 更新最终状态（FROM_SCRATCH 结果也需要用于继续计算）
        cycleStateManager.updateStateAfterUnitBased(billingUnits, state);
        // 使用返回的累计金额（包含之前累计的）
        state.setCycleAccumulated(lastCycleAccumulated);

        // 汇总结果
        BigDecimal totalAmount = billingUnits.stream()
                .map(BillingUnit::getChargedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 计算费用稳定时间窗口
        LocalDateTime feeEffectiveStart = calculateEffectiveFrom(billingUnits);
        LocalDateTime feeEffectiveEnd = calculateEffectiveTo(billingUnits, freeTimeRanges, calcBegin, calcEnd);

        // 标记最后一个单元是否被截断
        if (!billingUnits.isEmpty()) {
            BillingUnit lastUnit = billingUnits.get(billingUnits.size() - 1);
            int unitMinutes = config.getUnitMinutes();
            if (lastUnit.getDurationMinutes() < unitMinutes && lastUnit.getEndTime().equals(calcEnd)) {
                lastUnit.setIsTruncated(true);
            }
        }

        // 计算累计金额
        BigDecimal accumulatedAmount = context.getPreviousAccumulatedAmount();
        if (accumulatedAmount == null) {
            accumulatedAmount = BigDecimal.ZERO;
        }

        // 如果有截断单元已收取金额，需要扣减（避免重复收费）
        BigDecimal truncatedUnitChargedAmount = context.getTruncatedUnitChargedAmount();
        if (truncatedUnitChargedAmount != null && !billingUnits.isEmpty()) {
            // 从累计金额中扣减截断单元已收取的金额
            accumulatedAmount = accumulatedAmount.subtract(truncatedUnitChargedAmount);
            if (accumulatedAmount.compareTo(BigDecimal.ZERO) < 0) {
                accumulatedAmount = BigDecimal.ZERO;
            }
        }

        for (BillingUnit unit : billingUnits) {
            accumulatedAmount = accumulatedAmount.add(unit.getChargedAmount());
            unit.setAccumulatedAmount(accumulatedAmount);
        }

        // 构建输出状态（FROM_SCRATCH 结果也需要用于继续计算）
        Map<String, Object> ruleOutputState = buildRuleOutputState(state);

        return BillingSegmentResult.builder()
                .segmentId(context.getSegment().getId())
                .segmentStartTime(context.getSegment().getBeginTime())
                .segmentEndTime(context.getSegment().getEndTime())
                .calculationStartTime(calcBegin)
                .calculationEndTime(calcEnd)
                .chargedAmount(totalAmount)
                .billingUnits(billingUnits)
                .promotionUsages(promotionUsages)
                .promotionAggregate(promotionAggregate)
                .feeEffectiveStart(feeEffectiveStart)
                .feeEffectiveEnd(feeEffectiveEnd)
                .ruleOutputState(ruleOutputState)
                .build();
    }

    /**
     * 带简化计算的方法
     */
    private BillingSegmentResult calculateWithSimplification(
            BillingContext context,
            DayNightConfig config,
            PromotionAggregate promotionAggregate,
            LocalDateTime calcBegin,
            LocalDateTime calcEnd,
            int threshold,
            Set<Integer> cyclesWithPromotion,
            int totalCycles) {

        List<BillingUnit> billingUnits = new ArrayList<>();
        BigDecimal cycleCapAmount = getCycleCapAmount(config);
        List<FreeTimeRange> freeTimeRanges = promotionAggregate != null ? promotionAggregate.getFreeTimeRanges() : null;
        if (freeTimeRanges == null) {
            freeTimeRanges = List.of();
        }

        // 恢复状态
        RuleState state = restoreState(context.getRuleState());
        if (state == null) {
            state = initializeState(calcBegin);
        }

        // 从 state 恢复周期索引
        int startCycleIndex = state.getCycleIndex();

        int consecutiveSimplified = 0;
        int simplifiedStartIndex = -1;

        for (int cycleIndex = startCycleIndex; cycleIndex <= totalCycles; cycleIndex++) {
            boolean hasPromotion = cyclesWithPromotion.contains(cycleIndex);

            if (!hasPromotion) {
                // 无优惠周期，累计
                if (consecutiveSimplified == 0) {
                    simplifiedStartIndex = cycleIndex;
                }
                consecutiveSimplified++;
            } else {
                // 遇到有优惠周期，先处理之前的简化段
                if (consecutiveSimplified > threshold) {
                    // 生成简化单元
                    BillingUnit simplifiedUnit = buildSimplifiedUnit(
                        simplifiedStartIndex, consecutiveSimplified, cycleCapAmount, calcBegin);
                    billingUnits.add(simplifiedUnit);
                } else if (consecutiveSimplified > 0) {
                    // 不足阈值，逐周期生成
                    for (int i = simplifiedStartIndex; i < simplifiedStartIndex + consecutiveSimplified; i++) {
                        List<BillingUnit> cycleUnits = generateUnitsForSingleCycle(i, calcBegin, calcEnd, config, freeTimeRanges);
                        billingUnits.addAll(cycleUnits);
                    }
                }
                consecutiveSimplified = 0;

                // 生成当前有优惠周期的详细单元
                List<BillingUnit> cycleUnits = generateUnitsForSingleCycle(cycleIndex, calcBegin, calcEnd, config, freeTimeRanges);
                billingUnits.addAll(cycleUnits);
            }
        }

        // 处理最后的简化段
        if (consecutiveSimplified > threshold) {
            BillingUnit simplifiedUnit = buildSimplifiedUnit(
                simplifiedStartIndex, consecutiveSimplified, cycleCapAmount, calcBegin);
            billingUnits.add(simplifiedUnit);
        } else if (consecutiveSimplified > 0) {
            for (int i = simplifiedStartIndex; i < simplifiedStartIndex + consecutiveSimplified; i++) {
                List<BillingUnit> cycleUnits = generateUnitsForSingleCycle(i, calcBegin, calcEnd, config, freeTimeRanges);
                billingUnits.addAll(cycleUnits);
            }
        }

        // 应用封顶（简化单元已达封顶，但需要处理累计金额的逻辑）
        cycleStateManager.applyDailyCapWithCarryOver(
                billingUnits,
                config,
                state.getCycleAccumulated(),
                (beginTime, endTime) -> valueSpecFactory.createCappedSpec(
                        billingUnits.stream()
                                .filter(u -> beginTime.equals(u.getBeginTime()) && endTime.equals(u.getEndTime()))
                                .findFirst()
                                .map(BillingUnit::getValueSpec)
                                .orElse(null),
                        beginTime,
                        endTime,
                        billingUnits.stream()
                                .filter(u -> beginTime.equals(u.getBeginTime()) && endTime.equals(u.getEndTime()))
                                .findFirst()
                                .map(BillingUnit::getChargedAmount)
                                .orElse(BigDecimal.ZERO)
                )
        );

        // 更新状态 - 使用实际处理的最后一个周期索引
        if (!billingUnits.isEmpty()) {
            cycleStateManager.updateStateAfterSimplified(
                    billingUnits,
                    state,
                    calcBegin,
                    this::getCycleBoundary,
                    this::isSimplifiedUnit
            );
        } else {
            // 没有单元，保持原状态
            state.setCycleIndex(totalCycles);
            state.setCycleAccumulated(cycleCapAmount);
            state.setCycleBoundary(getCycleBoundary(totalCycles + 1, calcBegin));
        }

        // 汇总结果
        BigDecimal totalAmount = billingUnits.stream()
                .map(BillingUnit::getChargedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 标记最后单元截断
        if (!billingUnits.isEmpty()) {
            BillingUnit lastUnit = billingUnits.get(billingUnits.size() - 1);
            if (!isSimplifiedUnit(lastUnit)) {
                int unitMinutes = config.getUnitMinutes();
                if (lastUnit.getDurationMinutes() < unitMinutes && lastUnit.getEndTime().equals(calcEnd)) {
                    lastUnit.setIsTruncated(true);
                }
            }
        }

        return BillingSegmentResult.builder()
                .segmentId(context.getSegment().getId())
                .segmentStartTime(context.getSegment().getBeginTime())
                .segmentEndTime(context.getSegment().getEndTime())
                .calculationStartTime(calcBegin)
                .calculationEndTime(calcEnd)
                .chargedAmount(totalAmount)
                .billingUnits(billingUnits)
                .promotionUsages(new ArrayList<>())
                .promotionAggregate(promotionAggregate)
                .feeEffectiveStart(calculateEffectiveFrom(billingUnits))
                .feeEffectiveEnd(calculateEffectiveTo(billingUnits, freeTimeRanges, calcBegin, calcEnd))
                .ruleOutputState(buildRuleOutputState(state))
                .build();
    }

    /**
     * 生成单个周期的计费单元
     */
    private List<BillingUnit> generateUnitsForSingleCycle(
            int cycleIndex,
            LocalDateTime calcBegin,
            LocalDateTime calcEnd,
            DayNightConfig config,
            List<FreeTimeRange> freeTimeRanges) {

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
        List<BillingUnit> units = generateUnitsForCycle(cycle, config);

        // 为每个单元设置周期索引
        for (BillingUnit unit : units) {
            unit.setRuleData(cycleIndex);
        }

        return units;
    }

    /**
     * 构建带上下文的计费单元列表（支持状态恢复）
     */
    private List<UnitWithContext> buildUnitsWithContextWithState(LocalDateTime begin, LocalDateTime end, DayNightConfig config, RuleState state) {
        List<UnitWithContext> units = new ArrayList<>();
        int unitMinutes = config.getUnitMinutes();

        LocalDateTime current = begin;
        int cycleIndex = state.getCycleIndex();
        LocalDateTime cycleStart = state.getCycleBoundary() != null
                ? state.getCycleBoundary().minusHours(24)
                : begin;

        while (current.isBefore(end)) {
            // 计算当前周期结束时间
            LocalDateTime cycleEnd = cycleStart.plusHours(24);

            // 当前单元结束时间：取 min(当前+unitMinutes, 周期结束, 计费结束)
            LocalDateTime unitEnd = current.plusMinutes(unitMinutes);
            if (unitEnd.isAfter(cycleEnd)) {
                unitEnd = cycleEnd;
            }
            if (unitEnd.isAfter(end)) {
                unitEnd = end;
            }

            // 判断时段类型
            DayNightPeriodType periodType = priceResolver.determinePeriodType(current, unitEnd, config);

            // 计算白天夜间分钟数
            int dayMins = 0, nightMins = 0;
            if (periodType == DayNightPeriodType.MIXED) {
                int[] mins = priceResolver.calculateDayNightMinutes(current, unitEnd, config);
                dayMins = mins[0];
                nightMins = mins[1];
            } else if (periodType == DayNightPeriodType.DAY) {
                dayMins = (int) Duration.between(current, unitEnd).toMinutes();
            } else {
                nightMins = (int) Duration.between(current, unitEnd).toMinutes();
            }

            UnitWithContext unitCtx = new UnitWithContext();
            unitCtx.beginTime = current;
            unitCtx.endTime = unitEnd;
            unitCtx.cycleIndex = cycleIndex;
            unitCtx.periodType = periodType;
            unitCtx.dayMinutes = dayMins;
            unitCtx.nightMinutes = nightMins;

            units.add(unitCtx);

            // 更新当前时间和周期
            current = unitEnd;

            // 如果跨越到新周期
            if (!current.isBefore(cycleEnd) && current.isBefore(end)) {
                cycleIndex++;
                cycleStart = cycleEnd;
            }
        }

        return units;
    }

    /**
     * 计算单个计费单元
     */
    private BillingUnit calculateUnit(UnitWithContext unitCtx, DayNightConfig config, List<FreeTimeRange> freeTimeRanges) {
        int duration = (int) Duration.between(unitCtx.beginTime, unitCtx.endTime).toMinutes();

        BigDecimal finalAmount = determineFinalAmount(unitCtx, config, duration);

        // 检查是否被免费时段覆盖
        FreeTimeRange coveringRange = findCoveringFreeRange(unitCtx.beginTime, unitCtx.endTime, freeTimeRanges);
        String freePromotionId = coveringRange != null ? coveringRange.getId() : null;
        boolean isFree = freePromotionId != null;
        boolean conditionalFree = isFree && coveringRange.isConditional();

        BigDecimal unitPrice;
        BigDecimal originalAmount;
        BigDecimal chargedAmount;
        UnitValueSpec valueSpec;
        if (isFree && !conditionalFree) {
            unitPrice = BigDecimal.ZERO;
            originalAmount = BigDecimal.ZERO;
            chargedAmount = BigDecimal.ZERO;
            valueSpec = valueSpecFactory.createFreeSpec();
        } else {
            unitPrice = finalAmount;
            originalAmount = finalAmount;
            chargedAmount = finalAmount;
            if (conditionalFree) {
                valueSpec = valueSpecFactory.createConditionalFreeSpec(coveringRange.getConditionalUntil(), finalAmount);
            } else {
                valueSpec = valueSpecFactory.createRegularSpec(unitCtx.periodType, unitCtx.beginTime, unitCtx.endTime, config, finalAmount);
            }
        }

        BillingUnit unit = BillingUnit.builder()
                .beginTime(unitCtx.beginTime)
                .endTime(unitCtx.endTime)
                .durationMinutes(duration)
                .unitPrice(unitPrice)
                .originalAmount(originalAmount)
                .free(isFree && !conditionalFree)
                .freePromotionId(freePromotionId)
                .chargedAmount(chargedAmount)
                .valueSpec(valueSpec)
                .ruleData(unitCtx.cycleIndex) // 用ruleData存储周期序号
                .build();

        return unit;
    }

    private BigDecimal determineFinalAmount(UnitWithContext unitCtx, DayNightConfig config, int duration) {
        return priceResolver.determineFinalAmount(unitCtx.periodType, unitCtx.dayMinutes, duration, config, unitCtx.beginTime, unitCtx.endTime);
    }

    /**
     * 查找完全覆盖该时段的免费优惠
     */
    private String findFreePromotionId(LocalDateTime begin, LocalDateTime end, List<FreeTimeRange> freeTimeRanges) {
        FreeTimeRange range = findCoveringFreeRange(begin, end, freeTimeRanges);
        return range != null ? range.getId() : null;
    }

    private FreeTimeRange findCoveringFreeRange(LocalDateTime begin, LocalDateTime end, List<FreeTimeRange> freeTimeRanges) {
        for (FreeTimeRange range : freeTimeRanges) {
            if (!range.getBeginTime().isAfter(begin) && !range.getEndTime().isBefore(end)) {
                return range;
            }
        }
        return null;
    }

    /**
     * 应用每日封顶
     * 累计到封顶后，剩余单元标记为免费
     */
    private void applyDailyCap(List<BillingUnit> units, DayNightConfig config) {
        BigDecimal maxCharge = config.getMaxChargeOneDay();

        // 按周期分组
        int maxCycleIndex = units.stream()
                .mapToInt(u -> (Integer) u.getRuleData())
                .max().orElse(0);

        for (int cycle = 0; cycle <= maxCycleIndex; cycle++) {
            final int cycleIdx = cycle;
            List<BillingUnit> cycleUnits = units.stream()
                    .filter(u -> (Integer) u.getRuleData() == cycleIdx)
                    .toList();

            BigDecimal accumulated = BigDecimal.ZERO;
            int capIndex = -1;
            BigDecimal lastChargeAmount = null;

            // 找到封顶位置
            for (int i = 0; i < cycleUnits.size(); i++) {
                BillingUnit unit = cycleUnits.get(i);
                if (unit.isFree()) {
                    continue;
                }

                BigDecimal newAccumulated = accumulated.add(unit.getChargedAmount());

                if (newAccumulated.compareTo(maxCharge) >= 0) {
                    // 超过封顶
                    capIndex = i;
                    lastChargeAmount = maxCharge.subtract(accumulated);
                    break;
                }

                accumulated = newAccumulated;
            }

            if (capIndex >= 0) {
                // 调整封顶单元的金额
                cycleUnits.get(capIndex).setChargedAmount(lastChargeAmount.setScale(2, RoundingMode.HALF_UP));
                if (cycleUnits.get(capIndex).getChargedAmount().compareTo(BigDecimal.ZERO) == 0) {
                    cycleUnits.get(capIndex).setFree(true);
                    cycleUnits.get(capIndex).setFreePromotionId("DAILY_CAP");
                }

                // 封顶后的单元标记为免费
                for (int i = capIndex + 1; i < cycleUnits.size(); i++) {
                    BillingUnit unit = cycleUnits.get(i);
                    if (!unit.isFree()) {
                        unit.setChargedAmount(BigDecimal.ZERO);
                        unit.setFree(true);
                        unit.setFreePromotionId("DAILY_CAP");
                    }
                }
            }
        }
    }

    /**
     * 应用每日封顶（考虑结转的累计金额）
     * 累计到封顶后，剩余单元标记为免费
     * @return 最后一个周期的累计金额
     */
    private BigDecimal applyDailyCapWithCarryOver(List<BillingUnit> units, DayNightConfig config, BigDecimal carryOverAccumulated) {
        BigDecimal maxCharge = config.getMaxChargeOneDay();

        // 按周期分组
        int maxCycleIndex = units.stream()
                .mapToInt(u -> (Integer) u.getRuleData())
                .max().orElse(0);

        BigDecimal accumulated = carryOverAccumulated;
        BigDecimal lastCycleAccumulated = BigDecimal.ZERO;

        for (int cycle = 0; cycle <= maxCycleIndex; cycle++) {
            final int cycleIdx = cycle;
            List<BillingUnit> cycleUnits = units.stream()
                    .filter(u -> (Integer) u.getRuleData() == cycleIdx)
                    .toList();

            // 如果继承的累计金额已达到封顶，将所有收费单元标记为免费
            if (accumulated.compareTo(maxCharge) >= 0) {
                for (BillingUnit unit : cycleUnits) {
                    if (!unit.isFree()) {
                        unit.setChargedAmount(BigDecimal.ZERO);
                        unit.setFree(true);
                        unit.setFreePromotionId("DAILY_CAP");
                    }
                }
                lastCycleAccumulated = maxCharge;
            } else {
                // 找到封顶位置
                int capIndex = -1;
                BigDecimal lastChargeAmount = null;

                for (int i = 0; i < cycleUnits.size(); i++) {
                    BillingUnit unit = cycleUnits.get(i);
                    if (unit.isFree()) {
                        continue;
                    }

                    BigDecimal newAccumulated = accumulated.add(unit.getChargedAmount());

                    if (newAccumulated.compareTo(maxCharge) >= 0) {
                        // 超过封顶
                        capIndex = i;
                        lastChargeAmount = maxCharge.subtract(accumulated);
                        break;
                    }

                    accumulated = newAccumulated;
                }

                if (capIndex >= 0) {
                    // 调整封顶单元的金额
                    cycleUnits.get(capIndex).setChargedAmount(lastChargeAmount.setScale(2, RoundingMode.HALF_UP));
                    if (cycleUnits.get(capIndex).getChargedAmount().compareTo(BigDecimal.ZERO) == 0) {
                        cycleUnits.get(capIndex).setFree(true);
                        cycleUnits.get(capIndex).setFreePromotionId("DAILY_CAP");
                    }

                    // 封顶后的单元标记为免费
                    for (int i = capIndex + 1; i < cycleUnits.size(); i++) {
                        BillingUnit unit = cycleUnits.get(i);
                        if (!unit.isFree()) {
                            unit.setChargedAmount(BigDecimal.ZERO);
                            unit.setFree(true);
                            unit.setFreePromotionId("DAILY_CAP");
                        }
                    }

                    lastCycleAccumulated = maxCharge;
                } else {
                    // 未超过封顶
                    lastCycleAccumulated = accumulated;
                }
            }

            // 重置累计金额（新周期）
            accumulated = BigDecimal.ZERO;
        }

        return lastCycleAccumulated;
    }

    /**
     * 计算费用确定开始时间
     * = 最后一个计费单元的开始时间
     */
    private LocalDateTime calculateEffectiveFrom(List<BillingUnit> billingUnits) {
        if (billingUnits == null || billingUnits.isEmpty()) {
            return null;
        }
        return billingUnits.getLast().getBeginTime();
    }

    /**
     * 计算费用稳定结束时间
     * 取以下因素的最小值：
     * 1. 最后一个计费单元结束时间
     * 2. 如果最后一个单元在免费时段内，延伸到免费时段结束
     * 3. 下一个24小时周期边界
     * 4. 分段结束时间
     */
    private LocalDateTime calculateEffectiveTo(List<BillingUnit> billingUnits,
                                                List<FreeTimeRange> freeTimeRanges,
                                                LocalDateTime calcBegin,
                                                LocalDateTime calcEnd) {
        if (billingUnits == null || billingUnits.isEmpty()) {
            return null;
        }

        BillingUnit lastUnit = billingUnits.get(billingUnits.size() - 1);
        LocalDateTime effectiveTo = lastUnit.getEndTime();

        // 如果最后一个单元在免费时段内，延伸到免费时段结束
        if (lastUnit.isFree() && lastUnit.getFreePromotionId() != null) {
            FreeTimeRange coveringRange = findFreeTimeRangeById(lastUnit.getFreePromotionId(), freeTimeRanges);
            if (coveringRange != null && coveringRange.getEndTime().isAfter(effectiveTo)) {
                effectiveTo = coveringRange.getEndTime();
            }
        }

        // 检查下一个24小时周期边界
        // 周期从 calcBegin 开始，每个周期24小时
        LocalDateTime currentCycleEnd = calcBegin;
        while (currentCycleEnd.isBefore(effectiveTo) || currentCycleEnd.equals(effectiveTo)) {
            LocalDateTime nextCycleEnd = currentCycleEnd.plusHours(24);
            if (nextCycleEnd.isAfter(effectiveTo)) {
                // 找到下一个周期边界
                effectiveTo = nextCycleEnd.isBefore(effectiveTo) ? nextCycleEnd : effectiveTo;
                break;
            }
            currentCycleEnd = nextCycleEnd;
        }

        // 不超过分段结束时间
        if (calcEnd != null && effectiveTo.isAfter(calcEnd)) {
            effectiveTo = calcEnd;
        }

        return effectiveTo;
    }

    /**
     * 根据ID查找免费时段
     */
    private FreeTimeRange findFreeTimeRangeById(String id, List<FreeTimeRange> freeTimeRanges) {
        if (freeTimeRanges == null || id == null) {
            return null;
        }
        for (FreeTimeRange range : freeTimeRanges) {
            if (id.equals(range.getId())) {
                return range;
            }
        }
        return null;
    }

    /**
     * 查找下一个周期边界
     * @param current 当前时间点
     * @param calcBegin 计费起点
     * @return 下一个周期边界时间（24小时后）
     */
    private LocalDateTime findNextCycleBoundary(LocalDateTime current, LocalDateTime calcBegin) {
        // 找到包含 current 的周期起点
        LocalDateTime cycleStart = calcBegin;
        while (cycleStart.plusHours(24).isBefore(current) ||
               cycleStart.plusHours(24).equals(current)) {
            cycleStart = cycleStart.plusHours(24);
        }
        // 下一个周期边界
        return cycleStart.plusHours(24);
    }

    /**
     * CONTINUOUS 模式计算
     * 在免费时段边界切分时间轴，每个片段从片段起点重新按单元划分
     */
    BillingSegmentResult calculateContinuousInternal(BillingContext context, DayNightConfig config, PromotionAggregate promotionAggregate) {
        validateConfig(config);

        LocalDateTime calcBegin = context.getWindow().getCalculationBegin();
        LocalDateTime calcEnd = context.getWindow().getCalculationEnd();
        LocalDateTime cycleOriginBegin = context.getBeginTime();
        int unitMinutes = config.getUnitMinutes();

        // 恢复状态
        RuleState state = restoreState(context.getRuleState());
        if (state == null) {
            state = initializeState(calcBegin);
        } else {
            while (state.getCycleBoundary() != null && !calcBegin.isBefore(state.getCycleBoundary())) {
                state.setCycleIndex(state.getCycleIndex() + 1);
                state.setCycleAccumulated(BigDecimal.ZERO);
                state.setCycleBoundary(state.getCycleBoundary().plusMinutes(getCycleMinutes()));
            }
        }

        List<FreeTimeRange> rawFreeRanges = promotionAggregate.getFreeTimeRanges();
        if (rawFreeRanges == null) {
            rawFreeRanges = List.of();
        }
        final List<FreeTimeRange> freeTimeRanges = rawFreeRanges;
        BigDecimal maxCharge = config.getMaxChargeOneDay();

        // 简化计算检查：周期数超过阈值且存在无优惠周期时，走简化路径
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
            // 简化路径：按周期组织，无优惠周期合并为简化单元
            List<TimeFragment> fragments = splitTimeAxis(calcBegin, calcEnd, freeTimeRanges);
            List<CycleFragments> cycles = organizeByCycle(calcBegin, calcEnd, fragments, cycleOriginBegin);
            Set<Integer> cyclesWithPromotion = findCyclesWithPromotion(calcBegin, calcEnd, promotionAggregate);
            if (cyclesWithPromotion != null && !cycles.isEmpty()) {
                allUnits = generateSimplifiedUnitsForContinuous(cycles, cyclesWithPromotion,
                    threshold, config, calcBegin, cycleOriginBegin, state);
                BillingUnit lastUnit = allUnits.get(allUnits.size() - 1);
                int bubbleExtension = calculateBubbleExtension(freeTimeRanges, calcBegin, calcEnd);
                if (isSimplifiedUnit(lastUnit)) {
                    cn.shang.charging.billing.pojo.SimplifiedUnitMeta meta =
                            cn.shang.charging.billing.pojo.SimplifiedUnitMeta.from(lastUnit);
                    if (meta != null) {
                        state.setCycleAccumulated(meta.simplifiedCycleAmount());
                        state.setCycleIndex(state.getCycleIndex() + cycles.size() - 1);
                        if (state.getCycleBoundary() != null) {
                            int traversedCycleMinutes = Math.max(0, cycles.size() - 1) * getCycleMinutes();
                            state.setCycleBoundary(state.getCycleBoundary().plusMinutes(traversedCycleMinutes + bubbleExtension));
                        } else {
                            state.setCycleBoundary(cycles.get(cycles.size() - 1).cycleStart.plusHours(24).plusMinutes(bubbleExtension));
                        }
                    }
                } else {
                    LocalDateTime lastCycleEnd = cycles.get(cycles.size() - 1).cycleEnd;
                    BigDecimal lastCycleAmount = allUnits.stream()
                            .filter(u -> !u.isFree() && u.getEndTime().compareTo(lastCycleEnd) <= 0
                                    && u.getEndTime().compareTo(cycles.get(cycles.size() - 1).cycleStart) > 0)
                            .map(BillingUnit::getChargedAmount)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    state.setCycleAccumulated(lastCycleAmount);
                    state.setCycleIndex(state.getCycleIndex() + cycles.size() - 1);
                    if (state.getCycleBoundary() != null) {
                        state.setCycleBoundary(state.getCycleBoundary().plusMinutes(bubbleExtension));
                    } else {
                        state.setCycleBoundary(cycles.get(cycles.size() - 1).cycleStart.plusHours(24).plusMinutes(bubbleExtension));
                    }
                }
                usedSimplification = true;
            } else {
                allUnits = new ArrayList<>();
            }
        } else {
            allUnits = new ArrayList<>();
        }

        if (!usedSimplification) {
            // 边界驱动路径
            int dayBeginMin = config.getDayBeginMinute();
            int dayEndMin = config.getDayEndMinute();

            // 边界来源：周期结束 + 日夜时段边界 + 免费时段起止 + 条件免费窗口 + 单元对齐 + calcEnd
            List<BoundaryProvider> providers = new ArrayList<>();
            providers.add(BoundaryProviders.cycleEnd(cycleOriginBegin, getCycleMinutes()));
            // 日夜时段边界：每天 dayBeginMinute / dayEndMinute 翻转
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
            // 条件免费窗口结束边界（conditionalUntil）：条件免费段在此处切换计费语义
            providers.add((current, end) -> {
                List<LocalDateTime> result = new ArrayList<>();
                for (FreeTimeRange range : freeTimeRanges) {
                    if (range.isConditional() && range.getConditionalUntil() != null) {
                        LocalDateTime cu = range.getConditionalUntil();
                        if (cu.isAfter(current) && !cu.isAfter(end)) {
                            result.add(cu);
                        }
                    }
                }
                return result;
            });
            // 单元对齐
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

            // 边界驱动循环
            List<HomogeneousSegment> segments = runBoundaryDrivenLoop(calcBegin, calcEnd, providers,
                    (current, next) -> buildSegmentForDayNight(current, next, config, freeTimeRanges, calcEnd));

            // 应用封顶 + 累计 + 截断标记
            allUnits = applyCapAndAccumulate(segments, maxCharge, context, unitMinutes);

            // 边界驱动状态更新
            int bubbleExtension = calculateBubbleExtension(freeTimeRanges, calcBegin, calcEnd);
            if (state.getCycleBoundary() != null) {
                state.setCycleBoundary(state.getCycleBoundary().plusMinutes(bubbleExtension));
            } else {
                long offsetFromOrigin = Duration.between(cycleOriginBegin, calcEnd).toMinutes();
                long cycles = offsetFromOrigin / getCycleMinutes() + 1;
                state.setCycleBoundary(cycleOriginBegin.plusMinutes(cycles * getCycleMinutes()).plusMinutes(bubbleExtension));
            }
        }

        BigDecimal totalAmount = allUnits.stream()
                .map(BillingUnit::getChargedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        LocalDateTime feeEffectiveStart = calculateEffectiveFrom(allUnits);
        LocalDateTime feeEffectiveEnd = calculateEffectiveTo(allUnits, freeTimeRanges, calcBegin, calcEnd);

        // 标记最后一个单元是否被截断
        if (!allUnits.isEmpty()) {
            BillingUnit lastUnit = allUnits.get(allUnits.size() - 1);
            if (lastUnit.getDurationMinutes() < unitMinutes && lastUnit.getEndTime().equals(calcEnd)) {
                lastUnit.setIsTruncated(true);
            }
        }

        // 计算累计金额
        BigDecimal accumulatedAmount = context.getPreviousAccumulatedAmount();
        if (accumulatedAmount == null) {
            accumulatedAmount = BigDecimal.ZERO;
        }
        BigDecimal truncatedUnitChargedAmount = context.getTruncatedUnitChargedAmount();
        if (truncatedUnitChargedAmount != null && !allUnits.isEmpty()) {
            accumulatedAmount = accumulatedAmount.subtract(truncatedUnitChargedAmount);
            if (accumulatedAmount.compareTo(BigDecimal.ZERO) < 0) {
                accumulatedAmount = BigDecimal.ZERO;
            }
        }
        for (BillingUnit unit : allUnits) {
            accumulatedAmount = accumulatedAmount.add(unit.getChargedAmount());
            unit.setAccumulatedAmount(accumulatedAmount);
        }

        Map<String, Object> ruleOutputState = buildRuleOutputState(state);

        return BillingSegmentResult.builder()
                .segmentId(context.getSegment().getId())
                .segmentStartTime(context.getSegment().getBeginTime())
                .segmentEndTime(context.getSegment().getEndTime())
                .calculationStartTime(calcBegin)
                .calculationEndTime(calcEnd)
                .chargedAmount(totalAmount)
                .billingUnits(allUnits)
                .promotionUsages(new ArrayList<>())
                .promotionAggregate(promotionAggregate)
                .feeEffectiveStart(feeEffectiveStart)
                .feeEffectiveEnd(feeEffectiveEnd)
                .ruleOutputState(ruleOutputState)
                .build();
    }

    /**
     * 把同质段列表转换为 BillingUnit 列表，并应用封顶、累计金额、截断标记。
     */
    private List<BillingUnit> applyCapAndAccumulate(List<HomogeneousSegment> segments,
                                                     BigDecimal maxCharge,
                                                     BillingContext context,
                                                     int unitMinutes) {
        List<BillingUnit> units = new ArrayList<>();
        if (segments.isEmpty()) return units;

        LocalDateTime calcEnd = context.getWindow().getCalculationEnd();
        BigDecimal dayAccumulated = BigDecimal.ZERO;

        BigDecimal accumulated = context.getPreviousAccumulatedAmount();
        if (accumulated == null) accumulated = BigDecimal.ZERO;
        BigDecimal truncatedDeduction = context.getTruncatedUnitChargedAmount();

        for (int i = 0; i < segments.size(); i++) {
            HomogeneousSegment seg = segments.get(i);
            boolean isLast = (i == segments.size() - 1);
            int segMinutes = seg.durationMinutes();
            int subCount = unitMinutes > 0 ? segMinutes / unitMinutes : 1;
            if (subCount < 1) subCount = 1;

            boolean cycleCapped = false;
            if (maxCharge != null && !seg.isFree() && dayAccumulated.compareTo(maxCharge) >= 0) {
                cycleCapped = true;
            }

            BigDecimal originalPerSub = seg.getOriginalAmount() != null
                    ? seg.getOriginalAmount() : BigDecimal.ZERO;
            BigDecimal unitPrice = seg.getUnitPrice() != null ? seg.getUnitPrice() : BigDecimal.ZERO;

            BigDecimal charged;
            if (seg.isFree() || cycleCapped) {
                charged = BigDecimal.ZERO;
            } else {
                BigDecimal budget = maxCharge != null
                        ? maxCharge.subtract(dayAccumulated)
                        : null;
                if (budget != null && budget.signum() < 0) budget = BigDecimal.ZERO;
                BigDecimal fullTotal = originalPerSub.multiply(BigDecimal.valueOf(subCount));
                if (budget != null && fullTotal.compareTo(budget) > 0) {
                    charged = budget.setScale(2, RoundingMode.HALF_UP);
                } else {
                    charged = fullTotal;
                }
            }

            if (truncatedDeduction != null && i == 0) {
                BigDecimal adjusted = charged.subtract(truncatedDeduction);
                if (adjusted.signum() < 0) adjusted = BigDecimal.ZERO;
                charged = adjusted;
            }

            accumulated = accumulated.add(charged);
            if (!seg.isFree() && !cycleCapped) {
                dayAccumulated = dayAccumulated.add(charged);
            }

            boolean isTruncated = isLast
                    && unitMinutes > 0
                    && segMinutes < unitMinutes
                    && seg.getEndTime().equals(calcEnd);
            boolean isCompact = !isTruncated && subCount > 1;

            // 解析 valueSpec
            UnitValueSpec spec;
            if (seg.getValueSpec() instanceof UnitValueSpec us) {
                spec = us;
            } else if (seg.isFree()) {
                spec = new FixedValueSpec(BigDecimal.ZERO);
            } else {
                spec = new FixedValueSpec(unitPrice);
            }
            // 封顶削减时覆盖 valueSpec：query 投影应返回封顶后金额，而非原价
            boolean cappedOrReduced = cycleCapped
                    || (charged.compareTo(originalPerSub.multiply(BigDecimal.valueOf(subCount))) < 0
                        && !seg.isFree());
            if (cappedOrReduced) {
                spec = new FixedValueSpec(charged);
            }

            BillingUnit unit = BillingUnit.builder()
                    .beginTime(seg.getBeginTime())
                    .endTime(seg.getEndTime())
                    .durationMinutes(segMinutes)
                    .unitPrice(unitPrice)
                    .originalAmount(originalPerSub.multiply(BigDecimal.valueOf(subCount)))
                    .free(seg.isFree() || cycleCapped)
                    .freePromotionId(cycleCapped ? "DAILY_CAP" : seg.getFreePromotionId())
                    .chargedAmount(charged)
                    .accumulatedAmount(accumulated)
                    .valueSpec(spec)
                    .ruleData(seg.getRuleData())
                    .compact(isCompact)
                    .count(isCompact ? subCount : 1)
                    .isTruncated(isTruncated)
                    .build();
            units.add(unit);

            // 24h 周期切换（按 segment.endTime 落在哪一天切换）
            // DayNight 用自然日：跨午夜就重置
            if (seg.getEndTime().getDayOfYear() != seg.getBeginTime().getDayOfYear()
                    || seg.getEndTime().getYear() != seg.getBeginTime().getYear()) {
                dayAccumulated = BigDecimal.ZERO;
            }
        }
        return units;
    }

    /**
     * 为 CONTINUOUS 模式生成简化单元
     */
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
        BigDecimal carryOverAccumulated = state.getCycleAccumulated();

        for (int cycleIdx = 0; cycleIdx < cycles.size(); cycleIdx++) {
            CycleFragments cycle = cycles.get(cycleIdx);
            // 计算周期索引（基于原始计费起点）
            int cycleIndex = (int) Duration.between(cycleOriginBegin, cycle.cycleStart).toMinutes() / cycleMinutes;

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
                        List<BillingUnit> cycleUnits = generateUnitsForSingleCycle(i, calcBegin, cycle.cycleEnd, config, List.of());
                        allUnits.addAll(cycleUnits);
                    }
                }
                consecutiveSimplified = 0;

                // 生成当前有优惠周期的详细单元
                List<BillingUnit> cycleUnits = generateUnitsForCycle(cycle, config);
                cycleStateManager.applyDailyCapWithCarryOver(
                        cycleUnits,
                        config,
                        carryOverAccumulated,
                        (beginTime, endTime) -> valueSpecFactory.createCappedSpec(
                                cycleUnits.stream()
                                        .filter(u -> beginTime.equals(u.getBeginTime()) && endTime.equals(u.getEndTime()))
                                        .findFirst()
                                        .map(BillingUnit::getValueSpec)
                                        .orElse(null),
                                beginTime,
                                endTime,
                                cycleUnits.stream()
                                        .filter(u -> beginTime.equals(u.getBeginTime()) && endTime.equals(u.getEndTime()))
                                        .findFirst()
                                        .map(BillingUnit::getChargedAmount)
                                        .orElse(BigDecimal.ZERO)
                        )
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
                List<BillingUnit> cycleUnits = generateUnitsForSingleCycle(i, calcBegin, cycles.get(cycles.size() - 1).cycleEnd, config, List.of());
                allUnits.addAll(cycleUnits);
            }
        }

        return allUnits;
    }

    @Override
    protected TimeFragment createFragment(LocalDateTime beginTime, LocalDateTime endTime) {
        return new DayNightTimeFragment(beginTime, endTime);
    }

    /**
     * 为一个周期生成计费单元
     */
    private List<BillingUnit> generateUnitsForCycle(CycleFragments cycle, DayNightConfig config) {
        List<BillingUnit> units = new ArrayList<>();
        int unitMinutes = config.getUnitMinutes();
        for (TimeFragment fragment : cycle.fragments) {
            if (fragment.isFree) {
                if (fragment.isConditional()) {
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
                                .freePromotionId(fragment.freePromotionId)
                                .chargedAmount(originalAmount)
                                .valueSpec(new StepValueSpec(fragment.getConditionalUntil(), BigDecimal.ZERO, originalAmount))
                                .build();

                        units.add(unit);
                        current = unitEnd;
                    }
                } else {
                    // 免费片段直接生成一个免费单元
                    BillingUnit unit = BillingUnit.builder()
                            .beginTime(fragment.beginTime)
                            .endTime(fragment.endTime)
                            .durationMinutes((int) Duration.between(fragment.beginTime, fragment.endTime).toMinutes())
                            .unitPrice(BigDecimal.ZERO)
                            .originalAmount(BigDecimal.ZERO)
                            .free(true)
                            .freePromotionId(fragment.freePromotionId)
                            .chargedAmount(BigDecimal.ZERO)
                            .valueSpec(new FixedValueSpec(BigDecimal.ZERO))
                            .build();
                    units.add(unit);
                }
            } else {
                // 收费片段按单元长度划分
                LocalDateTime current = fragment.beginTime;
                while (current.isBefore(fragment.endTime)) {
                    LocalDateTime pricingEnd = resolvePricingEnd(current, unitMinutes, cycle.cycleEnd);
                    LocalDateTime unitEnd = pricingEnd;
                    if (unitEnd.isAfter(fragment.endTime)) {
                        unitEnd = fragment.endTime;
                    }

                    int duration = (int) Duration.between(current, unitEnd).toMinutes();

                    // 确定单价
                    BigDecimal unitPrice = determineUnitPriceForContinuous(current, pricingEnd, config);

                    // 不足单元也收全额
                    BigDecimal originalAmount = unitPrice;
                    DayNightPeriodType periodType = priceResolver.determinePeriodType(current, pricingEnd, config);
                    UnitValueSpec valueSpec = valueSpecFactory.createRegularSpec(
                            periodType,
                            current,
                            pricingEnd,
                            config,
                            originalAmount
                    );

                    BillingUnit unit = BillingUnit.builder()
                            .beginTime(current)
                            .endTime(unitEnd)
                            .durationMinutes(duration)
                            .unitPrice(unitPrice)
                            .originalAmount(originalAmount)
                            .free(false)
                            .chargedAmount(originalAmount)
                            .valueSpec(valueSpec)
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

    /**
     * 确定时段单价（CONTINUOUS模式专用）
     */
    private BigDecimal determineUnitPriceForContinuous(LocalDateTime begin, LocalDateTime end, DayNightConfig config) {
        return priceResolver.determineUnitPriceForContinuous(begin, end, config);
    }

    /**
     * 内部上下文类，存放计算过程中的专用数据
     */
    private static class UnitWithContext {
        LocalDateTime beginTime;
        LocalDateTime endTime;
        int cycleIndex;
        DayNightPeriodType periodType;
        int dayMinutes;
        int nightMinutes;
    }

    /**
     * DayNight 专用时间片段，在公共 {@link TimeFragment} 基础上携带条件免费信息。
     * <p>
     * {@link #copy(LocalDateTime, LocalDateTime)} 不覆盖，沿用基类实现：周期边界切分产生的
     * beforeBoundary 为基类 TimeFragment（isConditional()=false），与历史行为一致。
     */
    private static class DayNightTimeFragment extends TimeFragment {
        private boolean conditional;
        private LocalDateTime conditionalUntil;

        DayNightTimeFragment(LocalDateTime beginTime, LocalDateTime endTime) {
            super(beginTime, endTime);
        }

        @Override
        public void applyFreeRange(FreeTimeRange range) {
            super.applyFreeRange(range);
            this.conditional = range.isConditional();
            this.conditionalUntil = range.getConditionalUntil();
        }

        @Override
        public boolean isConditional() {
            return conditional;
        }

        @Override
        public LocalDateTime getConditionalUntil() {
            return conditionalUntil;
        }
    }

    /**
     * 边界驱动循环的段构造回调。
     */
    private HomogeneousSegment buildSegmentForDayNight(LocalDateTime current,
                                                       LocalDateTime next,
                                                       DayNightConfig config,
                                                       List<FreeTimeRange> freeTimeRanges,
                                                       LocalDateTime calcEnd) {
        // pricingEnd 取 current + unitMinutes，但截断到 calcEnd（与 HEAD resolvePricingEnd 截断到 cycleEnd 一致）：
        // 不足单元也按完整单元计价（收全额），但被 calcEnd 截断的 truncated 单元按实际时段计价
        LocalDateTime pricingEnd = current.plusMinutes(config.getUnitMinutes());
        if (pricingEnd.isAfter(calcEnd)) {
            pricingEnd = calcEnd;
        }
        for (FreeTimeRange range : freeTimeRanges) {
            if (!range.getBeginTime().isAfter(current) && !range.getEndTime().isBefore(next)) {
                if (range.isConditional()) {
                    // 条件免费段：窗口内免费、窗口外收费，按完整单元计价
                    BigDecimal unitPrice = priceResolver.determineUnitPriceForContinuous(current, pricingEnd, config);
                    return new HomogeneousSegment(current, next, unitPrice, unitPrice,
                            false, range.getId(),
                            new StepValueSpec(range.getConditionalUntil(), BigDecimal.ZERO, unitPrice),
                            null);
                }
                return new HomogeneousSegment(current, next, BigDecimal.ZERO, BigDecimal.ZERO,
                        true, range.getId(), null, null);
            }
        }
        BigDecimal unitPrice = priceResolver.determineUnitPriceForContinuous(current, pricingEnd, config);
        DayNightPeriodType periodType = priceResolver.determinePeriodType(current, pricingEnd, config);
        UnitValueSpec spec = valueSpecFactory.createRegularSpec(periodType, current, pricingEnd, config, unitPrice);
        return new HomogeneousSegment(current, next, unitPrice, unitPrice,
                false, null, spec, null);
    }
}
