package cn.shang.charging.charge.rules.relativetime;

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
 * 按相对时间段计费规则
 * <p>
 * 核心逻辑：
 * 1. 从计费起点开始，按 24 小时划分周期
 * 2. 每个周期内按配置的时间段划分，每个时间段可有不同的单元长度和单价
 * 3. 计费单元在时间段边界会被截断，不足一个单元长度的部分收全额
 * 4. 每个周期独立封顶，超出时从最后一个单元开始削减
 * 5. 免费时段完全覆盖计费单元才免费
 */
public class RelativeTimeRule implements BillingRule<RelativeTimeConfig> {

    private static final int MINUTES_PER_CYCLE = 1440; // 24小时 = 1440分钟

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

    // 规则类型标识（用于 ruleState Map 的 key）
    private static final String RULE_TYPE = "relativeTime";

    @Override
    public Class<RelativeTimeConfig> configClass() {
        return RelativeTimeConfig.class;
    }

    @Override
    public Set<BConstants.BillingMode> supportedModes() {
        return EnumSet.of(BConstants.BillingMode.CONTINUOUS, BConstants.BillingMode.UNIT_BASED);
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

        // 从序列化的 Map 恢复
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
    public Map<String, Object> buildCarryOverState(BillingSegmentResult result) {
        if (result.getRuleOutputState() == null) {
            return Collections.emptyMap();
        }
        return result.getRuleOutputState();
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

        // 恢复状态
        RuleState state = restoreState(context.getRuleState());
        if (state == null) {
            // FROM_SCRATCH: 初始化状态
            state = RuleState.builder()
                    .cycleIndex(0)
                    .cycleAccumulated(BigDecimal.ZERO)
                    .cycleBoundary(calcBegin.plusMinutes(MINUTES_PER_CYCLE))
                    .build();
        } else {
            // CONTINUE: 更新周期状态
            // 如果 calcBegin >= cycleBoundary，说明已经进入新周期
            while (state.getCycleBoundary() != null && !calcBegin.isBefore(state.getCycleBoundary())) {
                state.setCycleIndex(state.getCycleIndex() + 1);
                state.setCycleAccumulated(BigDecimal.ZERO);
                state.setCycleBoundary(state.getCycleBoundary().plusMinutes(MINUTES_PER_CYCLE));
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

        // 更新最终状态（最后一个周期的状态）- 仅 CONTINUE 模式需要
        if (!cycles.isEmpty() && context.getContinueMode() == BConstants.ContinueMode.CONTINUE) {
            CycleUnits lastCycle = cycles.get(cycles.size() - 1);
            state.setCycleIndex(state.getCycleIndex() + cycles.size() - 1);
            state.setCycleBoundary(lastCycle.cycleStart.plusMinutes(MINUTES_PER_CYCLE));
            // 计算最后一个周期的累计金额
            BigDecimal lastCycleAmount = lastCycle.units.stream()
                    .map(BillingUnit::getChargedAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            state.setCycleAccumulated(lastCycleAmount);
        }

        // 计算费用稳定时间窗口
        LocalDateTime feeEffectiveStart = calculateEffectiveFrom(allUnits);
        LocalDateTime feeEffectiveEnd = calculateEffectiveTo(allUnits, freeTimeRanges, calcBegin, calcEnd);

        // 延伸最后一个计费单元
        LocalDateTime extendedCalculationEndTime = extendLastUnit(allUnits, calcBegin, calcEnd, config);

        // 构建输出状态（FROM_SCRATCH 结果也需要用于继续计算）
        Map<String, Object> ruleOutputState = new HashMap<>();
        ruleOutputState.put(RULE_TYPE, toMap(state));

        return BillingSegmentResult.builder()
                .segmentId(context.getSegment().getId())
                .segmentStartTime(context.getSegment().getBeginTime())
                .segmentEndTime(context.getSegment().getEndTime())
                .calculationStartTime(calcBegin)
                .calculationEndTime(extendedCalculationEndTime)
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

        while (current.isBefore(calcEnd)) {
            // 计算当前周期起止
            LocalDateTime cycleStart = current;
            LocalDateTime cycleEnd = cycleStart.plusMinutes(MINUTES_PER_CYCLE);
            if (cycleEnd.isAfter(calcEnd)) {
                cycleEnd = calcEnd;
            }

            CycleUnits cycle = new CycleUnits(cycleStart, cycleEnd);

            // 在当前周期内按时间段生成计费单元
            for (RelativeTimePeriod period : config.getPeriods()) {
                generateUnitsInPeriod(cycle, period, freeTimeRanges);
            }

            // 应用周期封顶（考虑已有累计金额）
            applyCycleCapWithCarryOver(cycle, config.getMaxChargeOneCycle(), carryOverAccumulated);

            cycles.add(cycle);

            // 重置累计金额（新周期）
            carryOverAccumulated = BigDecimal.ZERO;
            current = cycleStart.plusMinutes(MINUTES_PER_CYCLE);
        }

        return cycles;
    }

    /**
     * 应用周期封顶（考虑结转的累计金额）
     */
    private void applyCycleCapWithCarryOver(CycleUnits cycle, BigDecimal maxCharge, BigDecimal carryOverAccumulated) {
        if (maxCharge == null || maxCharge.compareTo(BigDecimal.ZERO) <= 0) {
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

        if (totalAccumulated.compareTo(maxCharge) <= 0) {
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

        // 遍历所有时间段，找到第一个大于当前位置的边界
        for (RelativeTimePeriod period : config.getPeriods()) {
            // 时间段结束边界
            long periodEndMinute = period.getEndMinute();
            LocalDateTime periodBoundary = calcBegin.plusMinutes(periodEndMinute);

            if (periodBoundary.isAfter(current)) {
                return periodBoundary;
            }
        }

        // 如果当前周期内没有，检查下一个周期
        // 下一个周期的第一个时间段边界
        RelativeTimePeriod firstPeriod = config.getPeriods().get(0);
        return calcBegin.plusMinutes(MINUTES_PER_CYCLE).plusMinutes(firstPeriod.getBeginMinute());
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
     * 计算延伸后的结束时间并更新最后一个计费单元
     * 延伸规则：
     * 1. 普通情况：恢复到完整单元长度，但不能超过下一个边界
     * 2. 例外情况：如果因封顶而免费（CYCLE_CAP），可延伸到下一个周期边界
     * @param allUnits 所有计费单元
     * @param calcBegin 计费起点
     * @param calcEnd 计算结束时间（原截断点）
     * @param config 规则配置
     * @return 延伸后的 calculationEndTime
     */
    private LocalDateTime extendLastUnit(List<BillingUnit> allUnits,
                                         LocalDateTime calcBegin,
                                         LocalDateTime calcEnd,
                                         RelativeTimeConfig config) {
        if (allUnits == null || allUnits.isEmpty()) {
            return calcEnd;
        }

        BillingUnit lastUnit = allUnits.get(allUnits.size() - 1);

        // 只有当结束时间等于 calcEnd 时才需要延伸
        if (!lastUnit.getEndTime().equals(calcEnd)) {
            // 单元已经被其他边界截断，不需要延伸
            return lastUnit.getEndTime();
        }

        // 查找下一个周期边界
        LocalDateTime nextCycleBoundary = findNextCycleBoundary(lastUnit.getBeginTime(), calcBegin);

        // 例外情况：如果因封顶而免费，可延伸到下一个周期边界
        if (lastUnit.isFree() && "CYCLE_CAP".equals(lastUnit.getFreePromotionId())) {
            if (nextCycleBoundary != null && nextCycleBoundary.isAfter(calcEnd)) {
                lastUnit.setEndTime(nextCycleBoundary);
                lastUnit.setDurationMinutes((int) Duration.between(lastUnit.getBeginTime(), nextCycleBoundary).toMinutes());
                return nextCycleBoundary;
            }
        }

        // 找到最后一个单元对应的 period，获取单元长度
        int minutesFromCalcBegin = (int) Duration.between(calcBegin, lastUnit.getBeginTime()).toMinutes();
        RelativeTimePeriod period = findPeriodForMinute(minutesFromCalcBegin, config.getPeriods());
        int unitMinutes = period.getUnitMinutes();

        // 计算完整单元结束时间
        LocalDateTime fullUnitEnd = lastUnit.getBeginTime().plusMinutes(unitMinutes);

        // 查找下一个时间段边界（不能超过边界）
        // 注意：如果时间段边界等于 calcEnd，则不应该作为限制条件
        // 因为单元就是被 calcEnd 截断的，应该能延伸过这个边界
        LocalDateTime nextPeriodBoundary = findNextPeriodBoundary(lastUnit.getBeginTime(), calcBegin, config);
        if (nextPeriodBoundary != null && nextPeriodBoundary.equals(calcEnd)) {
            nextPeriodBoundary = findNextPeriodBoundary(nextPeriodBoundary, calcBegin, config);
        }

        // 取最近的边界
        LocalDateTime nextBoundary = null;
        if (nextPeriodBoundary != null && nextCycleBoundary != null) {
            nextBoundary = nextPeriodBoundary.isBefore(nextCycleBoundary) ? nextPeriodBoundary : nextCycleBoundary;
        } else if (nextPeriodBoundary != null) {
            nextBoundary = nextPeriodBoundary;
        } else if (nextCycleBoundary != null) {
            nextBoundary = nextCycleBoundary;
        }

        // 延伸后的结束时间 = min(完整单元结束时间, 下一个边界)
        LocalDateTime extendedEnd = fullUnitEnd;
        if (nextBoundary != null && nextBoundary.isBefore(fullUnitEnd)) {
            extendedEnd = nextBoundary;
        }

        // 如果延伸后的时间不比当前结束时间晚，不需要延伸
        if (!extendedEnd.isAfter(calcEnd)) {
            return calcEnd;
        }

        // 更新最后一个单元
        int extendedDuration = (int) Duration.between(lastUnit.getBeginTime(), extendedEnd).toMinutes();
        lastUnit.setEndTime(extendedEnd);
        lastUnit.setDurationMinutes(extendedDuration);

        return extendedEnd;
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
            state = RuleState.builder()
                    .cycleIndex(0)
                    .cycleAccumulated(BigDecimal.ZERO)
                    .cycleBoundary(calcBegin.plusMinutes(MINUTES_PER_CYCLE))
                    .build();
        } else {
            // CONTINUE: 更新周期状态
            while (state.getCycleBoundary() != null && !calcBegin.isBefore(state.getCycleBoundary())) {
                state.setCycleIndex(state.getCycleIndex() + 1);
                state.setCycleAccumulated(BigDecimal.ZERO);
                state.setCycleBoundary(state.getCycleBoundary().plusMinutes(MINUTES_PER_CYCLE));
            }
        }

        List<FreeTimeRange> freeTimeRanges = promotionAggregate != null && promotionAggregate.getFreeTimeRanges() != null
                ? promotionAggregate.getFreeTimeRanges()
                : List.of();

        // 按免费时段边界切分时间轴
        List<TimeFragment> fragments = splitTimeAxis(calcBegin, calcEnd, freeTimeRanges);

        // 按周期组织片段
        List<CycleFragments> cycles = organizeByCycle(calcBegin, calcEnd, fragments);

        // 对每个周期的片段生成计费单元并应用封顶（考虑结转的累计金额）
        BigDecimal carryOverAccumulated = state.getCycleAccumulated();
        List<BillingUnit> allUnits = new ArrayList<>();
        for (CycleFragments cycle : cycles) {
            List<BillingUnit> cycleUnits = generateUnitsForCycle(cycle, config);
            applyContinuousCapWithCarryOver(cycleUnits, config.getMaxChargeOneCycle(), carryOverAccumulated);
            allUnits.addAll(cycleUnits);
            // 新周期重置累计金额
            carryOverAccumulated = BigDecimal.ZERO;
        }

        // 更新最终状态（FROM_SCRATCH 结果也需要用于继续计算）
        if (!cycles.isEmpty()) {
            // 更新周期索引和边界
            state.setCycleIndex(state.getCycleIndex() + cycles.size() - 1);
            state.setCycleBoundary(cycles.get(cycles.size() - 1).cycleStart.plusMinutes(MINUTES_PER_CYCLE));
            // 计算最后一个周期的累计金额（非免费单元）
            LocalDateTime lastCycleStart = cycles.get(cycles.size() - 1).cycleStart;
            LocalDateTime lastCycleEnd = cycles.get(cycles.size() - 1).cycleEnd;
            BigDecimal lastCycleAmount = allUnits.stream()
                    .filter(u -> !u.isFree() && !u.getBeginTime().isBefore(lastCycleStart) && u.getEndTime().compareTo(lastCycleEnd) <= 0)
                    .map(BillingUnit::getChargedAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            state.setCycleAccumulated(lastCycleAmount);
        }

        BigDecimal totalAmount = allUnits.stream()
                .map(BillingUnit::getChargedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        LocalDateTime feeEffectiveStart = calculateEffectiveFrom(allUnits);
        LocalDateTime feeEffectiveEnd = calculateEffectiveTo(allUnits, freeTimeRanges, calcBegin, calcEnd);

        // 延伸最后一个计费单元
        LocalDateTime extendedCalculationEndTime = extendLastUnit(allUnits, calcBegin, calcEnd, config);

        // 如果延伸后的时间超过 effectiveEnd，更新 effectiveEnd
        if (extendedCalculationEndTime.isAfter(feeEffectiveEnd)) {
            feeEffectiveEnd = extendedCalculationEndTime;
        }

        // 构建输出状态（FROM_SCRATCH 结果也需要用于继续计算）
        Map<String, Object> ruleOutputState = new HashMap<>();
        ruleOutputState.put(RULE_TYPE, toMap(state));

        return BillingSegmentResult.builder()
                .segmentId(context.getSegment().getId())
                .segmentStartTime(context.getSegment().getBeginTime())
                .segmentEndTime(context.getSegment().getEndTime())
                .calculationStartTime(calcBegin)
                .calculationEndTime(extendedCalculationEndTime)  // 使用延伸后的时间
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
    private List<CycleFragments> organizeByCycle(LocalDateTime calcBegin, LocalDateTime calcEnd, List<TimeFragment> fragments) {
        List<CycleFragments> cycles = new ArrayList<>();

        LocalDateTime cycleStart = calcBegin;
        LocalDateTime cycleEnd = calcBegin.plusMinutes(MINUTES_PER_CYCLE);

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
     */
    private void applyContinuousCapWithCarryOver(List<BillingUnit> units, BigDecimal maxCharge, BigDecimal carryOverAccumulated) {
        if (maxCharge == null || maxCharge.compareTo(BigDecimal.ZERO) <= 0) {
            return;
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
            return;
        }

        units.get(capIndex).setChargedAmount(lastChargeAmount.setScale(2, RoundingMode.HALF_UP));

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