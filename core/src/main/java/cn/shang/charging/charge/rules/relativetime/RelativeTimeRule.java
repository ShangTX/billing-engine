package cn.shang.charging.charge.rules.relativetime;

import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.BillingContext;
import cn.shang.charging.billing.pojo.BillingSegmentResult;
import cn.shang.charging.billing.pojo.BillingUnit;
import cn.shang.charging.billing.pojo.CalculationWindow;
import cn.shang.charging.charge.rules.AbstractTimeBasedRule;
import cn.shang.charging.charge.rules.BillingRule;
import cn.shang.charging.promotion.pojo.FreeTimeRange;
import cn.shang.charging.promotion.pojo.PromotionAggregate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 按相对时间段计费规则
 * <p>
 * 核心逻辑：
 * 1. 从计费起点开始，按 24 小时划分周期
 * 2. 每个周期内按配置的时间段划分，每个时间段可有不同的单元长度和单价
 * 3. 计费单元在时间段边界会被截断，不足一个单元长度的部分收全额
 * 4. 每个周期独立封顶，超出时从最后一个单元开始削减
 * 5. 免费时段完全覆盖计费单元才免费
 */
public class RelativeTimeRule extends AbstractTimeBasedRule<RelativeTimeConfig> {

    private static final int MINUTES_PER_CYCLE = 1440; // 24小时 = 1440分钟

    // 规则类型标识（用于 ruleState Map 的 key）
    private static final String RULE_TYPE = "relativeTime";

    @Override
    protected String getRuleType() {
        return RULE_TYPE;
    }

    @Override
    protected boolean hasComplexFeatures(RelativeTimeConfig config) {
        // RelativeTimeRule 无时间段封顶等复杂特性
        return false;
    }

    @Override
    protected boolean isSimplifiedSupported(RelativeTimeConfig config) {
        // RelativeTimeRule 支持简化计算
        return true;
    }

    @Override
    protected BigDecimal getCycleCapAmount(RelativeTimeConfig config) {
        return config.getMaxChargeOneCycle();
    }

    @Override
    public Class<RelativeTimeConfig> configClass() {
        return RelativeTimeConfig.class;
    }

    @Override
    public Set<BConstants.BillingMode> supportedModes() {
        return EnumSet.of(BConstants.BillingMode.CONTINUOUS, BConstants.BillingMode.UNIT_BASED);
    }

    @Override
    public BillingSegmentResult calculate(BillingContext context, RelativeTimeConfig config, PromotionAggregate promotionAggregate) {
        if (context.getBillingMode() == BConstants.BillingMode.UNIT_BASED) {
            return calculateUnitBased(context, config, promotionAggregate);
        } else {
            return calculateContinuous(context, config, promotionAggregate);
        }
    }

    /**
     * UNIT_BASED 模式计算
     * 固定从计费起点对齐，免费时段必须完全覆盖整个单元才免费
     */
    private BillingSegmentResult calculateUnitBased(BillingContext context, RelativeTimeConfig config, PromotionAggregate promotionAggregate) {
        // 验证配置
        validateConfig(config);

        // 获取计算窗口
        CalculationWindow window = context.getWindow();
        LocalDateTime calcBegin = window.getCalculationBegin();
        LocalDateTime calcEnd = window.getCalculationEnd();

        // 检查是否启用简化计算
        boolean simplificationEnabled = context.getBillingConfigResolver() != null
            && isSimplificationEnabled(config, context.getBillingConfigResolver());

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
            // 如果 calcBegin >= cycleBoundary，说明已经进入新周期
            while (state.getCycleBoundary() != null && !calcBegin.isBefore(state.getCycleBoundary())) {
                state.setCycleIndex(state.getCycleIndex() + 1);
                state.setCycleAccumulated(BigDecimal.ZERO);
                state.setCycleBoundary(state.getCycleBoundary().plusMinutes(getCycleMinutes()));
            }
        }

        // 获取免费时段
        List<FreeTimeRange> freeTimeRanges = promotionAggregate != null && promotionAggregate.getFreeTimeRanges() != null
                ? promotionAggregate.getFreeTimeRanges()
                : List.of();

        // 构建计费单元（按周期组织），传入初始累计金额
        List<CycleUnits> cycles = buildBillingUnitsWithState(calcBegin, calcEnd, config, freeTimeRanges, state);

        // 汇总结果
        List<BillingUnit> allUnits = new ArrayList<>();
        for (CycleUnits cycle : cycles) {
            allUnits.addAll(cycle.units);
        }

