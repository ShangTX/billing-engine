package cn.shang.charging;

import cn.shang.charging.billing.BillingSegment;
import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.BillingContext;
import cn.shang.charging.billing.pojo.BillingSegmentResult;
import cn.shang.charging.billing.pojo.BillingUnit;
import cn.shang.charging.billing.pojo.CalculationWindow;
import cn.shang.charging.charge.rules.compositetime.CompositePeriod;
import cn.shang.charging.charge.rules.compositetime.CompositeTimeConfig;
import cn.shang.charging.charge.rules.compositetime.CompositeTimeRule;
import cn.shang.charging.charge.rules.compositetime.CrossPeriodMode;
import cn.shang.charging.charge.rules.compositetime.NaturalPeriod;
import cn.shang.charging.promotion.pojo.PromotionAggregate;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompositeTimeSmokeTest {

    @Test
    void unitBasedBasicCalculationShouldStayStable() {
        CompositeTimeRule rule = new CompositeTimeRule();
        BillingSegmentResult result = rule.calculate(
                createContext(
                        LocalDateTime.of(2026, 1, 1, 8, 0),
                        LocalDateTime.of(2026, 1, 1, 10, 0),
                        BConstants.CalculationMode.UNIT_BASED
                ),
                createSinglePeriodConfig(CrossPeriodMode.BLOCK_WEIGHT, BigDecimal.ONE, BigDecimal.valueOf(50)),
                PromotionAggregate.builder().build()
        );

        assertEquals(0, BigDecimal.valueOf(2).compareTo(result.getChargedAmount()));
        assertEquals(2, result.getBillingUnits().size());
    }

    @Test
    void crossPeriodHigherPriceShouldStayStable() {
        CompositeTimeConfig config = CompositeTimeConfig.builder()
                .id("test")
                .maxChargeOneCycle(BigDecimal.valueOf(50))
                .periods(List.of(
                        CompositePeriod.builder()
                                .beginMinute(0)
                                .endMinute(1440)
                                .unitMinutes(60)
                                .crossPeriodMode(CrossPeriodMode.HIGHER_PRICE)
                                .naturalPeriods(List.of(
                                        NaturalPeriod.builder().beginMinute(0).endMinute(480).unitPrice(BigDecimal.ONE).build(),
                                        NaturalPeriod.builder().beginMinute(480).endMinute(1200).unitPrice(BigDecimal.valueOf(2)).build(),
                                        NaturalPeriod.builder().beginMinute(1200).endMinute(1440).unitPrice(BigDecimal.ONE).build()
                                ))
                                .build()
                ))
                .build();

        CompositeTimeRule rule = new CompositeTimeRule();
        BillingSegmentResult result = rule.calculate(
                createContext(
                        LocalDateTime.of(2026, 1, 1, 19, 30),
                        LocalDateTime.of(2026, 1, 1, 20, 30),
                        BConstants.CalculationMode.UNIT_BASED
                ),
                config,
                PromotionAggregate.builder().build()
        );

        assertEquals(0, BigDecimal.valueOf(2).compareTo(result.getChargedAmount()));
        assertEquals(1, result.getBillingUnits().size());
    }

    @Test
    void continuousCycleCapShouldStayStable() {
        CompositeTimeRule rule = new CompositeTimeRule();
        BillingSegmentResult result = rule.calculate(
                createContext(
                        LocalDateTime.of(2026, 1, 1, 8, 0),
                        LocalDateTime.of(2026, 1, 1, 12, 0),
                        BConstants.CalculationMode.CONTINUOUS
                ),
                createSinglePeriodConfig(CrossPeriodMode.BLOCK_WEIGHT, BigDecimal.ONE, BigDecimal.valueOf(3)),
                PromotionAggregate.builder().build()
        );

        assertEquals(0, BigDecimal.valueOf(3).compareTo(result.getChargedAmount()));
        BillingUnit lastUnit = result.getBillingUnits().get(result.getBillingUnits().size() - 1);
        assertTrue(lastUnit.isFree());
        assertEquals("CYCLE_CAP", lastUnit.getFreePromotionId());
    }

    private static CompositeTimeConfig createSinglePeriodConfig(CrossPeriodMode mode, BigDecimal unitPrice, BigDecimal maxChargeOneCycle) {
        return CompositeTimeConfig.builder()
                .id("test")
                .maxChargeOneCycle(maxChargeOneCycle)
                .periods(List.of(
                        CompositePeriod.builder()
                                .beginMinute(0)
                                .endMinute(1440)
                                .unitMinutes(60)
                                .crossPeriodMode(mode)
                                .naturalPeriods(List.of(
                                        NaturalPeriod.builder().beginMinute(0).endMinute(1440).unitPrice(unitPrice).build()
                                ))
                                .build()
                ))
                .build();
    }

    private static BillingContext createContext(LocalDateTime begin, LocalDateTime end, BConstants.CalculationMode mode) {
        CalculationWindow window = new CalculationWindow();
        window.setCalculationBegin(begin);
        window.setCalculationEnd(end);

        BillingSegment segment = BillingSegment.builder()
                .beginTime(begin)
                .build();

        return BillingContext.builder()
                .beginTime(begin)
                .endTime(end)
                .calculationMode(mode)
                .segment(segment)
                .window(window)
                .build();
    }
}
