package cn.shang.charging;

import cn.shang.charging.billing.BillingSegment;
import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.BillingContext;
import cn.shang.charging.billing.pojo.BillingSegmentResult;
import cn.shang.charging.billing.pojo.BillingUnit;
import cn.shang.charging.billing.pojo.CalculationWindow;
import cn.shang.charging.charge.rules.compositetime.CrossPeriodMode;
import cn.shang.charging.charge.rules.compositetime.NaturalPeriod;
import cn.shang.charging.charge.rules.naturaltime.NaturalTimeConfig;
import cn.shang.charging.charge.rules.naturaltime.NaturalTimeRule;
import cn.shang.charging.promotion.pojo.PromotionAggregate;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NaturalTimeSmokeTest {

    @Test
    void unitBasedBasicCalculationShouldStayStable() {
        NaturalTimeRule rule = new NaturalTimeRule();
        BillingSegmentResult result = rule.calculate(
                createContext(
                        LocalDateTime.of(2026, 1, 1, 8, 0),
                        LocalDateTime.of(2026, 1, 1, 10, 0),
                        BConstants.BillingMode.UNIT_BASED
                ),
                createSinglePeriodConfig(CrossPeriodMode.BEGIN_TIME_TRUNCATE, BigDecimal.ONE, null),
                PromotionAggregate.builder().build()
        );

        assertEquals(0, BigDecimal.valueOf(2).compareTo(result.getChargedAmount()));
        assertEquals(2, result.getBillingUnits().size());
    }

    @Test
    void unitBasedCrossPeriodHigherPriceShouldStayStable() {
        NaturalTimeConfig config = NaturalTimeConfig.builder()
                .id("test")
                .unitMinutes(60)
                .crossPeriodMode(CrossPeriodMode.HIGHER_PRICE)
                .periods(List.of(
                        NaturalPeriod.builder().beginMinute(0).endMinute(480).unitPrice(BigDecimal.ONE).build(),
                        NaturalPeriod.builder().beginMinute(480).endMinute(1200).unitPrice(BigDecimal.valueOf(2)).build(),
                        NaturalPeriod.builder().beginMinute(1200).endMinute(1440).unitPrice(BigDecimal.ONE).build()
                ))
                .build();

        NaturalTimeRule rule = new NaturalTimeRule();
        BillingSegmentResult result = rule.calculate(
                createContext(
                        LocalDateTime.of(2026, 1, 1, 19, 30),
                        LocalDateTime.of(2026, 1, 1, 20, 30),
                        BConstants.BillingMode.UNIT_BASED
                ),
                config,
                PromotionAggregate.builder().build()
        );

        // 19:30-20:30 跨时段(1元时段->2元时段)，用 HIGHER_PRICE 应收2元
        assertEquals(0, BigDecimal.valueOf(2).compareTo(result.getChargedAmount()));
        assertEquals(1, result.getBillingUnits().size());
    }

    @Test
    void unitBasedDailyCapShouldStayStable() {
        NaturalTimeRule rule = new NaturalTimeRule();
        BillingSegmentResult result = rule.calculate(
                createContext(
                        LocalDateTime.of(2026, 1, 1, 8, 0),
                        LocalDateTime.of(2026, 1, 1, 12, 0),
                        BConstants.BillingMode.UNIT_BASED
                ),
                createSinglePeriodConfig(CrossPeriodMode.BEGIN_TIME_TRUNCATE, BigDecimal.ONE, BigDecimal.valueOf(3)),
                PromotionAggregate.builder().build()
        );

        assertEquals(0, BigDecimal.valueOf(3).compareTo(result.getChargedAmount()));
        BillingUnit lastUnit = result.getBillingUnits().get(result.getBillingUnits().size() - 1);
        assertTrue(lastUnit.isFree());
        assertEquals("DAILY_CAP", lastUnit.getFreePromotionId());
    }

    @Test
    void continuousBasicCalculationShouldStayStable() {
        NaturalTimeRule rule = new NaturalTimeRule();
        BillingSegmentResult result = rule.calculate(
                createContext(
                        LocalDateTime.of(2026, 1, 1, 8, 0),
                        LocalDateTime.of(2026, 1, 1, 10, 0),
                        BConstants.BillingMode.CONTINUOUS
                ),
                createSinglePeriodConfig(CrossPeriodMode.BEGIN_TIME_TRUNCATE, BigDecimal.ONE, null),
                PromotionAggregate.builder().build()
        );

        assertEquals(0, BigDecimal.valueOf(2).compareTo(result.getChargedAmount()));
    }

    @Test
    void continuousCrossPeriodShouldSplitCorrectly() {
        NaturalTimeConfig config = NaturalTimeConfig.builder()
                .id("test")
                .unitMinutes(60)
                .crossPeriodMode(CrossPeriodMode.BEGIN_TIME_TRUNCATE)
                .periods(List.of(
                        NaturalPeriod.builder().beginMinute(0).endMinute(480).unitPrice(BigDecimal.ONE).build(),
                        NaturalPeriod.builder().beginMinute(480).endMinute(1200).unitPrice(BigDecimal.valueOf(2)).build(),
                        NaturalPeriod.builder().beginMinute(1200).endMinute(1440).unitPrice(BigDecimal.ONE).build()
                ))
                .build();

        NaturalTimeRule rule = new NaturalTimeRule();
        BillingSegmentResult result = rule.calculate(
                createContext(
                        LocalDateTime.of(2026, 1, 1, 6, 0),
                        LocalDateTime.of(2026, 1, 1, 10, 0),
                        BConstants.BillingMode.CONTINUOUS
                ),
                config,
                PromotionAggregate.builder().build()
        );

        // 6:00-8:00 在时段1(0-480分钟)，单价1元，收2元
        // 8:00-10:00 在时段2(480-1200分钟)，单价2元，收4元
        assertEquals(0, BigDecimal.valueOf(6).compareTo(result.getChargedAmount()));
    }

    private static NaturalTimeConfig createSinglePeriodConfig(CrossPeriodMode mode, BigDecimal unitPrice, BigDecimal maxChargeOneDay) {
        return NaturalTimeConfig.builder()
                .id("test")
                .unitMinutes(60)
                .crossPeriodMode(mode)
                .maxChargeOneDay(maxChargeOneDay)
                .periods(List.of(
                        NaturalPeriod.builder().beginMinute(0).endMinute(1440).unitPrice(unitPrice).build()
                ))
                .build();
    }

    private static BillingContext createContext(LocalDateTime begin, LocalDateTime end, BConstants.BillingMode mode) {
        CalculationWindow window = new CalculationWindow();
        window.setCalculationBegin(begin);
        window.setCalculationEnd(end);

        BillingSegment segment = BillingSegment.builder()
                .beginTime(begin)
                .build();

        return BillingContext.builder()
                .beginTime(begin)
                .endTime(end)
                .billingMode(mode)
                .segment(segment)
                .window(window)
                .build();
    }
}