package cn.shang.charging.charge.rules.compositetime;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * `compositeTime` 规则的跨自然时段定价解析器。
 */
final class CompositeTimeCrossPeriodPriceResolver {

    private static final int MINUTES_PER_DAY = 1440;

    private final CompositeTimePeriodResolver periodResolver = new CompositeTimePeriodResolver();

    BigDecimal calculateUnitPrice(LocalDateTime unitBegin, LocalDateTime unitEnd, CompositePeriod period) {
        int beginMinuteOfDay = unitBegin.getHour() * 60 + unitBegin.getMinute();
        NaturalPeriod beginPeriod = periodResolver.findNaturalPeriod(beginMinuteOfDay, period.getNaturalPeriods());

        int endMinuteOfDay = unitEnd.getHour() * 60 + unitEnd.getMinute();
        if (endMinuteOfDay == 0) {
            endMinuteOfDay = MINUTES_PER_DAY;
        }
        NaturalPeriod endPeriod = periodResolver.findNaturalPeriod(
                endMinuteOfDay == MINUTES_PER_DAY ? 0 : endMinuteOfDay,
                period.getNaturalPeriods()
        );

        if (beginPeriod == endPeriod || beginPeriod.getUnitPrice().equals(endPeriod.getUnitPrice())) {
            return beginPeriod.getUnitPrice();
        }

        return handleCrossPeriod(unitBegin, unitEnd, period, beginPeriod, endPeriod);
    }

    private BigDecimal handleCrossPeriod(LocalDateTime unitBegin,
                                         LocalDateTime unitEnd,
                                         CompositePeriod period,
                                         NaturalPeriod beginPeriod,
                                         NaturalPeriod endPeriod) {
        CrossPeriodMode mode = period.getCrossPeriodMode();

        return switch (mode) {
            case BLOCK_WEIGHT -> handleBlockWeight(unitBegin, unitEnd, period.getNaturalPeriods());
            case HIGHER_PRICE -> beginPeriod.getUnitPrice().max(endPeriod.getUnitPrice());
            case LOWER_PRICE -> beginPeriod.getUnitPrice().min(endPeriod.getUnitPrice());
            case BEGIN_TIME_PRICE, BEGIN_TIME_TRUNCATE -> beginPeriod.getUnitPrice();
            case END_TIME_PRICE -> endPeriod.getUnitPrice();
            case PROPORTIONAL -> calculateProportionalPrice(unitBegin, unitEnd, period.getNaturalPeriods());
        };
    }

    private BigDecimal handleBlockWeight(LocalDateTime unitBegin, LocalDateTime unitEnd, List<NaturalPeriod> naturalPeriods) {
        int beginMinuteOfDay = unitBegin.getHour() * 60 + unitBegin.getMinute();
        NaturalPeriod beginPeriod = periodResolver.findNaturalPeriod(beginMinuteOfDay, naturalPeriods);
        return beginPeriod.getUnitPrice();
    }

    private BigDecimal calculateProportionalPrice(LocalDateTime unitBegin, LocalDateTime unitEnd, List<NaturalPeriod> naturalPeriods) {
        int totalMinutes = (int) Duration.between(unitBegin, unitEnd).toMinutes();
        BigDecimal totalAmount = BigDecimal.ZERO;

        LocalDateTime current = unitBegin;
        while (current.isBefore(unitEnd)) {
            int currentMinuteOfDay = current.getHour() * 60 + current.getMinute();
            NaturalPeriod np = periodResolver.findNaturalPeriod(currentMinuteOfDay, naturalPeriods);

            LocalDateTime nextBoundary = periodResolver.findNextNaturalPeriodBoundary(current, naturalPeriods);
            if (nextBoundary == null || nextBoundary.isAfter(unitEnd)) {
                nextBoundary = unitEnd;
            }

            int minutesInPeriod = (int) Duration.between(current, nextBoundary).toMinutes();
            BigDecimal periodAmount = np.getUnitPrice()
                    .multiply(BigDecimal.valueOf(minutesInPeriod))
                    .divide(BigDecimal.valueOf(totalMinutes), 2, RoundingMode.HALF_UP);
            totalAmount = totalAmount.add(periodAmount);

            current = nextBoundary;
        }

        return totalAmount;
    }
}
