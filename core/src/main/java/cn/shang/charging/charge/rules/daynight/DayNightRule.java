package cn.shang.charging.charge.rules.daynight;

import cn.shang.charging.billing.pojo.*;
import cn.shang.charging.charge.rules.BillingRule;
import cn.shang.charging.promotion.pojo.FreeTimeRange;
import cn.shang.charging.promotion.pojo.PromotionAggregate;
import cn.shang.charging.promotion.pojo.PromotionUsage;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 日夜计费规则
 * <p>
 * 计费逻辑：
 * - 每 24 小时为一个计费周期，从计费开始时间算起
 * - 白天和夜晚价格不同，由 DayNightConfig 配置
 * - 每日限额：每 24 小时的最高收费
 * - 白天黑夜比例：当单位时间跨越白天黑夜时，按白天分钟数占比决定使用哪种价格（0-1）
 */
public class DayNightRule implements BillingRule<DayNightConfig> {

    private static final String DAY_TYPE = "DAY";
    private static final String NIGHT_TYPE = "NIGHT";

    @Override
    public Class<DayNightConfig> configClass() {
        return DayNightConfig.class;
    }

    @Override
    public BillingSegmentResult calculate(BillingContext context, DayNightConfig ruleConfig, PromotionAggregate promotionAggregate) {
        // 获取计算窗口
        CalculationWindow window = context.getWindow();
        LocalDateTime calcBegin = window.getCalculationBegin();
        LocalDateTime calcEnd = window.getCalculationEnd();

        // 获取免费时间段列表
        List<FreeTimeRange> freeRanges = promotionAggregate.getFreeTimeRanges();

        // 计费单元列表
        List<BillingUnit> billingUnits = new ArrayList<>();

        // 总金额
        BigDecimal totalAmount = BigDecimal.ZERO;

        // 已计费时长（分钟）
        int chargedDuration = 0;

        // 每日限额跟踪：key=周期起始日期，value=已累积金额
        // 由于是从计费开始时间算 24 小时周期，需要特殊处理
        Map<LocalDateTime, BigDecimal> dailyCapUsed = new HashMap<>();

        // 当前处理时间
        LocalDateTime cursor = calcBegin;

        // 单位时间长度（分钟）
        int unitMinutes = ruleConfig.getUnitMinutes();

        // 白天开始/结束分钟数
        int dayBeginMinute = ruleConfig.getDayBeginMinute();
        int dayEndMinute = ruleConfig.getDayEndMinute();

        // 白天价格/夜晚价格
        BigDecimal dayUnitPrice = ruleConfig.getDayUnitPrice();
        BigDecimal nightUnitPrice = ruleConfig.getNightUnitPrice();

        // 每日限额
        BigDecimal maxChargeOneDay = ruleConfig.getMaxChargeOneDay();

        // 白天黑夜比例阈值
        BigDecimal blockWeight = ruleConfig.getBlockWeight();

        // 按单位时间逐步计算
        boolean shouldContinue = true;
        while (cursor.isBefore(calcEnd) && shouldContinue) {
            // 计算当前单位的结束时间
            LocalDateTime unitEnd = cursor.plusMinutes(unitMinutes);
            if (unitEnd.isAfter(calcEnd)) {
                unitEnd = calcEnd;
            }

            // 实际时长（可能最后一个单位不足 unitMinutes）
            int actualMinutes = (int) Duration.between(cursor, unitEnd).toMinutes();
            if (actualMinutes <= 0) {
                shouldContinue = false;
            } else {

                // 检查当前时间段是否完全在免费时间段内
                FreeTimeRange coveringFreeRange = findCoveringFreeRange(cursor, unitEnd, freeRanges);

                if (coveringFreeRange != null) {
                    // 整个单位时间免费
                    BillingUnit unit = BillingUnit.builder()
                            .beginTime(cursor)
                            .endTime(unitEnd)
                            .durationMinutes(actualMinutes)
                            .chargeType(DAY_TYPE) // 免费时段不区分日夜
                            .unitPrice(BigDecimal.ZERO)
                            .amount(BigDecimal.ZERO)
                            .isFree(true)
                            .freePromotionId(coveringFreeRange.getId())
                            .build();
                    billingUnits.add(unit);
                } else {
                    // 需要计费：判断日夜类型
                    DayNightChargeResult chargeResult = calculateDayNightCharge(
                            cursor, unitEnd, actualMinutes,
                            dayBeginMinute, dayEndMinute,
                            dayUnitPrice, nightUnitPrice,
                            blockWeight
                    );

                    // 检查每日限额
                    BigDecimal chargeAmount = chargeResult.amount;

                    // 计算当前单位所属的 24 小时周期起始时间
                    LocalDateTime cycleStart = getCycleStart(cursor, calcBegin, unitMinutes);
                    BigDecimal usedInCycle = dailyCapUsed.getOrDefault(cycleStart, BigDecimal.ZERO);
                    BigDecimal remainingInCycle = maxChargeOneDay.subtract(usedInCycle);

                    if (remainingInCycle.compareTo(BigDecimal.ZERO) <= 0) {
                        // 已达到每日限额，本单元免费
                        BillingUnit unit = BillingUnit.builder()
                                .beginTime(cursor)
                                .endTime(unitEnd)
                                .durationMinutes(actualMinutes)
                                .chargeType(chargeResult.chargeType)
                                .unitPrice(chargeResult.unitPrice)
                                .amount(BigDecimal.ZERO)
                                .isFree(true)
                                .freePromotionId("DAILY_CAP")
                                .build();
                        billingUnits.add(unit);
                        chargedDuration += actualMinutes;
                    } else if (remainingInCycle.compareTo(chargeAmount) < 0) {
                        // 部分超出限额
                        BigDecimal actualCharge = remainingInCycle;
                        BillingUnit unit = BillingUnit.builder()
                                .beginTime(cursor)
                                .endTime(unitEnd)
                                .durationMinutes(actualMinutes)
                                .chargeType(chargeResult.chargeType)
                                .unitPrice(chargeResult.unitPrice)
                                .amount(actualCharge)
                                .isFree(false)
                                .build();
                        billingUnits.add(unit);
                        totalAmount = totalAmount.add(actualCharge);
                        chargedDuration += actualMinutes;
                        dailyCapUsed.put(cycleStart, maxChargeOneDay); // 标记为已用满
                    } else {
                        // 未超出限额，正常计费
                        BillingUnit unit = BillingUnit.builder()
                                .beginTime(cursor)
                                .endTime(unitEnd)
                                .durationMinutes(actualMinutes)
                                .chargeType(chargeResult.chargeType)
                                .unitPrice(chargeResult.unitPrice)
                                .amount(chargeAmount)
                                .isFree(false)
                                .build();
                        billingUnits.add(unit);
                        totalAmount = totalAmount.add(chargeAmount);
                        chargedDuration += actualMinutes;
                        dailyCapUsed.put(cycleStart, usedInCycle.add(chargeAmount));
                    }

                }

                // 移动到下一个单位
                cursor = unitEnd;
            }
        }

        // 构建结果
        return BillingSegmentResult.builder()
                .segmentId(context.getSegment().getSchemeId())
                .segmentStartTime(context.getSegment().getBeginTime())
                .segmentEndTime(context.getSegment().getEndTime())
                .calculationStartTime(window.getCalculationBegin())
                .calculationEndTime(window.getCalculationEnd())
                .chargedAmount(totalAmount)
                .chargedDuration(chargedDuration)
                .billingUnits(billingUnits)
                .promotionUsages(promotionAggregate.getUsages())
                .build();
    }

