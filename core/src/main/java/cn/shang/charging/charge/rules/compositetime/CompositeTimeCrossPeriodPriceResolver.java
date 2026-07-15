package cn.shang.charging.charge.rules.compositetime;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * `compositeTime` 规则的自然时段定价解析器。
 * <p>
 * compositeTime 不再配置 crossPeriodMode；自然时段边界由 BoundaryProvider 统一切断，
 * 因此一个同质段使用段起点所在自然时段的价格即可。
 */
final class CompositeTimeCrossPeriodPriceResolver {

    private final CompositeTimePeriodResolver periodResolver = new CompositeTimePeriodResolver();

    BigDecimal calculateUnitPrice(LocalDateTime unitBegin, LocalDateTime unitEnd, CompositePeriod period) {
        int beginMinuteOfDay = unitBegin.getHour() * 60 + unitBegin.getMinute();
        NaturalPeriod beginPeriod = periodResolver.findNaturalPeriod(beginMinuteOfDay, period.getNaturalPeriods());
        return beginPeriod.getUnitPrice();
    }
}
