package cn.shang.charging.charge.rules.naturaltime;

import cn.shang.charging.charge.rules.compositetime.NaturalPeriod;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * `naturalTime` 规则的自然时段定价解析器。
 * <p>
 * naturalTime 在自然时段边界统一切断，不再暴露 crossPeriodMode。
 */
final class NaturalTimeCrossPeriodPriceResolver {

    private final NaturalTimePeriodResolver periodResolver = new NaturalTimePeriodResolver();

    /**
     * 计算单元价格
     */
    BigDecimal calculateUnitPrice(LocalDateTime unitBegin, LocalDateTime unitEnd,
                                  List<NaturalPeriod> periods) {
        int beginMinuteOfDay = unitBegin.getHour() * 60 + unitBegin.getMinute();
        NaturalPeriod beginPeriod = periodResolver.findPeriodByMinute(beginMinuteOfDay, periods);
        return beginPeriod.getUnitPrice();
    }
}