    /**
     * 查找覆盖给定时间段的免费时间段
     */
    private FreeTimeRange findCoveringFreeRange(LocalDateTime begin, LocalDateTime end, List<FreeTimeRange> freeRanges) {
        if (freeRanges == null || freeRanges.isEmpty()) {
            return null;
        }
        for (FreeTimeRange range : freeRanges) {
            // 检查免费时间段是否完全覆盖当前计费单元
            if (!range.getBeginTime().isAfter(begin) && !range.getEndTime().isBefore(end)) {
                return range;
            }
        }
        return null;
    }

    /**
     * 计算日夜计费结果
     */
    private DayNightChargeResult calculateDayNightCharge(
            LocalDateTime begin, LocalDateTime end, int totalMinutes,
            int dayBeginMinute, int dayEndMinute,
            BigDecimal dayUnitPrice, BigDecimal nightUnitPrice,
            BigDecimal blockWeight) {

        // 计算白天分钟数和夜晚分钟数
        int dayMinutes = calculateDayMinutes(begin, end, dayBeginMinute, dayEndMinute);
        int nightMinutes = totalMinutes - dayMinutes;

        // 判断使用哪种价格
        // 如果白天分钟数占比 >= blockWeight，使用白天价格；否则使用夜晚价格
        BigDecimal dayRatio = BigDecimal.valueOf(dayMinutes).divide(BigDecimal.valueOf(totalMinutes), 4, RoundingMode.HALF_UP);

        String chargeType;
        BigDecimal unitPrice;

        if (dayRatio.compareTo(blockWeight) >= 0) {
            chargeType = DAY_TYPE;
            unitPrice = dayUnitPrice;
        } else {
            chargeType = NIGHT_TYPE;
            unitPrice = nightUnitPrice;
        }

        // 计算金额（按单位价格，不考虑时长比例，因为单位时间已经标准化）
        BigDecimal amount = unitPrice;

        return new DayNightChargeResult(chargeType, unitPrice, amount);
    }

