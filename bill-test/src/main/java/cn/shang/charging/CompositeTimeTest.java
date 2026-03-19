package cn.shang.charging;

import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.BillingContext;
import cn.shang.charging.billing.pojo.BillingSegmentResult;
import cn.shang.charging.billing.pojo.BillingUnit;
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

        // === UNIT_BASED 模式测试 ===
        testUnitBased_BasicCalculation();
        testUnitBased_TwoRelativePeriods();

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

    private static BillingContext createBaseContext(LocalDateTime begin, LocalDateTime end) {
        CalculationWindow window = new CalculationWindow();
        window.setCalculationBegin(begin);
        window.setCalculationEnd(end);

        BillingSegment segment = BillingSegment.builder()
                .beginTime(begin)
                .build();

        return BillingContext.builder()
                .beginTime(begin)
                .endTime(end)
                .billingMode(BConstants.BillingMode.UNIT_BASED)
                .segment(segment)
                .window(window)
                .build();
    }

    private static void assertAmountEquals(BigDecimal expected, BigDecimal actual) {
        if (expected.compareTo(actual) != 0) {
            throw new AssertionError("Expected: " + expected + ", but was: " + actual);
        }
    }

    // ==================== UNIT_BASED 模式测试 ====================

    static void testUnitBased_BasicCalculation() {
        System.out.println("=== 测试: UNIT_BASED 基本计算 ===");
        CompositeTimeConfig config = createBaseConfig();

        CompositeTimeRule rule = new CompositeTimeRule();
        BillingSegmentResult result = rule.calculate(createBaseContext(), config, PromotionAggregate.builder().build());

        // 08:00-10:00 = 2 units, each 1 yuan = 2 yuan total
        assertAmountEquals(BigDecimal.valueOf(2), result.getChargedAmount());
        assertEquals(2, result.getBillingUnits().size());
        System.out.println("通过: 收费金额 = " + result.getChargedAmount() + ", 单元数 = " + result.getBillingUnits().size());
        System.out.println();
    }

    static void testUnitBased_TwoRelativePeriods() {
        System.out.println("=== 测试: UNIT_BASED 两个相对时间段 ===");
        CompositeTimeConfig config = CompositeTimeConfig.builder()
                .id("test")
                .maxChargeOneCycle(BigDecimal.valueOf(50))
                .periods(List.of(
                        CompositePeriod.builder()
                                .beginMinute(0).endMinute(120).unitMinutes(60)
                                .crossPeriodMode(CrossPeriodMode.BLOCK_WEIGHT)
                                .naturalPeriods(List.of(NaturalPeriod.builder()
                                        .beginMinute(0).endMinute(1440).unitPrice(BigDecimal.ONE).build()))
                                .build(),
                        CompositePeriod.builder()
                                .beginMinute(120).endMinute(1440).unitMinutes(30)
                                .crossPeriodMode(CrossPeriodMode.BLOCK_WEIGHT)
                                .naturalPeriods(List.of(NaturalPeriod.builder()
                                        .beginMinute(0).endMinute(1440).unitPrice(BigDecimal.valueOf(2)).build()))
                                .build()
                ))
                .build();

        BillingContext context = createBaseContext(
                LocalDateTime.of(2026, 1, 1, 8, 0),
                LocalDateTime.of(2026, 1, 1, 11, 0)
        );

        CompositeTimeRule rule = new CompositeTimeRule();
        BillingSegmentResult result = rule.calculate(context, config, PromotionAggregate.builder().build());

        // Period 1: 08:00-10:00 = 2 units × 1 yuan = 2 yuan
        // Period 2: 10:00-11:00 = 2 units × 2 yuan = 4 yuan
        // Total: 6 yuan
        assertAmountEquals(BigDecimal.valueOf(6), result.getChargedAmount());
        System.out.println("通过: 收费金额 = " + result.getChargedAmount());
        System.out.println();
    }

    private static void assertEquals(Object expected, Object actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("Expected: " + expected + ", but was: " + actual);
        }
    }
}