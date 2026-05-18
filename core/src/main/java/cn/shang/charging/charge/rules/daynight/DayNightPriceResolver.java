package cn.shang.charging.charge.rules.daynight;

import cn.shang.charging.charge.rules.compositetime.CrossPeriodMode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * `dayNight` 规则的时段判定与价格解析逻辑。
 * <p>
 * 支持多种跨时段处理模式，复用 CrossPeriodMode。
 */
final class DayNightPriceResolver {

    DayNightPeriodType determinePeriodType(LocalDateTime begin, LocalDateTime end, DayNightConfig config) {
        int dayBeginMin = config.getDayBeginMinute();
        int dayEndMin = config.getDayEndMinute();
        int beginDayMin = begin.getHour() * 60 + begin.getMinute();
        int endDayMin = end.getHour() * 60 + end.getMinute();

        if (!begin.toLocalDate().equals(end.toLocalDate())) {
            return DayNightPeriodType.MIXED;
        }

        boolean beginInDay = isInDayPeriod(beginDayMin, dayBeginMin, dayEndMin);
        boolean crossesBoundary = crossesDayNightBoundary(beginDayMin, endDayMin, dayBeginMin, dayEndMin);

        if (!crossesBoundary) {
            return beginInDay ? DayNightPeriodType.DAY : DayNightPeriodType.NIGHT;
        }

        return DayNightPeriodType.MIXED;
    }

    int[] calculateDayNightMinutes(LocalDateTime begin, LocalDateTime end, DayNightConfig config) {
        int dayMins = 0;
        int nightMins = 0;
        int dayBeginMin = config.getDayBeginMinute();
        int dayEndMin = config.getDayEndMinute();

        LocalDateTime current = begin;
        while (current.isBefore(end)) {
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

    BigDecimal determineFinalAmount(DayNightPeriodType periodType, int dayMinutes, int duration,
                                    DayNightConfig config, LocalDateTime begin, LocalDateTime end) {
        if (periodType == DayNightPeriodType.DAY) {
            return config.getDayUnitPrice();
        }
        if (periodType == DayNightPeriodType.NIGHT) {
            return config.getNightUnitPrice();
        }

        // MIXED: 根据 crossPeriodMode 处理
        CrossPeriodMode mode = config.getCrossPeriodMode();
        return switch (mode) {
            case BEGIN_TIME_TRUNCATE, BEGIN_TIME_PRICE, BLOCK_WEIGHT -> {
                // BLOCK_WEIGHT 使用传统逻辑（根据白天分钟占比）
                if (mode == CrossPeriodMode.BLOCK_WEIGHT) {
                    BigDecimal ratio = BigDecimal.valueOf(dayMinutes)
                            .divide(BigDecimal.valueOf(duration), 4, RoundingMode.HALF_UP);
                    yield ratio.compareTo(config.getBlockWeight()) >= 0
                            ? config.getDayUnitPrice()
                            : config.getNightUnitPrice();
                }
                // BEGIN_TIME_PRICE/TRUNCATE: 使用开始时间的价格
                yield getBeginTimePrice(begin, config);
            }
            case END_TIME_PRICE -> getEndTimePrice(end, config);
            case HIGHER_PRICE -> config.getDayUnitPrice().max(config.getNightUnitPrice());
            case LOWER_PRICE -> config.getDayUnitPrice().min(config.getNightUnitPrice());
            case PROPORTIONAL -> calculateProportionalAmount(dayMinutes, duration - dayMinutes, config);
        };
    }

    BigDecimal determineUnitPriceForContinuous(LocalDateTime begin, LocalDateTime end, DayNightConfig config) {
        DayNightPeriodType periodType = determinePeriodType(begin, end, config);
        if (periodType == DayNightPeriodType.DAY) {
            return config.getDayUnitPrice();
        }
        if (periodType == DayNightPeriodType.NIGHT) {
            return config.getNightUnitPrice();
        }

        // MIXED: 根据 crossPeriodMode 处理
        int[] mins = calculateDayNightMinutes(begin, end, config);
        int duration = (int) Duration.between(begin, end).toMinutes();
        return determineFinalAmount(periodType, mins[0], duration, config, begin, end);
    }

    private BigDecimal getBeginTimePrice(LocalDateTime time, DayNightConfig config) {
        int minute = time.getHour() * 60 + time.getMinute();
        boolean inDay = isInDayPeriod(minute, config.getDayBeginMinute(), config.getDayEndMinute());
        return inDay ? config.getDayUnitPrice() : config.getNightUnitPrice();
    }

    private BigDecimal getEndTimePrice(LocalDateTime time, DayNightConfig config) {
        int minute = time.getHour() * 60 + time.getMinute();
        // 结束时间点用前一分钟判断（避免边界问题）
        if (minute == 0) minute = 1440;
        boolean inDay = isInDayPeriod(minute - 1, config.getDayBeginMinute(), config.getDayEndMinute());
        return inDay ? config.getDayUnitPrice() : config.getNightUnitPrice();
    }

    private BigDecimal calculateProportionalAmount(int dayMins, int nightMins, DayNightConfig config) {
        BigDecimal dayAmount = config.getDayUnitPrice().multiply(BigDecimal.valueOf(dayMins));
        BigDecimal nightAmount = config.getNightUnitPrice().multiply(BigDecimal.valueOf(nightMins));
        int totalMins = dayMins + nightMins;
        return dayAmount.add(nightAmount)
                .divide(BigDecimal.valueOf(totalMins), 2, RoundingMode.HALF_UP);
    }

    private boolean isInDayPeriod(int minute, int dayBeginMin, int dayEndMin) {
        if (dayBeginMin < dayEndMin) {
            return minute >= dayBeginMin && minute < dayEndMin;
        }
        return minute >= dayBeginMin || minute < dayEndMin;
    }

    private boolean crossesDayNightBoundary(int beginMin, int endMin, int dayBeginMin, int dayEndMin) {
        if (dayBeginMin < dayEndMin) {
            boolean crossesDayBegin = beginMin < dayBeginMin && endMin > dayBeginMin;
            boolean crossesDayEnd = beginMin < dayEndMin && endMin > dayEndMin;
            return crossesDayBegin || crossesDayEnd;
        }
        return true;
    }
}
