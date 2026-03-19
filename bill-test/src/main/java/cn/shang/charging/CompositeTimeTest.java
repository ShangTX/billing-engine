package cn.shang.charging;

import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.BillingContext;
import cn.shang.charging.billing.pojo.BillingSegmentResult;
import cn.shang.charging.billing.pojo.CalculationWindow;
import cn.shang.charging.billing.BillingSegment;
import cn.shang.charging.charge.rules.compositetime.*;
import cn.shang.charging.promotion.pojo.PromotionAggregate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.Month;
import java.util.List;

/**
 * 混合时间计费测试
 *
 * 当前测试覆盖：
 * 1. 配置校验测试
 */
public class CompositeTimeTest {

    public static void main(String[] args) {
        System.out.println("========== 混合时间计费测试 ==========\n");

        // === 配置校验测试 ===
        testConfigValidation_NaturalPeriodNotFullCoverage();
        testConfigValidation_PeriodsNotContinuous();
        testConfigValidation_MaxChargeOneCycleRequired();

        System.out.println("========== 所有测试完成 ==========\n");
    }

    // ==================== 配置校验测试 ====================

    static void testConfigValidation_NaturalPeriodNotFullCoverage() {
        System.out.println("=== 测试: 配置校验 - 自然时段未覆盖全天 ===");
        try {
            CompositeTimeConfig config = createBaseConfig();
            config.getPeriods().get(0).setNaturalPeriods(List.of(
                    NaturalPeriod.builder().beginMinute(480).endMinute(1200).unitPrice(BigDecimal.ONE).build()
            ));

            CompositeTimeRule rule = new CompositeTimeRule();
            rule.calculate(createBaseContext(), config, PromotionAggregate.builder().build());

            System.out.println("失败: 应该抛出异常");
        } catch (IllegalArgumentException e) {
            System.out.println("通过: " + e.getMessage());
        }
        System.out.println();
    }

    static void testConfigValidation_PeriodsNotContinuous() {
        System.out.println("=== 测试: 配置校验 - 相对时间段不连续 ===");
        try {
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
            rule.calculate(createBaseContext(), config, PromotionAggregate.builder().build());

            System.out.println("失败: 应该抛出异常");
        } catch (IllegalArgumentException e) {
            System.out.println("通过: " + e.getMessage());
        }
        System.out.println();
    }

    static void testConfigValidation_MaxChargeOneCycleRequired() {
        System.out.println("=== 测试: 配置校验 - 封顶金额必填 ===");
        try {
            CompositeTimeConfig config = CompositeTimeConfig.builder()
                    .id("test")
                    .periods(createValidPeriods())
                    .build();

            CompositeTimeRule rule = new CompositeTimeRule();
            rule.calculate(createBaseContext(), config, PromotionAggregate.builder().build());

            System.out.println("失败: 应该抛出异常");
        } catch (IllegalArgumentException e) {
            System.out.println("通过: " + e.getMessage());
        }
        System.out.println();
    }

    // ==================== 辅助方法 ====================

    private static CompositeTimeConfig createBaseConfig() {
        return CompositeTimeConfig.builder()
                .id("test")
                .maxChargeOneCycle(BigDecimal.valueOf(50))
                .periods(createValidPeriods())
                .build();
    }

    private static List<CompositePeriod> createValidPeriods() {
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

    private static List<NaturalPeriod> createFullCoverageNaturalPeriods() {
        return List.of(
                NaturalPeriod.builder().beginMinute(0).endMinute(1440).unitPrice(BigDecimal.ONE).build()
        );
    }

    private static BillingContext createBaseContext() {
        CalculationWindow window = new CalculationWindow();
        window.setCalculationBegin(LocalDateTime.of(2026, Month.MARCH, 10, 8, 0));
        window.setCalculationEnd(LocalDateTime.of(2026, Month.MARCH, 10, 10, 0));

        BillingSegment segment = BillingSegment.builder()
                .beginTime(LocalDateTime.of(2026, 1, 1, 8, 0))
                .build();

        return BillingContext.builder()
                .beginTime(LocalDateTime.of(2026, 1, 1, 8, 0))
                .endTime(LocalDateTime.of(2026, 1, 1, 10, 0))
                .billingMode(BConstants.BillingMode.UNIT_BASED)
                .segment(segment)
                .window(window)
                .build();
    }
}