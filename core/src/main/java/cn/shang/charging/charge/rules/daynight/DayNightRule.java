package cn.shang.charging.charge.rules.daynight;

import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.BillingContext;
import cn.shang.charging.billing.pojo.BillingSegmentResult;
import cn.shang.charging.billing.pojo.BillingUnit;
import cn.shang.charging.charge.rules.AbstractTimeBasedRule;
import cn.shang.charging.charge.rules.BillingRule;
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
            return calculateUnitBased(context, config, promotionAggregate);
        } else {
            return calculateContinuous(context, config, promotionAggregate);
        }
    }

    /**
     * UNIT_BASED 模式计算
     * 固定从计费起点对齐，免费时段必须完全覆盖整个单元才免费
     */
    private BillingSegmentResult calculateUnitBased(BillingContext context, DayNightConfig config, PromotionAggregate promotionAggregate) {
        // 验证配置
        validateConfig(config);

        // 获取计算窗口
        LocalDateTime calcBegin = context.getWindow().getCalculationBegin();
        LocalDateTime calcEnd = context.getWindow().getCalculationEnd();

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
        BigDecimal lastCycleAccumulated = applyDailyCapWithCarryOver(billingUnits, config, state.getCycleAccumulated());

        // 更新最终状态（FROM_SCRATCH 结果也需要用于继续计算）
        int maxCycleIndex = billingUnits.stream()
                .mapToInt(u -> (Integer) u.getRuleData())
                .max().orElse(0);
        state.setCycleIndex(maxCycleIndex);
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
            PeriodType periodType = determinePeriodType(current, unitEnd, config);

            // 计算白天夜间分钟数
            int dayMins = 0, nightMins = 0;
            if (periodType == PeriodType.MIXED) {
                int[] mins = calculateDayNightMinutes(current, unitEnd, config);
                dayMins = mins[0];
                nightMins = mins[1];
            } else if (periodType == PeriodType.DAY) {
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
     * 判断时段类型
     */
    private PeriodType determinePeriodType(LocalDateTime begin, LocalDateTime end, DayNightConfig config) {
        int dayBeginMin = config.getDayBeginMinute();
        int dayEndMin = config.getDayEndMinute();

        // 获取开始时间在当天内的分钟数
        int beginDayMin = begin.getHour() * 60 + begin.getMinute();
        int endDayMin = end.getHour() * 60 + end.getMinute();

        // 是否跨天
        boolean crossDay = !begin.toLocalDate().equals(end.toLocalDate());

        if (crossDay) {
            // 跨天一定是MIXED
            return PeriodType.MIXED;
        }

        // 同一天内，判断是否跨越日夜边界
        boolean beginInDay = isInDayPeriod(beginDayMin, dayBeginMin, dayEndMin);
        boolean endInDay = isInDayPeriod(endDayMin, dayBeginMin, dayEndMin);

        // 检查是否跨越日夜边界
        boolean crossesBoundary = crossesDayNightBoundary(beginDayMin, endDayMin, dayBeginMin, dayEndMin);

        if (!crossesBoundary) {
            if (beginInDay) {
                return PeriodType.DAY;
            } else {
                return PeriodType.NIGHT;
            }
        }

        return PeriodType.MIXED;
    }

    /**
     * 判断分钟数是否在白天时段
     */
    private boolean isInDayPeriod(int minute, int dayBeginMin, int dayEndMin) {
        if (dayBeginMin < dayEndMin) {
            // 白天在一天内，如 12:20-19:00
            return minute >= dayBeginMin && minute < dayEndMin;
        } else {
            // 白天跨天，如 22:00-06:00（较少见）
            return minute >= dayBeginMin || minute < dayEndMin;
        }
    }

    /**
     * 判断是否跨越日夜边界
     */
    private boolean crossesDayNightBoundary(int beginMin, int endMin, int dayBeginMin, int dayEndMin) {
        // 检查时段内是否包含日夜边界点
        if (dayBeginMin < dayEndMin) {
            // 白天时段不跨天
            // 检查是否跨越 dayBeginMin 或 dayEndMin
            boolean crossesDayBegin = beginMin < dayBeginMin && endMin > dayBeginMin;
            boolean crossesDayEnd = beginMin < dayEndMin && endMin > dayEndMin;
            return crossesDayBegin || crossesDayEnd;
        } else {
            // 白天时段跨天（较少见的情况）
            return true;
        }
    }

    /**
     * 计算时段内的白天和夜间分钟数
     */
    private int[] calculateDayNightMinutes(LocalDateTime begin, LocalDateTime end, DayNightConfig config) {
        int dayMins = 0, nightMins = 0;
        int dayBeginMin = config.getDayBeginMinute();
        int dayEndMin = config.getDayEndMinute();

        LocalDateTime current = begin;
        while (current.isBefore(end)) {
            // 每次前进1分钟计算
            int curMin = current.getHour() * 60 + current.getMinute();
            boolean inDay = isInDayPeriod(curMin, dayBeginMin, dayEndMin);
            if (inDay) {
                dayMins++;
            } else {
                nightMins++;
            }
            current = current.plusMinutes(1);
        }

        return new int[]{dayMins, nightMins};
    }

    /**
     * 计算单个计费单元
     */
    private BillingUnit calculateUnit(UnitWithContext unitCtx, DayNightConfig config, List<FreeTimeRange> freeTimeRanges) {
        int duration = (int) Duration.between(unitCtx.beginTime, unitCtx.endTime).toMinutes();

        // 确定单价
        BigDecimal unitPrice;
        if (unitCtx.periodType == PeriodType.DAY) {
            unitPrice = config.getDayUnitPrice();
        } else if (unitCtx.periodType == PeriodType.NIGHT) {
            unitPrice = config.getNightUnitPrice();
        } else {
            // MIXED: 根据blockWeight判断
            BigDecimal ratio = BigDecimal.valueOf(unitCtx.dayMinutes)
                    .divide(BigDecimal.valueOf(duration), 4, RoundingMode.HALF_UP);
            if (ratio.compareTo(config.getBlockWeight()) >= 0) {
                unitPrice = config.getDayUnitPrice();
            } else {
                unitPrice = config.getNightUnitPrice();
            }
        }

        // 计算原始金额：不足单元也收全额
        BigDecimal originalAmount = unitPrice;

        // 检查是否被免费时段覆盖
        String freePromotionId = findFreePromotionId(unitCtx.beginTime, unitCtx.endTime, freeTimeRanges);
        boolean isFree = freePromotionId != null;

        BillingUnit unit = BillingUnit.builder()
                .beginTime(unitCtx.beginTime)
                .endTime(unitCtx.endTime)
                .durationMinutes(duration)
                .unitPrice(unitPrice)
                .originalAmount(originalAmount)
                .free(isFree)
                .freePromotionId(freePromotionId)
                .chargedAmount(isFree ? BigDecimal.ZERO : originalAmount)
                .ruleData(unitCtx.cycleIndex) // 用ruleData存储周期序号
                .build();

        return unit;
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
     * 应用每日封顶
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

            BigDecimal cycleTotal = cycleUnits.stream()
                    .map(BillingUnit::getChargedAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            if (cycleTotal.compareTo(maxCharge) > 0) {
                // 超过封顶，按比例削减
                BigDecimal ratio = maxCharge.divide(cycleTotal, 6, RoundingMode.HALF_UP);
                for (BillingUnit unit : cycleUnits) {
                    if (!unit.isFree()) {
                        BigDecimal newAmount = unit.getChargedAmount().multiply(ratio)
                                .setScale(2, RoundingMode.HALF_UP);
                        unit.setChargedAmount(newAmount);
                    }
                }
            }
        }
    }

    /**
     * 应用每日封顶（考虑结转的累计金额）
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

            // 本周期新增金额
            BigDecimal cycleNewAmount = cycleUnits.stream()
                    .map(BillingUnit::getChargedAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            // 总累计金额 = 结转金额 + 新增金额
            BigDecimal totalAccumulated = accumulated.add(cycleNewAmount);

            if (totalAccumulated.compareTo(maxCharge) > 0) {
                // 超过封顶
                BigDecimal maxAllowed = maxCharge.subtract(accumulated).max(BigDecimal.ZERO);
                if (maxAllowed.compareTo(BigDecimal.ZERO) <= 0) {
                    // 已达封顶，全部免费
                    for (BillingUnit unit : cycleUnits) {
                        if (!unit.isFree()) {
                            unit.setChargedAmount(BigDecimal.ZERO);
                            unit.setFree(true);
                            unit.setFreePromotionId("DAILY_CAP");
                        }
                    }
                    // 触发封顶，累计 = 封顶金额
                    lastCycleAccumulated = maxCharge;
                } else {
                    // 按比例削减
                    BigDecimal ratio = maxAllowed.divide(cycleNewAmount, 6, RoundingMode.HALF_UP);
                    for (BillingUnit unit : cycleUnits) {
                        if (!unit.isFree()) {
                            BigDecimal newAmount = unit.getChargedAmount().multiply(ratio)
                                    .setScale(2, RoundingMode.HALF_UP);
                            unit.setChargedAmount(newAmount);
                            if (newAmount.compareTo(BigDecimal.ZERO) == 0) {
                                unit.setFree(true);
                                unit.setFreePromotionId("DAILY_CAP");
                            }
                        }
                    }
                    // 触发封顶，累计 = 封顶金额
                    lastCycleAccumulated = maxCharge;
                }
            } else {
                // 未超过封顶，累计 = 总累计金额
                lastCycleAccumulated = totalAccumulated;
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
    private BillingSegmentResult calculateContinuous(BillingContext context, DayNightConfig config, PromotionAggregate promotionAggregate) {
        // 验证配置
        validateConfig(config);

        // 获取计算窗口
        LocalDateTime calcBegin = context.getWindow().getCalculationBegin();
        LocalDateTime calcEnd = context.getWindow().getCalculationEnd();

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

        // 获取免费时段
        List<FreeTimeRange> freeTimeRanges = promotionAggregate.getFreeTimeRanges();
        if (freeTimeRanges == null) {
            freeTimeRanges = List.of();
        }

        // 按免费时段边界切分时间轴
        List<TimeFragment> fragments = splitTimeAxis(calcBegin, calcEnd, freeTimeRanges);

        // 按周期组织片段（使用原始计费起点确定周期边界）
        LocalDateTime cycleOriginBegin = context.getBeginTime(); // 原始计费起点
        List<CycleFragments> cycles = organizeByCycle(calcBegin, calcEnd, fragments, cycleOriginBegin);

        // 对每个周期的片段生成计费单元并应用封顶（考虑结转的累计金额）
        BigDecimal carryOverAccumulated = state.getCycleAccumulated();
        List<BillingUnit> allUnits = new ArrayList<>();
        for (CycleFragments cycle : cycles) {
            List<BillingUnit> cycleUnits = generateUnitsForCycle(cycle, config);
            applyContinuousCapWithCarryOver(cycleUnits, config.getMaxChargeOneDay(), carryOverAccumulated);
            allUnits.addAll(cycleUnits);
            // 新周期重置累计金额
            carryOverAccumulated = BigDecimal.ZERO;
        }

        // 更新最终状态（FROM_SCRATCH 结果也需要用于继续计算）
        BigDecimal lastCycleAmount = BigDecimal.ZERO;
        if (!cycles.isEmpty()) {
            // 找到最后一个周期的非免费单元
            LocalDateTime lastCycleEnd = cycles.get(cycles.size() - 1).cycleEnd;
            lastCycleAmount = allUnits.stream()
                    .filter(u -> !u.isFree() && u.getEndTime().compareTo(lastCycleEnd) <= 0 && u.getEndTime().compareTo(cycles.get(cycles.size() - 1).cycleStart) > 0)
                    .map(BillingUnit::getChargedAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            state.setCycleAccumulated(lastCycleAmount);
            // 更新周期索引
            state.setCycleIndex(state.getCycleIndex() + cycles.size() - 1);
            state.setCycleBoundary(cycles.get(cycles.size() - 1).cycleStart.plusHours(24));
        }

        // 汇总结果
        BigDecimal totalAmount = allUnits.stream()
                .map(BillingUnit::getChargedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        LocalDateTime feeEffectiveStart = calculateEffectiveFrom(allUnits);
        LocalDateTime feeEffectiveEnd = calculateEffectiveTo(allUnits, freeTimeRanges, calcBegin, calcEnd);

        // 标记最后一个单元是否被截断
        if (!allUnits.isEmpty()) {
            BillingUnit lastUnit = allUnits.get(allUnits.size() - 1);
            int unitMinutes = config.getUnitMinutes();
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

        // 收集所有切分点（免费时段边界）
        List<LocalDateTime> cutPoints = new ArrayList<>();
        cutPoints.add(begin);

        for (FreeTimeRange range : freeTimeRanges) {
            // 只处理在计费范围内的免费时段
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

        // 去重并排序
        cutPoints = cutPoints.stream().distinct().sorted().toList();

        // 生成片段
        for (int i = 0; i < cutPoints.size() - 1; i++) {
            LocalDateTime fragBegin = cutPoints.get(i);
            LocalDateTime fragEnd = cutPoints.get(i + 1);

            TimeFragment fragment = new TimeFragment(fragBegin, fragEnd);

            // 检查是否匹配某个免费时段
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
        LocalDateTime cycleEnd = cycleOriginBegin.plusHours(24);

        // 找到包含 calcBegin 的周期
        while (cycleEnd.isBefore(calcBegin) || cycleEnd.equals(calcBegin)) {
            cycleStart = cycleEnd;
            cycleEnd = cycleStart.plusHours(24);
        }

        CycleFragments currentCycle = new CycleFragments(cycleStart, cycleEnd.isAfter(calcEnd) ? calcEnd : cycleEnd);

        for (TimeFragment fragment : fragments) {
            // 检查片段是否跨越周期边界
            while (fragment.endTime.isAfter(currentCycle.cycleEnd)) {
                // 切分片段
                TimeFragment beforeBoundary = new TimeFragment(fragment.beginTime, currentCycle.cycleEnd);
                beforeBoundary.isFree = fragment.isFree;
                beforeBoundary.freePromotionId = fragment.freePromotionId;

                currentCycle.fragments.add(beforeBoundary);
                cycles.add(currentCycle);

                // 开始新周期
                cycleStart = currentCycle.cycleEnd;
                cycleEnd = cycleStart.plusHours(24);
                currentCycle = new CycleFragments(cycleStart, cycleEnd.isAfter(calcEnd) ? calcEnd : cycleEnd);

                // 更新原片段
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
    private List<BillingUnit> generateUnitsForCycle(CycleFragments cycle, DayNightConfig config) {
        List<BillingUnit> units = new ArrayList<>();
        int unitMinutes = config.getUnitMinutes();

        for (TimeFragment fragment : cycle.fragments) {
            if (fragment.isFree) {
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
                        .build();
                units.add(unit);
            } else {
                // 收费片段按单元长度划分
                LocalDateTime current = fragment.beginTime;
                while (current.isBefore(fragment.endTime)) {
                    LocalDateTime unitEnd = current.plusMinutes(unitMinutes);
                    if (unitEnd.isAfter(fragment.endTime)) {
                        unitEnd = fragment.endTime;
                    }

                    int duration = (int) Duration.between(current, unitEnd).toMinutes();

                    // 确定单价
                    BigDecimal unitPrice = determineUnitPriceForContinuous(current, unitEnd, config);

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
            }
        }

        return units;
    }

    /**
     * 确定时段单价（CONTINUOUS模式专用）
     */
    private BigDecimal determineUnitPriceForContinuous(LocalDateTime begin, LocalDateTime end, DayNightConfig config) {
        PeriodType periodType = determinePeriodType(begin, end, config);

        if (periodType == PeriodType.DAY) {
            return config.getDayUnitPrice();
        } else if (periodType == PeriodType.NIGHT) {
            return config.getNightUnitPrice();
        } else {
            // MIXED: 根据时长比例判断
            int[] mins = calculateDayNightMinutes(begin, end, config);
            int duration = (int) Duration.between(begin, end).toMinutes();
            BigDecimal ratio = BigDecimal.valueOf(mins[0])
                    .divide(BigDecimal.valueOf(duration), 4, RoundingMode.HALF_UP);
            if (ratio.compareTo(config.getBlockWeight()) >= 0) {
                return config.getDayUnitPrice();
            } else {
                return config.getNightUnitPrice();
            }
        }
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
                        .freePromotionId("DAILY_CAP")
                        .chargedAmount(BigDecimal.ZERO)
                        .build();
                units.add(mergedFreeUnit);
            }
            return;
        }

        BigDecimal accumulated = carryOverAccumulated;
        int capIndex = -1;
        BigDecimal lastChargeAmount = null;

        // 找到封顶位置
        for (int i = 0; i < units.size(); i++) {
            BillingUnit unit = units.get(i);
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

        if (capIndex < 0) {
            return; // 未超过封顶
        }

        // 调整封顶单元的金额
        units.get(capIndex).setChargedAmount(lastChargeAmount.setScale(2, RoundingMode.HALF_UP));
        if (units.get(capIndex).getChargedAmount().compareTo(BigDecimal.ZERO) == 0) {
            units.get(capIndex).setFree(true);
            units.get(capIndex).setFreePromotionId("DAILY_CAP");
        }

        // 封顶后的单元合并为一个免费单元
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
                    .freePromotionId("DAILY_CAP")
                    .chargedAmount(BigDecimal.ZERO)
                    .build();

            // 移除封顶后的单元，添加合并后的免费单元
            units.subList(capIndex + 1, units.size()).clear();
            units.add(mergedFreeUnit);
        }
    }

    /**
     * 时段类型
     */
    private enum PeriodType {
        DAY, NIGHT, MIXED
    }

    /**
     * 内部上下文类，存放计算过程中的专用数据
     */
    private static class UnitWithContext {
        LocalDateTime beginTime;
        LocalDateTime endTime;
        int cycleIndex;
        PeriodType periodType;
        int dayMinutes;
        int nightMinutes;
    }

    /**
     * 时间片段（切分后的时间范围）- CONTINUOUS模式专用
     */
    private static class TimeFragment {
        LocalDateTime beginTime;
        LocalDateTime endTime;
        boolean isFree;
        String freePromotionId;  // 如果是免费片段，记录优惠ID

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