        BigDecimal totalAmount = allUnits.stream()
                .map(BillingUnit::getChargedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 更新最终状态（最后一个周期的状态）
        if (!cycles.isEmpty()) {
            CycleUnits lastCycle = cycles.get(cycles.size() - 1);
            // 更新周期索引（CONTINUE 模式需要累加）
            if (context.getContinueMode() == BConstants.ContinueMode.CONTINUE) {
                state.setCycleIndex(state.getCycleIndex() + cycles.size() - 1);
            } else {
                state.setCycleIndex(cycles.size() - 1);
            }
            state.setCycleBoundary(lastCycle.cycleStart.plusMinutes(MINUTES_PER_CYCLE));
            // 使用存储的累计金额（包含之前累计的）
            if (lastCycle.accumulatedBeforeCap != null) {
                state.setCycleAccumulated(lastCycle.accumulatedBeforeCap);
            } else {
                // 如果没有封顶逻辑，从单元计算
                BigDecimal lastCycleAmount = lastCycle.units.stream()
                        .map(BillingUnit::getChargedAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                state.setCycleAccumulated(lastCycleAmount);
            }
        }

        // 计算费用稳定时间窗口
        LocalDateTime feeEffectiveStart = calculateEffectiveFrom(allUnits);
        LocalDateTime feeEffectiveEnd = calculateEffectiveTo(allUnits, freeTimeRanges, calcBegin, calcEnd);

        // 标记最后一个单元是否被截断
        if (!allUnits.isEmpty()) {
            BillingUnit lastUnit = allUnits.get(allUnits.size() - 1);
            // 获取最后一个单元对应的单元长度
            int minutesFromCalcBegin = (int) Duration.between(calcBegin, lastUnit.getBeginTime()).toMinutes();
            RelativeTimePeriod period = findPeriodForMinute(minutesFromCalcBegin, config.getPeriods());
            int unitMinutes = period.getUnitMinutes();
            if (lastUnit.getDurationMinutes() < unitMinutes && lastUnit.getEndTime().equals(calcEnd)) {
                lastUnit.setIsTruncated(true);
            }
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
                .billingUnits(allUnits)
                .promotionUsages(new ArrayList<>())
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
            RelativeTimeConfig config,
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
        applyCapWithCarryOverForSimplified(billingUnits, config, state.getCycleAccumulated());

        // 更新状态 - 使用实际处理的最后一个周期索引
        if (!billingUnits.isEmpty()) {
            BillingUnit lastUnit = billingUnits.get(billingUnits.size() - 1);
            if (isSimplifiedUnit(lastUnit)) {
                // 简化单元：从 ruleData 揎取周期信息
                @SuppressWarnings("unchecked")
                Map<String, Object> ruleData = (Map<String, Object>) lastUnit.getRuleData();
                int beginCycleIndex = (Integer) ruleData.get("cycleIndex");
                int cycleCount = (Integer) ruleData.get("simplifiedCycleCount");
                BigDecimal cycleAmount = (BigDecimal) ruleData.get("simplifiedCycleAmount");
                state.setCycleIndex(beginCycleIndex + cycleCount - 1);
                state.setCycleAccumulated(cycleAmount);
                state.setCycleBoundary(getCycleBoundary(beginCycleIndex + cycleCount, calcBegin));
            } else {
                // 非简化单元：使用最后一个单元的周期信息
                int lastCycleIndex = extractCycleIndex(lastUnit);
                state.setCycleIndex(lastCycleIndex);
                // 计算最后一个周期的累计金额
                final int finalLastCycleIndex = lastCycleIndex;
                BigDecimal lastCycleAccumulated = billingUnits.stream()
                        .filter(u -> !isSimplifiedUnit(u) && extractCycleIndex(u) == finalLastCycleIndex)
                        .map(BillingUnit::getChargedAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                state.setCycleAccumulated(lastCycleAccumulated);
                state.setCycleBoundary(getCycleBoundary(lastCycleIndex + 1, calcBegin));
            }
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
                int minutesFromCalcBegin = (int) Duration.between(calcBegin, lastUnit.getBeginTime()).toMinutes();
                RelativeTimePeriod period = findPeriodForMinute(minutesFromCalcBegin % MINUTES_PER_CYCLE, config.getPeriods());
                int unitMinutes = period.getUnitMinutes();
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
            RelativeTimeConfig config,
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

        // 使用临时的 CycleUnits 容器生成单元
        CycleUnits cycle = new CycleUnits(cycleStart, cycleEnd);

        // 在当前周期内按时间段生成计费单元
        for (RelativeTimePeriod period : config.getPeriods()) {
            generateUnitsInPeriod(cycle, period, freeTimeRanges);
        }

        // 为每个单元设置周期索引
        for (BillingUnit unit : cycle.units) {
            unit.setRuleData(cycleIndex);
        }

        return cycle.units;
    }

    /**
     * 从 BillingUnit 中提取周期索引
     */
    private int extractCycleIndex(BillingUnit unit) {
        if (unit.getRuleData() instanceof Integer) {
            return (Integer) unit.getRuleData();
        }
        return 0;
    }

    /**
     * 应用封顶（针对简化计算结果）
     */
    private void applyCapWithCarryOverForSimplified(List<BillingUnit> units, RelativeTimeConfig config, BigDecimal carryOverAccumulated) {
        BigDecimal maxCharge = config.getMaxChargeOneCycle();

        // 按周期分组处理
        Map<Integer, List<BillingUnit>> cycleGroups = new LinkedHashMap<>();
        for (BillingUnit unit : units) {
            int cycleIndex = isSimplifiedUnit(unit) ? getSimplifiedCycleIndex(unit) : extractCycleIndex(unit);
            cycleGroups.computeIfAbsent(cycleIndex, k -> new ArrayList<>()).add(unit);
        }

        BigDecimal accumulated = carryOverAccumulated;

        for (Map.Entry<Integer, List<BillingUnit>> entry : cycleGroups.entrySet()) {
            List<BillingUnit> cycleUnits = entry.getValue();

            // 检查是否为简化单元
            if (cycleUnits.size() == 1 && isSimplifiedUnit(cycleUnits.get(0))) {
                // 简化单元已达封顶，重置累计
                accumulated = BigDecimal.ZERO;
                continue;
            }

            // 计算本周期金额
            BigDecimal cycleAmount = cycleUnits.stream()
                    .map(BillingUnit::getChargedAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal totalAccumulated = accumulated.add(cycleAmount);

            if (totalAccumulated.compareTo(maxCharge) > 0) {
                // 超过封顶
                BigDecimal maxAllowed = maxCharge.subtract(accumulated).max(BigDecimal.ZERO);
                if (maxAllowed.compareTo(BigDecimal.ZERO) <= 0) {
                    // 已达封顶，全部免费
                    for (BillingUnit unit : cycleUnits) {
                        if (!unit.isFree()) {
                            unit.setChargedAmount(BigDecimal.ZERO);
                            unit.setFree(true);
                            unit.setFreePromotionId("CYCLE_CAP");
                        }
                    }
                } else {
                    // 按比例削减
                    BigDecimal ratio = maxAllowed.divide(cycleAmount, 6, RoundingMode.HALF_UP);
                    for (BillingUnit unit : cycleUnits) {
                        if (!unit.isFree()) {
                            BigDecimal newAmount = unit.getChargedAmount().multiply(ratio)
                                    .setScale(2, RoundingMode.HALF_UP);
                            unit.setChargedAmount(newAmount);
                            if (newAmount.compareTo(BigDecimal.ZERO) == 0) {
                                unit.setFree(true);
                                unit.setFreePromotionId("CYCLE_CAP");
                            }
                        }
                    }
                }
                accumulated = maxCharge;
            } else {
                accumulated = totalAccumulated;
            }

            // 新周期重置
            accumulated = BigDecimal.ZERO;
        }
    }

    /**
     * 从简化单元中获取起始周期索引
     */
    @SuppressWarnings("unchecked")
    private int getSimplifiedCycleIndex(BillingUnit unit) {
        if (isSimplifiedUnit(unit)) {
            Map<String, Object> data = (Map<String, Object>) unit.getRuleData();
            return (Integer) data.get("cycleIndex");
        }
        return 0;
    }

    /**
     * 验证配置有效性
     */
    private void validateConfig(RelativeTimeConfig config) {
        // 检查封顶金额（必填）
        if (config.getMaxChargeOneCycle() == null) {
            throw new IllegalArgumentException("maxChargeOneCycle is required");
        }
        if (config.getMaxChargeOneCycle().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("maxChargeOneCycle must be positive");
        }

        List<RelativeTimePeriod> periods = config.getPeriods();
        if (periods == null || periods.isEmpty()) {
            throw new IllegalArgumentException("Periods cannot be empty");
        }

        // 检查首时间段
        if (periods.get(0).getBeginMinute() != 0) {
            throw new IllegalArgumentException("First period must start at minute 0");
        }

        // 检查末时间段
        if (periods.get(periods.size() - 1).getEndMinute() != MINUTES_PER_CYCLE) {
            throw new IllegalArgumentException("Last period must end at minute 1440");
        }

        // 检查相邻时间段首尾相连
        for (int i = 0; i < periods.size() - 1; i++) {
            if (periods.get(i).getEndMinute() != periods.get(i + 1).getBeginMinute()) {
                throw new IllegalArgumentException("Periods must be contiguous: period " + i + " ends at " +
                        periods.get(i).getEndMinute() + " but period " + (i + 1) + " starts at " +
                        periods.get(i + 1).getBeginMinute());
            }
        }

        // 检查每个时间段的有效性
        for (int i = 0; i < periods.size(); i++) {
            RelativeTimePeriod period = periods.get(i);
            if (period.getBeginMinute() >= period.getEndMinute()) {
                throw new IllegalArgumentException("Invalid period " + i + ": beginMinute must be less than endMinute");
            }
            if (period.getUnitMinutes() <= 0) {
                throw new IllegalArgumentException("Invalid unitMinutes in period " + i);
            }
            if (period.getUnitPrice() == null || period.getUnitPrice().compareTo(BigDecimal.ZERO) < 0) {
                throw new IllegalArgumentException("Invalid unitPrice in period " + i);
            }
        }
    }

    /**
     * 周期计费单元容器
     */
    private static class CycleUnits {
        final LocalDateTime cycleStart;
        final LocalDateTime cycleEnd;
        final List<BillingUnit> units = new ArrayList<>();
        BigDecimal accumulatedBeforeCap; // 封顶前的累计金额

        CycleUnits(LocalDateTime cycleStart, LocalDateTime cycleEnd) {
            this.cycleStart = cycleStart;
            this.cycleEnd = cycleEnd;
        }
    }

    /**
     * 构建计费单元，按周期组织（支持状态恢复）
     */
    private List<CycleUnits> buildBillingUnitsWithState(LocalDateTime calcBegin, LocalDateTime calcEnd,
                                                          RelativeTimeConfig config, List<FreeTimeRange> freeTimeRanges,
                                                          RuleState state) {
        List<CycleUnits> cycles = new ArrayList<>();
        LocalDateTime current = calcBegin;
        BigDecimal carryOverAccumulated = state.getCycleAccumulated();

        // 使用 state.cycleBoundary 作为当前周期的结束时间
        LocalDateTime currentCycleBoundary = state.getCycleBoundary();

        while (current.isBefore(calcEnd)) {
            // 计算当前周期起止
            LocalDateTime cycleStart = current;

            // 周期结束时间：
            // - 如果有 currentCycleBoundary 且它在 current 之后，使用它
            // - 否则使用标准的 24 小时周期
            LocalDateTime cycleEnd;
            if (currentCycleBoundary != null && currentCycleBoundary.isAfter(current)) {
                cycleEnd = currentCycleBoundary;
            } else {
                cycleEnd = cycleStart.plusMinutes(MINUTES_PER_CYCLE);
            }

            if (cycleEnd.isAfter(calcEnd)) {
                cycleEnd = calcEnd;
            }

            CycleUnits cycle = new CycleUnits(cycleStart, cycleEnd);

            // 方案A：检查是否已经达到封顶
            // 如果结转的累计金额已经达到封顶，直接生成免费合并单元，跳过正常计费
            BigDecimal maxCharge = config.getMaxChargeOneCycle();
            if (maxCharge != null && maxCharge.compareTo(BigDecimal.ZERO) > 0
                    && carryOverAccumulated.compareTo(maxCharge) >= 0) {
                // 已达封顶，生成从 current 到 cycleEnd 的免费合并单元
                BillingUnit freeUnit = BillingUnit.builder()
                        .beginTime(current)
                        .endTime(cycleEnd)
                        .durationMinutes((int) Duration.between(current, cycleEnd).toMinutes())
                        .unitPrice(BigDecimal.ZERO)
                        .originalAmount(BigDecimal.ZERO)
                        .chargedAmount(BigDecimal.ZERO)
                        .free(true)
                        .freePromotionId("CYCLE_CAP")
                        .build();
                cycle.units.add(freeUnit);
                cycle.accumulatedBeforeCap = maxCharge; // 封顶金额
            } else {
                // 在当前周期内按时间段生成计费单元
                for (RelativeTimePeriod period : config.getPeriods()) {
                    generateUnitsInPeriod(cycle, period, freeTimeRanges);
                }

                // 应用周期封顶（考虑已有累计金额）
                applyCycleCapWithCarryOver(cycle, config.getMaxChargeOneCycle(), carryOverAccumulated);
            }

            cycles.add(cycle);

            // 重置累计金额（新周期）
            carryOverAccumulated = BigDecimal.ZERO;
            // 更新周期边界（下一周期）
            currentCycleBoundary = cycleEnd.plusMinutes(MINUTES_PER_CYCLE);
            current = cycleEnd;
        }

        return cycles;
    }

    /**
     * 应用周期封顶（考虑结转的累计金额）
     */
    private void applyCycleCapWithCarryOver(CycleUnits cycle, BigDecimal maxCharge, BigDecimal carryOverAccumulated) {
        if (maxCharge == null || maxCharge.compareTo(BigDecimal.ZERO) <= 0) {
            // 不封顶时，累计金额 = 结转 + 本次新增
            BigDecimal newAmount = cycle.units.stream()
                    .filter(u -> !u.isFree())
                    .map(BillingUnit::getChargedAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            cycle.accumulatedBeforeCap = carryOverAccumulated.add(newAmount);
            return;
        }

        // 计算非免费单元的总金额
        List<BillingUnit> chargeableUnits = cycle.units.stream()
                .filter(u -> !u.isFree())
                .toList();

        // 本周期新增金额
        BigDecimal cycleNewAmount = chargeableUnits.stream()
                .map(BillingUnit::getChargedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 总累计金额 = 结转金额 + 新增金额
        BigDecimal totalAccumulated = carryOverAccumulated.add(cycleNewAmount);

        if (totalAccumulated.compareTo(maxCharge) < 0) {
            // 未超过封顶，存储累计金额
            cycle.accumulatedBeforeCap = totalAccumulated;
            return; // 未超过封顶
        }

        // 计算超出金额
        BigDecimal excess = totalAccumulated.subtract(maxCharge);

        // 从最后一个非免费单元开始削减
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
        // 触发封顶，累计金额 = 封顶金额
        cycle.accumulatedBeforeCap = maxCharge;
    }

    /**
     * 在一个时间段内生成计费单元
     */
    private void generateUnitsInPeriod(CycleUnits cycle, RelativeTimePeriod period, List<FreeTimeRange> freeTimeRanges) {
        // 计算该时间段在当前周期内的实际时间范围
        LocalDateTime periodStart = cycle.cycleStart.plusMinutes(period.getBeginMinute());
        LocalDateTime periodEnd = cycle.cycleStart.plusMinutes(period.getEndMinute());

        // 截取到周期范围
        if (periodStart.isBefore(cycle.cycleStart)) {
            periodStart = cycle.cycleStart;
        }
        if (periodEnd.isAfter(cycle.cycleEnd)) {
            periodEnd = cycle.cycleEnd;
        }

        // 如果时间段无效，跳过
        if (!periodStart.isBefore(periodEnd)) {
            return;
        }

        // 按单元长度生成计费单元
        int unitMinutes = period.getUnitMinutes();
        BigDecimal unitPrice = period.getUnitPrice();
        LocalDateTime unitStart = periodStart;

        while (unitStart.isBefore(periodEnd)) {
            LocalDateTime unitEnd = unitStart.plusMinutes(unitMinutes);

            // 截断到时间段边界
            if (unitEnd.isAfter(periodEnd)) {
                unitEnd = periodEnd;
            }

            // 计算时长
            int duration = (int) Duration.between(unitStart, unitEnd).toMinutes();

            // 金额计算：不足一个单元也收全额
            BigDecimal originalAmount = unitPrice;

            // 检查是否被免费时段完全覆盖
            String freePromotionId = findFreePromotionId(unitStart, unitEnd, freeTimeRanges);
            boolean isFree = freePromotionId != null;

            BillingUnit unit = BillingUnit.builder()
                    .beginTime(unitStart)
                    .endTime(unitEnd)
                    .durationMinutes(duration)
                    .unitPrice(unitPrice)
                    .originalAmount(originalAmount)
                    .chargedAmount(isFree ? BigDecimal.ZERO : originalAmount)
                    .free(isFree)
                    .freePromotionId(freePromotionId)
                    .build();

            cycle.units.add(unit);
            unitStart = unitEnd;
        }
    }

    /**
     * 查找完全覆盖该时段的免费优惠
     */
    private String findFreePromotionId(LocalDateTime begin, LocalDateTime end, List<FreeTimeRange> freeTimeRanges) {
        for (FreeTimeRange range : freeTimeRanges) {
            // 免费时段必须完全覆盖计费单元：range.begin <= unit.begin && range.end >= unit.end
            if (!range.getBeginTime().isAfter(begin) && !range.getEndTime().isBefore(end)) {
                return range.getId();
            }
        }
        return null;
    }

    /**
     * 应用周期封顶（从最后一个单元开始削减）
     */
    private void applyCycleCap(CycleUnits cycle, BigDecimal maxCharge) {
        if (maxCharge == null || maxCharge.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }

        // 计算非免费单元的总金额
        List<BillingUnit> chargeableUnits = cycle.units.stream()
                .filter(u -> !u.isFree())
                .toList();

        BigDecimal cycleTotal = chargeableUnits.stream()
                .map(BillingUnit::getChargedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (cycleTotal.compareTo(maxCharge) <= 0) {
            return; // 未超过封顶
        }

        // 计算超出金额
        BigDecimal excess = cycleTotal.subtract(maxCharge);

        // 从最后一个非免费单元开始削减
        for (int i = chargeableUnits.size() - 1; i >= 0 && excess.compareTo(BigDecimal.ZERO) > 0; i--) {
            BillingUnit unit = chargeableUnits.get(i);
            BigDecimal charged = unit.getChargedAmount();

            if (charged.compareTo(excess) >= 0) {
                // 当前单元足够抵扣超出金额
                unit.setChargedAmount(charged.subtract(excess).setScale(2, RoundingMode.HALF_UP));
                // 如果抵扣后金额为0，标记为免费
                if (unit.getChargedAmount().compareTo(BigDecimal.ZERO) == 0) {
                    unit.setFree(true);
                    unit.setFreePromotionId("CYCLE_CAP");
                }
                excess = BigDecimal.ZERO;
            } else {
                // 当前单元不足以抵扣，减为0，继续处理前一个单元
                unit.setChargedAmount(BigDecimal.ZERO);
                unit.setFree(true);
                unit.setFreePromotionId("CYCLE_CAP");
                excess = excess.subtract(charged);
            }
        }
    }

    /**
     * 计算费用确定开始时间 = 最后一个计费单元的开始时间
     */
    private LocalDateTime calculateEffectiveFrom(List<BillingUnit> billingUnits) {
        if (billingUnits == null || billingUnits.isEmpty()) {
            return null;
        }
        return billingUnits.get(billingUnits.size() - 1).getBeginTime();
    }

    /**
     * 计算费用稳定结束时间
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

        // 检查下一个周期边界
        LocalDateTime currentCycleEnd = calcBegin;
        while (!currentCycleEnd.isAfter(effectiveTo)) {
            LocalDateTime nextCycleEnd = currentCycleEnd.plusMinutes(MINUTES_PER_CYCLE);
            if (nextCycleEnd.isAfter(effectiveTo)) {
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
     * 查找下一个时间段边界
     * @param current 当前时间点
     * @param calcBegin 计费起点
     * @param config 规则配置
     * @return 下一个时间段边界时间，如果没有则返回 null
     */
    private LocalDateTime findNextPeriodBoundary(LocalDateTime current, LocalDateTime calcBegin, RelativeTimeConfig config) {
        if (config.getPeriods() == null || config.getPeriods().isEmpty()) {
            return null;
        }

        // 计算当前位置相对于计费起点的分钟偏移
        long minutesFromCalcBegin = Duration.between(calcBegin, current).toMinutes();

        // 计算当前在周期内的位置（取模）
        long positionInCycle = minutesFromCalcBegin % MINUTES_PER_CYCLE;
        if (positionInCycle < 0) {
            positionInCycle += MINUTES_PER_CYCLE;
        }

        // 当前周期的起点
        long cycleCount = minutesFromCalcBegin / MINUTES_PER_CYCLE;
        LocalDateTime cycleStart = calcBegin.plusMinutes(cycleCount * MINUTES_PER_CYCLE);

        // 遍历所有时间段，找到第一个大于当前位置的边界
        for (RelativeTimePeriod period : config.getPeriods()) {
            long periodEndMinute = period.getEndMinute();
            if (periodEndMinute > positionInCycle) {
                return cycleStart.plusMinutes(periodEndMinute);
            }
        }

        // 如果当前周期内没有，返回下一个周期的起点
        return cycleStart.plusMinutes(MINUTES_PER_CYCLE);
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
        while (cycleStart.plusMinutes(MINUTES_PER_CYCLE).isBefore(current) ||
               cycleStart.plusMinutes(MINUTES_PER_CYCLE).equals(current)) {
            cycleStart = cycleStart.plusMinutes(MINUTES_PER_CYCLE);
        }
        // 下一个周期边界
        return cycleStart.plusMinutes(MINUTES_PER_CYCLE);
    }

    /**
     * CONTINUOUS 模式计算
     * 在免费时段边界切分时间轴，每个片段从片段起点重新按单元划分
     */
    private BillingSegmentResult calculateContinuous(BillingContext context, RelativeTimeConfig config, PromotionAggregate promotionAggregate) {
        validateConfig(config);

        CalculationWindow window = context.getWindow();
        LocalDateTime calcBegin = window.getCalculationBegin();
        LocalDateTime calcEnd = window.getCalculationEnd();

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

        List<FreeTimeRange> freeTimeRanges = promotionAggregate != null && promotionAggregate.getFreeTimeRanges() != null
                ? promotionAggregate.getFreeTimeRanges()
                : List.of();

        // 按免费时段边界切分时间轴
        List<TimeFragment> fragments = splitTimeAxis(calcBegin, calcEnd, freeTimeRanges);

        // 按周期组织片段（使用原始计费起点确定周期边界）
        LocalDateTime cycleOriginBegin = context.getBeginTime(); // 原始计费起点
        List<CycleFragments> cycles = organizeByCycle(calcBegin, calcEnd, fragments, cycleOriginBegin);

        // 检查是否启用简化计算
        List<BillingUnit> allUnits = new ArrayList<>();
        boolean simplificationEnabled = context.getBillingConfigResolver() != null
            && isSimplificationEnabled(config, context.getBillingConfigResolver());
        int threshold = context.getBillingConfigResolver() != null
            ? context.getBillingConfigResolver().getSimplifiedCycleThreshold()
            : 0;

        if (simplificationEnabled && cycles.size() > threshold) {
            Set<Integer> cyclesWithPromotion = findCyclesWithPromotion(calcBegin, calcEnd, promotionAggregate);

            if (cyclesWithPromotion != null) {
                // 使用简化计算
                allUnits = generateSimplifiedUnitsForContinuous(cycles, cyclesWithPromotion,
                    threshold, config, calcBegin, cycleOriginBegin, state);
            }
        }

        // 如果未使用简化，使用原有逻辑
        if (allUnits.isEmpty()) {
            // 对每个周期的片段生成计费单元并应用封顶（考虑结转的累计金额）
            BigDecimal carryOverAccumulated = state.getCycleAccumulated();
            BigDecimal lastCycleAccumulated = BigDecimal.ZERO;
            BigDecimal maxCharge = config.getMaxChargeOneCycle();

            for (CycleFragments cycle : cycles) {
                // 方案A：检查是否已经达到封顶
                // 如果结转的累计金额已经达到封顶，直接生成免费合并单元，跳过正常计费
                if (maxCharge != null && maxCharge.compareTo(BigDecimal.ZERO) > 0
                        && carryOverAccumulated.compareTo(maxCharge) >= 0) {
                    // 已达封顶，生成从实际计算开始时间到周期结束的免费合并单元
                    // 注意：cycle.cycleStart 是周期原始起点（如08:00），
                    // 但实际计算开始时间 calcBegin 可能是中间位置（如11:00，即上次计算的结束点）
                    LocalDateTime freeUnitBegin;
                    if (allUnits.isEmpty()) {
                        // 第一个周期：使用实际计算开始时间
                        freeUnitBegin = calcBegin;
                    } else {
                        // 后续周期：从最后一个单元的结束时间开始
                        freeUnitBegin = allUnits.get(allUnits.size() - 1).getEndTime();
                    }
                    BillingUnit freeUnit = BillingUnit.builder()
                            .beginTime(freeUnitBegin)
                            .endTime(cycle.cycleEnd)
                            .durationMinutes((int) Duration.between(freeUnitBegin, cycle.cycleEnd).toMinutes())
                            .unitPrice(BigDecimal.ZERO)
                            .originalAmount(BigDecimal.ZERO)
                            .chargedAmount(BigDecimal.ZERO)
                            .free(true)
                            .freePromotionId("CYCLE_CAP")
                            .build();
                    allUnits.add(freeUnit);
                    lastCycleAccumulated = maxCharge; // 封顶金额
                } else {
                    List<BillingUnit> cycleUnits = generateUnitsForCycle(cycle, config);
                    lastCycleAccumulated = applyContinuousCapWithCarryOver(cycleUnits, config.getMaxChargeOneCycle(), carryOverAccumulated);
                    allUnits.addAll(cycleUnits);
                }
                // 新周期重置累计金额
                carryOverAccumulated = BigDecimal.ZERO;
            }

            // 更新最终状态（FROM_SCRATCH 结果也需要用于继续计算）
            if (!cycles.isEmpty()) {
                // 更新周期索引和边界
                state.setCycleIndex(state.getCycleIndex() + cycles.size() - 1);
                int bubbleExtension = calculateBubbleExtension(freeTimeRanges, calcBegin, calcEnd);
                // 如果 cycleBoundary 已存在（CONTINUE 模式），在其基础上延长
                if (state.getCycleBoundary() != null) {
                    state.setCycleBoundary(state.getCycleBoundary().plusMinutes(bubbleExtension));
                } else {
                    state.setCycleBoundary(cycles.get(cycles.size() - 1).cycleStart.plusMinutes(MINUTES_PER_CYCLE).plusMinutes(bubbleExtension));
                }
                // 使用返回的累计金额
                state.setCycleAccumulated(lastCycleAccumulated);
            }
        } else {
            // 简化计算模式：更新状态
            if (!allUnits.isEmpty()) {
                BillingUnit lastUnit = allUnits.get(allUnits.size() - 1);
                if (isSimplifiedUnit(lastUnit)) {
                    // 简化单元：使用 simplifiedCycleAmount 作为周期累计金额
                    @SuppressWarnings("unchecked")
                    Map<String, Object> ruleData = (Map<String, Object>) lastUnit.getRuleData();
                    BigDecimal cycleAmount = (BigDecimal) ruleData.get("simplifiedCycleAmount");
                    state.setCycleAccumulated(cycleAmount);
                    int beginCycleIndex = (Integer) ruleData.get("cycleIndex");
                    int cycleCount = (Integer) ruleData.get("simplifiedCycleCount");
                    state.setCycleIndex(beginCycleIndex + cycleCount - 1);
                    int bubbleExtension = calculateBubbleExtension(freeTimeRanges, calcBegin, calcEnd);
                    if (state.getCycleBoundary() != null) {
                        state.setCycleBoundary(state.getCycleBoundary().plusMinutes(bubbleExtension));
                    } else {
                        state.setCycleBoundary(getCycleBoundary(beginCycleIndex + cycleCount, calcBegin).plusMinutes(bubbleExtension));
                    }
                } else {
                    // 非简化单元
                    state.setCycleAccumulated(BigDecimal.ZERO);
                    state.setCycleIndex(state.getCycleIndex() + cycles.size() - 1);
                    int bubbleExtension = calculateBubbleExtension(freeTimeRanges, calcBegin, calcEnd);
                    if (state.getCycleBoundary() != null) {
                        state.setCycleBoundary(state.getCycleBoundary().plusMinutes(bubbleExtension));
                    } else {
                        state.setCycleBoundary(cycles.get(cycles.size() - 1).cycleStart.plusMinutes(MINUTES_PER_CYCLE).plusMinutes(bubbleExtension));
                    }
                }
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
            // 对于 CONTINUOUS 模式，使用最后一个单元的开始时间计算对应的单元长度
            int minutesFromCalcBegin = (int) Duration.between(calcBegin, lastUnit.getBeginTime()).toMinutes();
            RelativeTimePeriod period = findPeriodForMinute(minutesFromCalcBegin, config.getPeriods());
            int unitMinutes = period.getUnitMinutes();
            if (lastUnit.getDurationMinutes() < unitMinutes && lastUnit.getEndTime().equals(calcEnd)) {
                lastUnit.setIsTruncated(true);
            }
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
                .billingUnits(allUnits)
                .promotionUsages(new ArrayList<>())
                .promotionAggregate(promotionAggregate)
                .feeEffectiveStart(feeEffectiveStart)
                .feeEffectiveEnd(feeEffectiveEnd)
                .ruleOutputState(ruleOutputState)
                .build();
    }

    /**
     * 按免费时段边界切分时间轴
     */
    private List<TimeFragment> splitTimeAxis(LocalDateTime begin, LocalDateTime end, List<FreeTimeRange> freeTimeRanges) {
        List<TimeFragment> fragments = new ArrayList<>();

        List<LocalDateTime> cutPoints = new ArrayList<>();
        cutPoints.add(begin);

        for (FreeTimeRange range : freeTimeRanges) {
            if (range.getBeginTime().isAfter(end) || range.getEndTime().isBefore(begin)) {
                continue;
            }
            if (range.getBeginTime().isAfter(begin) && range.getBeginTime().isBefore(end)) {
                cutPoints.add(range.getBeginTime());
            }
            if (range.getEndTime().isAfter(begin) && range.getEndTime().isBefore(end)) {
                cutPoints.add(range.getEndTime());
            }
        }

        cutPoints.add(end);
        cutPoints = cutPoints.stream().distinct().sorted().toList();

        for (int i = 0; i < cutPoints.size() - 1; i++) {
            LocalDateTime fragBegin = cutPoints.get(i);
            LocalDateTime fragEnd = cutPoints.get(i + 1);

            TimeFragment fragment = new TimeFragment(fragBegin, fragEnd);

            for (FreeTimeRange range : freeTimeRanges) {
                if (!range.getBeginTime().isAfter(fragBegin) && !range.getEndTime().isBefore(fragEnd)) {
                    fragment.isFree = true;
                    fragment.freePromotionId = range.getId();
                    break;
                }
            }

            fragments.add(fragment);
        }

        return fragments;
    }

    /**
     * 按周期组织片段
     * @param calcBegin 计算窗口起点（可能是 CONTINUE 模式的继续起点）
     * @param calcEnd 计算窗口终点
     * @param fragments 时间片段列表
     * @param cycleOriginBegin 原始计费起点（用于确定周期边界）
     */
    private List<CycleFragments> organizeByCycle(LocalDateTime calcBegin, LocalDateTime calcEnd, List<TimeFragment> fragments, LocalDateTime cycleOriginBegin) {
        List<CycleFragments> cycles = new ArrayList<>();

        // 使用原始计费起点计算周期边界
        LocalDateTime cycleStart = cycleOriginBegin;
        LocalDateTime cycleEnd = cycleOriginBegin.plusMinutes(MINUTES_PER_CYCLE);

        // 找到包含 calcBegin 的周期
        while (cycleEnd.isBefore(calcBegin) || cycleEnd.equals(calcBegin)) {
            cycleStart = cycleEnd;
            cycleEnd = cycleStart.plusMinutes(MINUTES_PER_CYCLE);
        }

        CycleFragments currentCycle = new CycleFragments(cycleStart, cycleEnd.isAfter(calcEnd) ? calcEnd : cycleEnd);

        for (TimeFragment fragment : fragments) {
            while (fragment.endTime.isAfter(currentCycle.cycleEnd)) {
                TimeFragment beforeBoundary = new TimeFragment(fragment.beginTime, currentCycle.cycleEnd);
                beforeBoundary.isFree = fragment.isFree;
                beforeBoundary.freePromotionId = fragment.freePromotionId;

                currentCycle.fragments.add(beforeBoundary);
                cycles.add(currentCycle);

                cycleStart = currentCycle.cycleEnd;
                cycleEnd = cycleStart.plusMinutes(MINUTES_PER_CYCLE);
                currentCycle = new CycleFragments(cycleStart, cycleEnd.isAfter(calcEnd) ? calcEnd : cycleEnd);

                fragment.beginTime = currentCycle.cycleStart;
            }

            currentCycle.fragments.add(fragment);
        }

        if (!currentCycle.fragments.isEmpty()) {
            cycles.add(currentCycle);
        }

        return cycles;
    }

    /**
     * 为一个周期生成计费单元
     */
    private List<BillingUnit> generateUnitsForCycle(CycleFragments cycle, RelativeTimeConfig config) {
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
                // 根据片段开始时间查找对应的 period
                units.addAll(generateUnitsForFragment(fragment, cycle.cycleStart, config));
            }
        }

        return units;
    }

    /**
     * 为一个片段生成计费单元
     */
    private List<BillingUnit> generateUnitsForFragment(TimeFragment fragment, LocalDateTime cycleStart, RelativeTimeConfig config) {
        List<BillingUnit> units = new ArrayList<>();

        LocalDateTime current = fragment.beginTime;

        while (current.isBefore(fragment.endTime)) {
            // 找到当前时间点对应的 period
            int minutesFromCycleStart = (int) Duration.between(cycleStart, current).toMinutes();
            RelativeTimePeriod period = findPeriodForMinute(minutesFromCycleStart, config.getPeriods());

            int unitMinutes = period.getUnitMinutes();
            BigDecimal unitPrice = period.getUnitPrice();

            LocalDateTime unitEnd = current.plusMinutes(unitMinutes);

            // 截断到片段边界
            if (unitEnd.isAfter(fragment.endTime)) {
                unitEnd = fragment.endTime;
            }

            // 截断到 period 边界
            int periodEndMinute = period.getEndMinute();
            LocalDateTime periodEnd = cycleStart.plusMinutes(periodEndMinute);
            if (unitEnd.isAfter(periodEnd)) {
                unitEnd = periodEnd;
            }

            int duration = (int) Duration.between(current, unitEnd).toMinutes();

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
            current = unitEnd;
        }

        return units;
    }

    /**
     * 找到对应的 period
     */
    private RelativeTimePeriod findPeriodForMinute(int minute, List<RelativeTimePeriod> periods) {
        for (RelativeTimePeriod period : periods) {
            if (minute >= period.getBeginMinute() && minute < period.getEndMinute()) {
                return period;
            }
        }
        // 如果超出最后一个 period，返回最后一个
        return periods.get(periods.size() - 1);
    }

    /**
     * CONTINUOUS 模式封顶处理
     * 封顶后截止，剩余时间合并为免费单元
     */
    private void applyContinuousCap(List<BillingUnit> units, BigDecimal maxCharge) {
        applyContinuousCapWithCarryOver(units, maxCharge, BigDecimal.ZERO);
    }

    /**
     * CONTINUOUS 模式封顶处理（考虑结转的累计金额）
     * @return 累计金额（封顶后为封顶金额，未封顶为实际累计）
     */
    private BigDecimal applyContinuousCapWithCarryOver(List<BillingUnit> units, BigDecimal maxCharge, BigDecimal carryOverAccumulated) {
        if (maxCharge == null || maxCharge.compareTo(BigDecimal.ZERO) <= 0) {
            // 不封顶时，返回累计金额
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

                // 移除所有收费单元
                units.removeIf(u -> !u.isFree());

                // 添加一个合并的免费单元
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
            // 未封顶，返回累计金额
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

        // 封顶时返回封顶金额
        return maxCharge;
    }

    /**
     * 为 CONTINUOUS 模式生成简化单元
     */
    private List<BillingUnit> generateSimplifiedUnitsForContinuous(
            List<CycleFragments> cycles,
            Set<Integer> cyclesWithPromotion,
            int threshold,
            RelativeTimeConfig config,
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
                        List<FreeTimeRange> emptyRanges = List.of();
                        List<BillingUnit> cycleUnits = generateUnitsForSingleCycle(i, calcBegin, cycle.cycleEnd, config, emptyRanges);
                        allUnits.addAll(cycleUnits);
                    }
                }
                consecutiveSimplified = 0;

                // 生成当前有优惠周期的详细单元
                List<BillingUnit> cycleUnits = generateUnitsForCycle(cycle, config);
                applyContinuousCapWithCarryOver(cycleUnits, config.getMaxChargeOneCycle(), carryOverAccumulated);
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
                List<BillingUnit> cycleUnits = generateUnitsForSingleCycle(i, calcBegin, cycles.get(cycles.size() - 1).cycleEnd, config, emptyRanges);
                allUnits.addAll(cycleUnits);
            }
        }

        return allUnits;
    }

    /**
     * 时间片段（切分后的时间范围）- CONTINUOUS模式专用
     */
    private static class TimeFragment {
        LocalDateTime beginTime;
        LocalDateTime endTime;
        boolean isFree;
        String freePromotionId;

        TimeFragment(LocalDateTime beginTime, LocalDateTime endTime) {
            this.beginTime = beginTime;
            this.endTime = endTime;
            this.isFree = false;
            this.freePromotionId = null;
        }
    }

    /**
     * 周期片段容器 - CONTINUOUS模式专用
     */
    private static class CycleFragments {
        final LocalDateTime cycleStart;
        final LocalDateTime cycleEnd;
        final List<TimeFragment> fragments = new ArrayList<>();

        CycleFragments(LocalDateTime cycleStart, LocalDateTime cycleEnd) {
            this.cycleStart = cycleStart;
            this.cycleEnd = cycleEnd;
        }
    }
}