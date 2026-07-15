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
import cn.shang.charging.charge.rules.compositetime.NaturalPeriod;
import cn.shang.charging.promotion.pojo.PromotionAggregate;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 CompositeTime CONTINUOUS 路径的时段独立封顶（period.getMaxCharge）。
 * 时段封顶原只在 UNIT_BASED 路径实现，TODO-20260630-001 将其补齐到 CONTINUOUS。
 */
class CompositeTimePeriodCapTest {

    @Test
    void continuous_periodCap_reduceFromLastUnit() {
        // 3元/小时，时段封顶5元，08:00-11:00（3小时）= 9元 → 封顶5元
        CompositeTimeConfig config = CompositeTimeConfig.builder()
                .id("period-cap-test")
                .maxChargeOneCycle(BigDecimal.valueOf(50))
                .periods(List.of(
                        CompositePeriod.builder()
                                .beginMinute(0).endMinute(1440).unitMinutes(60)
                                .maxCharge(BigDecimal.valueOf(5))
                                .naturalPeriods(List.of(
                                        NaturalPeriod.builder().beginMinute(0).endMinute(1440)
                                                .unitPrice(BigDecimal.valueOf(3)).build()
                                ))
                                .build()
                ))
                .build();

        CompositeTimeRule rule = new CompositeTimeRule();
        BillingSegmentResult result = rule.calculate(
                createContext(LocalDateTime.of(2026, 1, 1, 8, 0),
                        LocalDateTime.of(2026, 1, 1, 11, 0),
                        BConstants.CalculationMode.CONTINUOUS),
                config,
                PromotionAggregate.builder().build());

        assertEquals(0, BigDecimal.valueOf(5).compareTo(result.getChargedAmount()),
                "时段封顶后应收 5 元，实际=" + result.getChargedAmount());

        // 最后单元应被削减（免费或 chargedAmount < 单价）
        BillingUnit lastUnit = result.getBillingUnits().get(result.getBillingUnits().size() - 1);
        assertTrue(lastUnit.isFree() || lastUnit.getChargedAmount().compareTo(BigDecimal.valueOf(3)) < 0,
                "最后单元应被 PERIOD_CAP 削减");    }

    @Test
    void continuous_periodCap_twoPeriods_capOnFirst() {
        // 时段1（0-120min）：2元/单元，封顶3元
        // 时段2（120-1440min）：1元/单元，无封顶
        // 08:00-12:00（4小时）：时段1 = 2单元×2=4元→封顶3元；时段2 = 2单元×1=2元；合计5元
        CompositeTimeConfig config = CompositeTimeConfig.builder()
                .id("two-period-cap")
                .maxChargeOneCycle(BigDecimal.valueOf(50))
                .periods(List.of(
                        CompositePeriod.builder()
                                .beginMinute(0).endMinute(120).unitMinutes(60)
                                .maxCharge(BigDecimal.valueOf(3))
                                .naturalPeriods(List.of(
                                        NaturalPeriod.builder().beginMinute(0).endMinute(1440)
                                                .unitPrice(BigDecimal.valueOf(2)).build()
                                ))
                                .build(),
                        CompositePeriod.builder()
                                .beginMinute(120).endMinute(1440).unitMinutes(60)
                                .naturalPeriods(List.of(
                                        NaturalPeriod.builder().beginMinute(0).endMinute(1440)
                                                .unitPrice(BigDecimal.valueOf(1)).build()
                                ))
                                .build()
                ))
                .build();

        CompositeTimeRule rule = new CompositeTimeRule();
        BillingSegmentResult result = rule.calculate(
                createContext(LocalDateTime.of(2026, 1, 1, 8, 0),
                        LocalDateTime.of(2026, 1, 1, 12, 0),
                        BConstants.CalculationMode.CONTINUOUS),
                config,
                PromotionAggregate.builder().build());

        assertEquals(0, BigDecimal.valueOf(5).compareTo(result.getChargedAmount()),
                "两时段：第一时段封顶3元 + 第二时段2元 = 5元");
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
