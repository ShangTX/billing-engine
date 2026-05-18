package cn.shang.charging.charge.rules.naturaltime;

import cn.shang.charging.charge.rules.compositetime.CrossPeriodMode;
import cn.shang.charging.charge.rules.compositetime.NaturalPeriod;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

/**
 * `naturalTime` 规则的跨自然时段定价解析器。
 * <p>
 * 复用 compositeTime 的跨时段定价逻辑。
 */
final class NaturalTimeCrossPeriodPriceResolver {

    private static final int MINUTES_PER_DAY = 1440;

    private final NaturalTimePeriodResolver periodResolver = new NaturalTimePeriodResolver();

    /**
     * 计算单元价格
     */
    BigDecimal calculateUnitPrice(LocalDateTime unitBegin, LocalDateTime unitEnd,
                                  List<NaturalPeriod> periods, CrossPeriodMode mode) {
        int beginMinuteOfDay = unitBegin.getHour() * 60 + unitBegin.getMinute();
        int endMinuteOfDay = unitEnd.getHour() * 60 + unitEnd.getMinute();
        if (endMinuteOfDay == 0) {
            endMinuteOfDay = MINUTES_PER_DAY;
        }

        NaturalPeriod beginPeriod = periodResolver.findPeriodByMinute(beginMinuteOfDay, periods);
        NaturalPeriod endPeriod = periodResolver.findPeriodByMinute(
                endMinuteOfDay == MINUTES_PER_DAY ? 0 : endMinuteOfDay, periods);

        // 同时段或价格相同
        if (beginPeriod == endPeriod || beginPeriod.getUnitPrice().equals(endPeriod.getUnitPrice())) {
            return beginPeriod.getUnitPrice();
        }

        // 跨时段处理
        return handleCrossPeriod(beginMinuteOfDay, endMinuteOfDay, beginPeriod, endPeriod, mode, periods);
    }

    private BigDecimal handleCrossPeriod(int beginMinute, int endMinute,
                                          NaturalPeriod beginPeriod, NaturalPeriod endPeriod,
                                          CrossPeriodMode mode, List<NaturalPeriod> periods) {
        return switch (mode) {
            case BEGIN_TIME_TRUNCATE, BEGIN_TIME_PRICE, BLOCK_WEIGHT -> beginPeriod.getUnitPrice();
            case END_TIME_PRICE -> endPeriod.getUnitPrice();
            case HIGHER_PRICE -> beginPeriod.getUnitPrice().max(endPeriod.getUnitPrice());
            case LOWER_PRICE -> beginPeriod.getUnitPrice().min(endPeriod.getUnitPrice());
            case PROPORTIONAL -> calculateProportionalPrice(beginMinute, endMinute, periods);
        };
    }

    /**
     * 按比例计算跨时段价格
     */
    private BigDecimal calculateProportionalPrice(int beginMinute, int endMinute, List<NaturalPeriod> periods) {
        int totalMinutes;
        if (endMinute > beginMinute) {
            totalMinutes = endMinute - beginMinute;
        } else {
            totalMinutes = MINUTES_PER_DAY - beginMinute + endMinute;
        }

        BigDecimal totalAmount = BigDecimal.ZERO;
        int currentMinute = beginMinute;

        while (currentMinute != endMinute) {
            NaturalPeriod period = periodResolver.findPeriodByMinute(currentMinute, periods);
            int periodEnd = period.getEndMinute();

            // 计算当前时段内的分钟数
            int minutesInPeriod;
            if (periodEnd > currentMinute) {
                minutesInPeriod = Math.min(periodEnd, endMinute > beginMinute ? endMinute : MINUTES_PER_DAY) - currentMinute;
            } else {
                // 跨天时段
                minutesInPeriod = MINUTES_PER_DAY - currentMinute;
                if (endMinute < beginMinute) {
                    minutesInPeriod = Math.min(minutesInPeriod, MINUTES_PER_DAY - beginMinute + endMinute - (MINUTES_PER_DAY - currentMinute));
                }
            }

            if (minutesInPeriod > 0) {
                BigDecimal periodAmount = period.getUnitPrice()
                        .multiply(BigDecimal.valueOf(minutesInPeriod))
                        .divide(BigDecimal.valueOf(totalMinutes), 2, RoundingMode.HALF_UP);
                totalAmount = totalAmount.add(periodAmount);
            }

            currentMinute = periodEnd == MINUTES_PER_DAY ? 0 : periodEnd;
            if (currentMinute == 0 && endMinute < beginMinute) {
                // 继续处理跨天部分
                if (currentMinute >= endMinute) break;
            }
        }

        return totalAmount;
    }
}