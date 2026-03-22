package cn.shang.charging.charge.rules.compositetime;

import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.BillingContext;
import cn.shang.charging.billing.pojo.BillingSegmentResult;
import cn.shang.charging.billing.pojo.BillingUnit;
import cn.shang.charging.billing.pojo.CalculationWindow;
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
public class CompositeTimeRule implements BillingRule<CompositeTimeConfig> {

    private static final int MINUTES_PER_DAY = 1440;

    /**
     * 规则状态结构（用于 CONTINUE 模式）
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RuleState {
        /** 当前周期索引 */
        private int cycleIndex;
        /** 当前周期累计金额 */
        private BigDecimal cycleAccumulated;
        /** 周期边界时间 */
        private LocalDateTime cycleBoundary;
    }

    private static final String RULE_TYPE = "compositeTime";

    @Override
    public BillingSegmentResult calculate(BillingContext context,
                                          CompositeTimeConfig ruleConfig,
                                          PromotionAggregate promotionAggregate) {
        validateConfig(ruleConfig);

        if (context.getBillingMode() == BConstants.BillingMode.UNIT_BASED) {
            return calculateUnitBased(context, ruleConfig, promotionAggregate);
        } else {
            return calculateContinuous(context, ruleConfig, promotionAggregate);
        }
    }

    /**
     * UNIT_BASED 模式计算
     */
    private BillingSegmentResult calculateUnitBased(BillingContext context,
                                                     CompositeTimeConfig config,
                                                     PromotionAggregate promotionAggregate) {
        // 获取计算窗口
        CalculationWindow window = context.getWindow();
        LocalDateTime calcBegin = window.getCalculationBegin();
        LocalDateTime calcEnd = window.getCalculationEnd();

        // 获取计费起点（从分段信息获取）
        LocalDateTime billingOrigin = context.getSegment().getBeginTime();

        // 恢复状态
        RuleState state = restoreState(context.getRuleState());
        if (state == null) {
            state = RuleState.builder()
                    .cycleIndex(0)
                    .cycleAccumulated(BigDecimal.ZERO)
                    .cycleBoundary(billingOrigin.plusMinutes(MINUTES_PER_DAY))
                    .build();
        } else {
            // CONTINUE: 更新周期状态
            while (state.getCycleBoundary() != null && !calcBegin.isBefore(state.getCycleBoundary())) {
                state.setCycleIndex(state.getCycleIndex() + 1);
                state.setCycleAccumulated(BigDecimal.ZERO);
                state.setCycleBoundary(state.getCycleBoundary().plusMinutes(MINUTES_PER_DAY));
            }
        }

        // 获取免费时段
        List<FreeTimeRange> freeTimeRanges = promotionAggregate != null && promotionAggregate.getFreeTimeRanges() != null
                ? promotionAggregate.getFreeTimeRanges()
                : List.of();

        // 构建计费单元
        List<CycleUnits> cycles = buildBillingUnits(calcBegin, calcEnd, billingOrigin, config, freeTimeRanges, state);

        // 汇总结果
        List<BillingUnit> allUnits = new ArrayList<>();
        for (CycleUnits cycle : cycles) {
            allUnits.addAll(cycle.units);
        }

        BigDecimal totalAmount = allUnits.stream()
                .map(BillingUnit::getChargedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 更新最终状态
        BigDecimal lastCycleAccumulated = BigDecimal.ZERO;
        if (!cycles.isEmpty()) {
            CycleUnits lastCycle = cycles.get(cycles.size() - 1);
            state.setCycleIndex(cycles.size() - 1);
            state.setCycleBoundary(lastCycle.cycleStart.plusMinutes(MINUTES_PER_DAY));
            lastCycleAccumulated = lastCycle.accumulatedBeforeCap != null
                    ? lastCycle.accumulatedBeforeCap
                    : lastCycle.units.stream().map(BillingUnit::getChargedAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            state.setCycleAccumulated(lastCycleAccumulated);
        }

        // 计算费用稳定时间窗口
        LocalDateTime feeEffectiveStart = calculateEffectiveFrom(allUnits);
        LocalDateTime feeEffectiveEnd = calculateEffectiveTo(allUnits, calcEnd);

        // 标记最后一个单元是否被截断
        if (!allUnits.isEmpty()) {
            BillingUnit lastUnit = allUnits.get(allUnits.size() - 1);
            // 获取最后一个单元对应的单元长度
            int minutesFromBillingOrigin = (int) Duration.between(billingOrigin, lastUnit.getBeginTime()).toMinutes();
            int positionInCycle = minutesFromBillingOrigin % MINUTES_PER_DAY;
            if (positionInCycle < 0) {
                positionInCycle += MINUTES_PER_DAY;
            }
            CompositePeriod period = findPeriodForMinute(positionInCycle, config.getPeriods());
            int unitMinutes = period.getUnitMinutes();
            if (lastUnit.getDurationMinutes() < unitMinutes && lastUnit.getEndTime().equals(calcEnd)) {
                lastUnit.setIsTruncated(true);
            }
        }

        // 构建输出状态
        Map<String, Object> ruleOutputState = new HashMap<>();
        ruleOutputState.put(RULE_TYPE, toMap(state));

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
    private BillingSegmentResult calculateContinuous(BillingContext context,
                                                      CompositeTimeConfig config,
                                                      PromotionAggregate promotionAggregate) {
        // 获取计算窗口
        CalculationWindow window = context.getWindow();
        LocalDateTime calcBegin = window.getCalculationBegin();
        LocalDateTime calcEnd = window.getCalculationEnd();

        // 获取计费起点（从分段信息获取）
        LocalDateTime billingOrigin = context.getSegment().getBeginTime();

        // 恢复状态
        RuleState state = restoreState(context.getRuleState());
        if (state == null) {
            state = RuleState.builder()
                    .cycleIndex(0)
                    .cycleAccumulated(BigDecimal.ZERO)
                    .cycleBoundary(billingOrigin.plusMinutes(MINUTES_PER_DAY))
                    .build();
        } else {
            // CONTINUE: 更新周期状态
            while (state.getCycleBoundary() != null && !calcBegin.isBefore(state.getCycleBoundary())) {
                state.setCycleIndex(state.getCycleIndex() + 1);
                state.setCycleAccumulated(BigDecimal.ZERO);
                state.setCycleBoundary(state.getCycleBoundary().plusMinutes(MINUTES_PER_DAY));
            }
        }

        // 获取免费时段
        List<FreeTimeRange> freeTimeRanges = promotionAggregate != null && promotionAggregate.getFreeTimeRanges() != null
                ? promotionAggregate.getFreeTimeRanges()
                : List.of();

        // 按免费时段边界切分时间轴
        List<TimeFragment> fragments = splitTimeAxis(calcBegin, calcEnd, freeTimeRanges);

        // 按周期组织片段
        List<CycleFragments> cycles = organizeByCycle(calcBegin, calcEnd, fragments, billingOrigin);

        // 对每个周期的片段生成计费单元
        BigDecimal carryOverAccumulated = state.getCycleAccumulated();
        List<BillingUnit> allUnits = new ArrayList<>();
        BigDecimal lastCycleAccumulated = BigDecimal.ZERO;
        BigDecimal maxCharge = config.getMaxChargeOneCycle();

        for (CycleFragments cycle : cycles) {
            // 检查是否已经达到封顶
            if (maxCharge != null && maxCharge.compareTo(BigDecimal.ZERO) > 0
                    && carryOverAccumulated.compareTo(maxCharge) >= 0) {
                // 已达封顶，生成免费合并单元
                LocalDateTime freeUnitBegin;
                if (allUnits.isEmpty()) {
                    freeUnitBegin = calcBegin;
                } else {
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
                lastCycleAccumulated = maxCharge;
            } else {
                List<BillingUnit> cycleUnits = generateUnitsForCycle(cycle, config, billingOrigin);
                lastCycleAccumulated = applyContinuousCapWithCarryOver(cycleUnits, config.getMaxChargeOneCycle(), carryOverAccumulated);
                allUnits.addAll(cycleUnits);
            }
            // 新周期重置累计金额
            carryOverAccumulated = BigDecimal.ZERO;
        }

        // 更新最终状态
        if (!cycles.isEmpty()) {
            state.setCycleIndex(state.getCycleIndex() + cycles.size() - 1);
            state.setCycleBoundary(cycles.get(cycles.size() - 1).cycleStart.plusMinutes(MINUTES_PER_DAY));
            state.setCycleAccumulated(lastCycleAccumulated);
        }

        BigDecimal totalAmount = allUnits.stream()
                .map(BillingUnit::getChargedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        LocalDateTime feeEffectiveStart = calculateEffectiveFrom(allUnits);
        LocalDateTime feeEffectiveEnd = calculateEffectiveTo(allUnits, calcEnd);

        // 标记最后一个单元是否被截断
        if (!allUnits.isEmpty()) {
            BillingUnit lastUnit = allUnits.get(allUnits.size() - 1);
            // 获取最后一个单元对应的单元长度
            int minutesFromBillingOrigin = (int) Duration.between(billingOrigin, lastUnit.getBeginTime()).toMinutes();
            int positionInCycle = minutesFromBillingOrigin % MINUTES_PER_DAY;
            if (positionInCycle < 0) {
                positionInCycle += MINUTES_PER_DAY;
            }
            CompositePeriod period = findPeriodForMinute(positionInCycle, config.getPeriods());
            int unitMinutes = period.getUnitMinutes();
            if (lastUnit.getDurationMinutes() < unitMinutes && lastUnit.getEndTime().equals(calcEnd)) {
                lastUnit.setIsTruncated(true);
            }
        }

        // 构建输出状态
        Map<String, Object> ruleOutputState = new HashMap<>();
        ruleOutputState.put(RULE_TYPE, toMap(state));

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
     */
    private List<CycleFragments> organizeByCycle(LocalDateTime calcBegin, LocalDateTime calcEnd,
                                                  List<TimeFragment> fragments, LocalDateTime billingOrigin) {
        List<CycleFragments> cycles = new ArrayList<>();

        LocalDateTime cycleStart = billingOrigin;
        LocalDateTime cycleEnd = billingOrigin.plusMinutes(MINUTES_PER_DAY);

        // 找到包含 calcBegin 的周期
        while (cycleEnd.isBefore(calcBegin) || cycleEnd.equals(calcBegin)) {
            cycleStart = cycleEnd;
            cycleEnd = cycleStart.plusMinutes(MINUTES_PER_DAY);
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
                cycleEnd = cycleStart.plusMinutes(MINUTES_PER_DAY);
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
            CompositePeriod period = findPeriodForMinute((int) positionInCycle, config.getPeriods());
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
            BigDecimal unitPrice = calculateUnitPrice(current, unitEnd, period);

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

    /**
     * 从 Map 恢复 RuleState
     */
    @SuppressWarnings("unchecked")
    private RuleState restoreState(Map<String, Object> stateMap) {
        if (stateMap == null) return null;
        Object state = stateMap.get(RULE_TYPE);
        if (state == null) return null;

        if (state instanceof RuleState) {
            return (RuleState) state;
        }

        if (state instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) state;
            return RuleState.builder()
                    .cycleIndex((Integer) map.getOrDefault("cycleIndex", 0))
                    .cycleAccumulated(map.get("cycleAccumulated") instanceof BigDecimal
                            ? (BigDecimal) map.get("cycleAccumulated")
                            : new BigDecimal(map.getOrDefault("cycleAccumulated", "0").toString()))
                    .cycleBoundary((LocalDateTime) map.get("cycleBoundary"))
                    .build();
        }
        return null;
    }

    /**
     * 序列化 RuleState 为 Map
     */
    private Map<String, Object> toMap(RuleState state) {
        Map<String, Object> map = new HashMap<>();
        map.put("cycleIndex", state.getCycleIndex());
        map.put("cycleAccumulated", state.getCycleAccumulated());
        map.put("cycleBoundary", state.getCycleBoundary());
        return map;
    }

    @Override
    public Class<CompositeTimeConfig> configClass() {
        return CompositeTimeConfig.class;
    }

    @Override
    public Set<BConstants.BillingMode> supportedModes() {
        return Set.of(BConstants.BillingMode.UNIT_BASED, BConstants.BillingMode.CONTINUOUS);
    }

    @Override
    public Map<String, Object> buildCarryOverState(BillingSegmentResult result) {
        if (result.getRuleOutputState() == null) {
            return Collections.emptyMap();
        }
        return result.getRuleOutputState();
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

    /**
     * 构建计费单元
     */
    private List<CycleUnits> buildBillingUnits(LocalDateTime calcBegin, LocalDateTime calcEnd,
                                                LocalDateTime billingOrigin, CompositeTimeConfig config,
                                                List<FreeTimeRange> freeTimeRanges, RuleState state) {
        List<CycleUnits> cycles = new ArrayList<>();
        LocalDateTime current = calcBegin;
        BigDecimal carryOverAccumulated = state.getCycleAccumulated();
        LocalDateTime currentCycleBoundary = state.getCycleBoundary();

        while (current.isBefore(calcEnd)) {
            LocalDateTime cycleStart = current;

            // 计算当前周期结束时间
            LocalDateTime cycleEnd;
            if (currentCycleBoundary != null && currentCycleBoundary.isAfter(current)) {
                cycleEnd = currentCycleBoundary;
            } else {
                cycleEnd = cycleStart.plusMinutes(MINUTES_PER_DAY);
            }

            if (cycleEnd.isAfter(calcEnd)) {
                cycleEnd = calcEnd;
            }

            CycleUnits cycle = new CycleUnits(cycleStart, cycleEnd);

            // 检查是否已经达到封顶
            BigDecimal maxCharge = config.getMaxChargeOneCycle();
            if (maxCharge != null && maxCharge.compareTo(BigDecimal.ZERO) > 0
                    && carryOverAccumulated.compareTo(maxCharge) >= 0) {
                // 已达封顶，生成免费单元
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
                cycle.accumulatedBeforeCap = maxCharge;
            } else {
                // 在当前周期内按相对时间段生成计费单元
                for (CompositePeriod period : config.getPeriods()) {
                    generateUnitsInPeriod(cycle, period, freeTimeRanges, config.getInsufficientUnitMode());
                }

                // 应用周期封顶
                applyCycleCapWithCarryOver(cycle, config.getMaxChargeOneCycle(), carryOverAccumulated);
            }

            cycles.add(cycle);

            // 重置累计金额（新周期）
            carryOverAccumulated = BigDecimal.ZERO;
            currentCycleBoundary = cycleEnd.plusMinutes(MINUTES_PER_DAY);
            current = cycleEnd;
        }

        return cycles;
    }

    /**
     * 在一个相对时间段内生成计费单元
     * 类似 RelativeTimeRule.generateUnitsInPeriod，但使用自然时段价格
     */
    private void generateUnitsInPeriod(CycleUnits cycle, CompositePeriod period,
                                        List<FreeTimeRange> freeTimeRanges,
                                        InsufficientUnitMode insufficientUnitMode) {
        // 计算该时间段在当前周期内的实际时间范围
        // 类似 RelativeTimeRule：periodStart = cycleStart + beginMinute
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

        // 记录时间段开始时的单元数量，用于后续封顶处理
        int startIndex = cycle.units.size();

        // 按单元长度生成计费单元
        int unitMinutes = period.getUnitMinutes();
        LocalDateTime unitStart = periodStart;

        while (unitStart.isBefore(periodEnd)) {
            LocalDateTime unitEnd = unitStart.plusMinutes(unitMinutes);

            // 截断到时间段边界
            if (unitEnd.isAfter(periodEnd)) {
                unitEnd = periodEnd;
            }

            // 计算时长
            int duration = (int) Duration.between(unitStart, unitEnd).toMinutes();

            // 计算单元价格（基于自然时段）
            BigDecimal unitPrice = calculateUnitPrice(unitStart, unitEnd, period);

            // 金额计算：不足一个单元也收全额（除非配置了按比例）
            BigDecimal originalAmount;
            if (insufficientUnitMode == InsufficientUnitMode.PROPORTIONAL && duration < unitMinutes) {
                originalAmount = unitPrice.multiply(BigDecimal.valueOf(duration))
                        .divide(BigDecimal.valueOf(unitMinutes), 2, RoundingMode.HALF_UP);
            } else {
                originalAmount = unitPrice;
            }

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

        // 应用时间段独立封顶
        if (period.getMaxCharge() != null && period.getMaxCharge().compareTo(BigDecimal.ZERO) > 0) {
            applyPeriodCap(cycle.units, startIndex, period.getMaxCharge());
        }
    }

    /**
     * 应用时间段独立封顶
     * 从时间段内最后一个收费单元开始削减
     */
    private void applyPeriodCap(List<BillingUnit> allUnits, int startIndex, BigDecimal maxCharge) {
        // 获取该时间段内的可收费单元（非免费单元）
        List<BillingUnit> periodUnits = allUnits.subList(startIndex, allUnits.size());
        List<BillingUnit> chargeableUnits = new ArrayList<>(periodUnits.stream()
                .filter(u -> !u.isFree())
                .toList());

        if (chargeableUnits.isEmpty()) {
            return;
        }

        // 计算时间段内的总收费
        BigDecimal totalCharge = chargeableUnits.stream()
                .map(BillingUnit::getChargedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 如果未超过封顶，无需处理
        if (totalCharge.compareTo(maxCharge) <= 0) {
            return;
        }

        // 计算超出金额
        BigDecimal excess = totalCharge.subtract(maxCharge);

        // 从最后一个单元开始削减
        for (int i = chargeableUnits.size() - 1; i >= 0 && excess.compareTo(BigDecimal.ZERO) > 0; i--) {
            BillingUnit unit = chargeableUnits.get(i);
            BigDecimal charged = unit.getChargedAmount();

            if (charged.compareTo(excess) >= 0) {
                // 该单元可以完全抵消超出金额
                unit.setChargedAmount(charged.subtract(excess).setScale(2, RoundingMode.HALF_UP));
                if (unit.getChargedAmount().compareTo(BigDecimal.ZERO) == 0) {
                    unit.setFree(true);
                    unit.setFreePromotionId("PERIOD_CAP");
                }
                excess = BigDecimal.ZERO;
            } else {
                // 该单元金额不足，全部抵扣
                unit.setChargedAmount(BigDecimal.ZERO);
                unit.setFree(true);
                unit.setFreePromotionId("PERIOD_CAP");
                excess = excess.subtract(charged);
            }
        }
    }

    /**
     * 计算单元价格（基于自然时段和跨时段处理模式）
     */
    private BigDecimal calculateUnitPrice(LocalDateTime unitBegin, LocalDateTime unitEnd, CompositePeriod period) {
        int unitMinutes = (int) Duration.between(unitBegin, unitEnd).toMinutes();

        // 找到单元开始时间对应的自然时段
        int beginMinuteOfDay = unitBegin.getHour() * 60 + unitBegin.getMinute();
        NaturalPeriod beginPeriod = findNaturalPeriod(beginMinuteOfDay, period.getNaturalPeriods());

        // 找到单元结束时间对应的自然时段
        int endMinuteOfDay = unitEnd.getHour() * 60 + unitEnd.getMinute();
        if (endMinuteOfDay == 0) {
            endMinuteOfDay = MINUTES_PER_DAY; // 00:00 视为 1440
        }
        NaturalPeriod endPeriod = findNaturalPeriod(endMinuteOfDay == MINUTES_PER_DAY ? 0 : endMinuteOfDay, period.getNaturalPeriods());

        // 如果开始和结束在同一自然时段，直接返回该时段价格
        if (beginPeriod == endPeriod || beginPeriod.getUnitPrice().equals(endPeriod.getUnitPrice())) {
            return beginPeriod.getUnitPrice();
        }

        // 跨时段处理
        return handleCrossPeriod(unitBegin, unitEnd, period, beginPeriod, endPeriod);
    }

    /**
     * 处理跨自然时段的单元
     */
    private BigDecimal handleCrossPeriod(LocalDateTime unitBegin, LocalDateTime unitEnd,
                                          CompositePeriod period, NaturalPeriod beginPeriod, NaturalPeriod endPeriod) {
        CrossPeriodMode mode = period.getCrossPeriodMode();

        switch (mode) {
            case BLOCK_WEIGHT:
                return handleBlockWeight(unitBegin, unitEnd, period.getNaturalPeriods());
            case HIGHER_PRICE:
                return beginPeriod.getUnitPrice().max(endPeriod.getUnitPrice());
            case LOWER_PRICE:
                return beginPeriod.getUnitPrice().min(endPeriod.getUnitPrice());
            case BEGIN_TIME_PRICE:
                return beginPeriod.getUnitPrice();
            case END_TIME_PRICE:
                return endPeriod.getUnitPrice();
            case PROPORTIONAL:
                return calculateProportionalPrice(unitBegin, unitEnd, period.getNaturalPeriods());
            case BEGIN_TIME_TRUNCATE:
                return beginPeriod.getUnitPrice();
            default:
                return beginPeriod.getUnitPrice();
        }
    }

    /**
     * 按时间比例判断用哪个价格（BLOCK_WEIGHT 模式）
     * 类似 DayNightRule 的 blockWeight 逻辑，默认 0.5
     */
    private BigDecimal handleBlockWeight(LocalDateTime unitBegin, LocalDateTime unitEnd, List<NaturalPeriod> naturalPeriods) {
        // 简化实现：返回第一个自然时段的价格
        // 完整实现需要计算各时段的分钟比例
        int beginMinuteOfDay = unitBegin.getHour() * 60 + unitBegin.getMinute();
        NaturalPeriod beginPeriod = findNaturalPeriod(beginMinuteOfDay, naturalPeriods);
        return beginPeriod.getUnitPrice();
    }

    /**
     * 按比例计算价格
     */
    private BigDecimal calculateProportionalPrice(LocalDateTime unitBegin, LocalDateTime unitEnd, List<NaturalPeriod> naturalPeriods) {
        int totalMinutes = (int) Duration.between(unitBegin, unitEnd).toMinutes();
        BigDecimal totalAmount = BigDecimal.ZERO;

        LocalDateTime current = unitBegin;
        while (current.isBefore(unitEnd)) {
            int currentMinuteOfDay = current.getHour() * 60 + current.getMinute();
            NaturalPeriod np = findNaturalPeriod(currentMinuteOfDay, naturalPeriods);

            // 找到下一个自然时段的边界
            LocalDateTime nextBoundary = findNextNaturalPeriodBoundary(current, naturalPeriods);
            if (nextBoundary == null || nextBoundary.isAfter(unitEnd)) {
                nextBoundary = unitEnd;
            }

            int minutesInPeriod = (int) Duration.between(current, nextBoundary).toMinutes();
            BigDecimal periodAmount = np.getUnitPrice().multiply(BigDecimal.valueOf(minutesInPeriod))
                    .divide(BigDecimal.valueOf(totalMinutes), 2, RoundingMode.HALF_UP);
            totalAmount = totalAmount.add(periodAmount);

            current = nextBoundary;
        }

        return totalAmount;
    }

    /**
     * 查找下一个自然时段边界
     */
    private LocalDateTime findNextNaturalPeriodBoundary(LocalDateTime current, List<NaturalPeriod> naturalPeriods) {
        int currentMinuteOfDay = current.getHour() * 60 + current.getMinute();

        for (NaturalPeriod np : naturalPeriods) {
            // 如果当前在某个时段内，下一个边界是该时段的结束
            if (isInNaturalPeriod(currentMinuteOfDay, np)) {
                return current.plusMinutes(np.getEndMinute() - currentMinuteOfDay);
            }
        }

        return null;
    }

    /**
     * 判断分钟是否在自然时段内
     */
    private boolean isInNaturalPeriod(int minute, NaturalPeriod period) {
        int begin = period.getBeginMinute();
        int end = period.getEndMinute();

        if (begin < end) {
            // 不跨天
            return minute >= begin && minute < end;
        } else {
            // 跨天
            return minute >= begin || minute < end;
        }
    }

    /**
     * 查找包含指定分钟的自然时段
     */
    private NaturalPeriod findNaturalPeriod(int minute, List<NaturalPeriod> naturalPeriods) {
        for (NaturalPeriod np : naturalPeriods) {
            if (isInNaturalPeriod(minute, np)) {
                return np;
            }
        }
        // 如果没找到，返回第一个时段（边界情况）
        return naturalPeriods.get(0);
    }

    /**
     * 查找完全覆盖该时段的免费优惠
     */
    private String findFreePromotionId(LocalDateTime begin, LocalDateTime end, List<FreeTimeRange> freeTimeRanges) {
        for (FreeTimeRange range : freeTimeRanges) {
            if (!range.getBeginTime().isAfter(begin) && !range.getEndTime().isBefore(end)) {
                return range.getId();
            }
        }
        return null;
    }

    /**
     * 应用周期封顶（考虑结转的累计金额）
     */
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
     * 计算费用确定开始时间
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
    private LocalDateTime calculateEffectiveTo(List<BillingUnit> billingUnits, LocalDateTime calcEnd) {
        if (billingUnits == null || billingUnits.isEmpty()) {
            return null;
        }
        return billingUnits.get(billingUnits.size() - 1).getEndTime();
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
     * 根据分钟找到对应的 CompositePeriod
     */
    private CompositePeriod findPeriodForMinute(int minute, List<CompositePeriod> periods) {
        for (CompositePeriod period : periods) {
            if (minute >= period.getBeginMinute() && minute < period.getEndMinute()) {
                return period;
            }
        }
        // 如果超出最后一个 period，返回最后一个
        return periods.get(periods.size() - 1);
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