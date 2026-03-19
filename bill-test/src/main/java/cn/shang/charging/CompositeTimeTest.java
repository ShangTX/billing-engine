package cn.shang.charging;

import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.BillingContext;
import cn.shang.charging.billing.pojo.BillingSegmentResult;
import cn.shang.charging.billing.pojo.BillingSegment;
import cn.shang.charging.charge.rules.compositetime.*;
import cn.shang.charging.promotion.pojo.PromotionAggregate;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class CompositeTimeTest {

    // ========== 配置校验测试 ==========

    @Test
    void testConfigValidation_NaturalPeriodNotFullCoverage() {
        CompositeTimeConfig config = createBaseConfig();
        config.getPeriods().get(0).setNaturalPeriods(List.of(
                NaturalPeriod.builder().beginMinute(480).endMinute(1200).unitPrice(BigDecimal.ONE).build()
        ));

        CompositeTimeRule rule = new CompositeTimeRule();
        assertThrows(IllegalArgumentException.class, () ->
                rule.calculate(createBaseContext(), config, PromotionAggregate.empty())
        );
    }

    @Test
    void testConfigValidation_PeriodsNotContinuous() {
        CompositeTimeConfig config = CompositeTimeConfig.builder()
                .id("test")
                .maxChargeOneCycle(BigDecimal.valueOf(50))
                .periods(List.of(
                        CompositePeriod.builder()
                                .beginMinute(0)
                                .endMinute(60)
                                .unitMinutes(60)
                                .crossPeriodMode(CrossPeriodMode.BLOCK_WEIGHT)
                                .naturalPeriods(createFullCoverageNaturalPeriods())
                                .build()
                ))
                .build();

        CompositeTimeRule rule = new CompositeTimeRule();
        assertThrows(IllegalArgumentException.class, () ->
                rule.calculate(createBaseContext(), config, PromotionAggregate.empty())
        );
    }

    @Test
    void testConfigValidation_MaxChargeOneCycleRequired() {
        CompositeTimeConfig config = CompositeTimeConfig.builder()
                .id("test")
                .periods(createValidPeriods())
                .build();

        CompositeTimeRule rule = new CompositeTimeRule();
        assertThrows(IllegalArgumentException.class, () ->
                rule.calculate(createBaseContext(), config, PromotionAggregate.empty())
        );
    }

    // ========== 辅助方法 ==========

    private CompositeTimeConfig createBaseConfig() {
        return CompositeTimeConfig.builder()
                .id("test")
                .maxChargeOneCycle(BigDecimal.valueOf(50))
                .periods(createValidPeriods())
                .build();
    }

    private List<CompositePeriod> createValidPeriods() {
        return List.of(
                CompositePeriod.builder()
                        .beginMinute(0)
                        .endMinute(1440)
                        .unitMinutes(60)
                        .crossPeriodMode(CrossPeriodMode.BLOCK_WEIGHT)
                        .naturalPeriods(createFullCoverageNaturalPeriods())
                        .build()
        );
    }

    private List<NaturalPeriod> createFullCoverageNaturalPeriods() {
        return List.of(
                NaturalPeriod.builder().beginMinute(0).endMinute(1440).unitPrice(BigDecimal.ONE).build()
        );
    }

    private BillingContext createBaseContext() {
        BillingSegment segment = BillingSegment.builder()
                .billingBeginTime(LocalDateTime.of(2026, 1, 1, 8, 0))
                .build();
        return BillingContext.builder()
                .beginTime(LocalDateTime.of(2026, 1, 1, 8, 0))
                .endTime(LocalDateTime.of(2026, 1, 1, 10, 0))
                .billingMode(BConstants.BillingMode.UNIT_BASED)
                .segment(segment)
                .build();
    }
}