    /**
     * 计算时间段内的白天分钟数
     */
    private int calculateDayMinutes(LocalDateTime begin, LocalDateTime end, int dayBeginMinute, int dayEndMinute) {
        int totalDayMinutes = 0;

        // 处理跨天情况
        LocalDate startDate = begin.toLocalDate();
        LocalDate endDate = end.toLocalDate();

        if (startDate.equals(endDate)) {
            // 同一天内
            return calculateDayMinutesSingleDay(begin, end, dayBeginMinute, dayEndMinute);
        } else {
            // 跨天：分段计算
            // 第一段：begin 到当天结束
            LocalDateTime dayEnd = startDate.atTime(23, 59, 59);
            totalDayMinutes += calculateDayMinutesSingleDay(begin, dayEnd, dayBeginMinute, dayEndMinute);

            // 中间完整的天
            LocalDate current = startDate.plusDays(1);
            while (current.isBefore(endDate)) {
                // 完整一天的白天分钟数
                totalDayMinutes += (dayEndMinute - dayBeginMinute);
                current = current.plusDays(1);
            }

            // 最后一段：当天开始到 end
            LocalDateTime dayStart = endDate.atTime(0, 0, 0);
            totalDayMinutes += calculateDayMinutesSingleDay(dayStart, end, dayBeginMinute, dayEndMinute);
        }

        return totalDayMinutes;
    }

    /**
     * 计算单天内的白天分钟数
     */
    private int calculateDayMinutesSingleDay(LocalDateTime begin, LocalDateTime end, int dayBeginMinute, int dayEndMinute) {
        // 将时间转换为分钟数（从 0 点开始）
        int beginMinuteOfDay = begin.getHour() * 60 + begin.getMinute();
        int endMinuteOfDay = end.getHour() * 60 + end.getMinute();

        // 白天时间段 [dayBeginMinute, dayEndMinute]
        // 计算重叠部分
        int overlapStart = Math.max(beginMinuteOfDay, dayBeginMinute);
        int overlapEnd = Math.min(endMinuteOfDay, dayEndMinute);

        if (overlapStart < overlapEnd) {
            return overlapEnd - overlapStart;
        }
        return 0;
    }

    /**
     * 获取给定时间所属的 24 小时周期起始时间
     * 周期从计费开始时间 calcBegin 算起
     */
    private LocalDateTime getCycleStart(LocalDateTime currentTime, LocalDateTime calcBegin, int unitMinutes) {
        // 计算从 calcBegin 到 currentTime 经过了多少分钟
        long minutesFromBegin = Duration.between(calcBegin, currentTime).toMinutes();

        // 计算这是第几个周期（每 24 小时=1440 分钟为一个周期）
        long cycleIndex = minutesFromBegin / 1440;

        // 返回周期起始时间
        return calcBegin.plusMinutes(cycleIndex * 1440);
    }

    /**
     * 内部类：日夜计费结果
     */
    private static class DayNightChargeResult {
        String chargeType;
        BigDecimal unitPrice;
        BigDecimal amount;

        DayNightChargeResult(String chargeType, BigDecimal unitPrice, BigDecimal amount) {
            this.chargeType = chargeType;
            this.unitPrice = unitPrice;
            this.amount = amount;
        }
    }
}
