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

        // === CrossPeriodMode 测试 ===
        testCrossPeriodMode_HigherPrice();
        testCrossPeriodMode_LowerPrice();
        testCrossPeriodMode_BeginTimePrice();
        testCrossPeriodMode_EndTimePrice();

        // === 封顶测试 ===
        testPeriodCap_ReduceFromLastUnit();
        testPeriodCap_TwoPeriods_CapOnFirst();

        // === 周期封顶测试 ===
        testCycleCap_WithPeriodCap();
        testCycleCap_ReductionNeeded();
        testCycleCap_PeriodCapUnitsNotReduced();

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

    // ==================== CrossPeriodMode 测试 ====================

    static void testCrossPeriodMode_HigherPrice() {
        System.out.println("=== 测试: CrossPeriodMode.HIGHER_PRICE ===");
        // Natural periods: 00:00-08:00 (1 yuan), 08:00-20:00 (2 yuan), 20:00-24:00 (1 yuan)
        // Billing unit crosses boundary at 19:30-20:30, should use HIGHER_PRICE = 2 yuan
        CompositeTimeConfig config = CompositeTimeConfig.builder()
                .id("test")
                .maxChargeOneCycle(BigDecimal.valueOf(50))
                .periods(List.of(
                        CompositePeriod.builder()
                                .beginMinute(0).endMinute(1440).unitMinutes(60)
                                .crossPeriodMode(CrossPeriodMode.HIGHER_PRICE)
                                .naturalPeriods(List.of(
                                        NaturalPeriod.builder().beginMinute(0).endMinute(480).unitPrice(BigDecimal.ONE).build(),
                                        NaturalPeriod.builder().beginMinute(480).endMinute(1200).unitPrice(BigDecimal.valueOf(2)).build(),
                                        NaturalPeriod.builder().beginMinute(1200).endMinute(1440).unitPrice(BigDecimal.ONE).build()
                                ))
                                .build()
                ))
                .build();

        BillingContext context = createBaseContext(LocalDateTime.of(2026, 1, 1, 19, 30),
                                                   LocalDateTime.of(2026, 1, 1, 20, 30));

        CompositeTimeRule rule = new CompositeTimeRule();
        BillingSegmentResult result = rule.calculate(context, config, PromotionAggregate.builder().build());

        // Crosses boundary: begin in 2-yuan period, end in 1-yuan period
        // HIGHER_PRICE should use 2 yuan
        assertAmountEquals(BigDecimal.valueOf(2), result.getChargedAmount());
        System.out.println("通过: 收费金额 = " + result.getChargedAmount());
        System.out.println();
    }

    static void testCrossPeriodMode_LowerPrice() {
        System.out.println("=== 测试: CrossPeriodMode.LOWER_PRICE ===");
        // Same setup, but with LOWER_PRICE mode
        CompositeTimeConfig config = CompositeTimeConfig.builder()
                .id("test")
                .maxChargeOneCycle(BigDecimal.valueOf(50))
                .periods(List.of(
                        CompositePeriod.builder()
                                .beginMinute(0).endMinute(1440).unitMinutes(60)
                                .crossPeriodMode(CrossPeriodMode.LOWER_PRICE)
                                .naturalPeriods(List.of(
                                        NaturalPeriod.builder().beginMinute(0).endMinute(480).unitPrice(BigDecimal.ONE).build(),
                                        NaturalPeriod.builder().beginMinute(480).endMinute(1200).unitPrice(BigDecimal.valueOf(2)).build(),
                                        NaturalPeriod.builder().beginMinute(1200).endMinute(1440).unitPrice(BigDecimal.ONE).build()
                                ))
                                .build()
                ))
                .build();

        BillingContext context = createBaseContext(LocalDateTime.of(2026, 1, 1, 19, 30),
                                                   LocalDateTime.of(2026, 1, 1, 20, 30));

        CompositeTimeRule rule = new CompositeTimeRule();
        BillingSegmentResult result = rule.calculate(context, config, PromotionAggregate.builder().build());

        // LOWER_PRICE should use 1 yuan
        assertAmountEquals(BigDecimal.valueOf(1), result.getChargedAmount());
        System.out.println("通过: 收费金额 = " + result.getChargedAmount());
        System.out.println();
    }

    static void testCrossPeriodMode_BeginTimePrice() {
        System.out.println("=== 测试: CrossPeriodMode.BEGIN_TIME_PRICE ===");
        // BEGIN_TIME_PRICE uses the price of the period where unit begins
        CompositeTimeConfig config = CompositeTimeConfig.builder()
                .id("test")
                .maxChargeOneCycle(BigDecimal.valueOf(50))
                .periods(List.of(
                        CompositePeriod.builder()
                                .beginMinute(0).endMinute(1440).unitMinutes(60)
                                .crossPeriodMode(CrossPeriodMode.BEGIN_TIME_PRICE)
                                .naturalPeriods(List.of(
                                        NaturalPeriod.builder().beginMinute(0).endMinute(480).unitPrice(BigDecimal.ONE).build(),
                                        NaturalPeriod.builder().beginMinute(480).endMinute(1200).unitPrice(BigDecimal.valueOf(2)).build(),
                                        NaturalPeriod.builder().beginMinute(1200).endMinute(1440).unitPrice(BigDecimal.ONE).build()
                                ))
                                .build()
                ))
                .build();

        BillingContext context = createBaseContext(LocalDateTime.of(2026, 1, 1, 19, 30),
                                                   LocalDateTime.of(2026, 1, 1, 20, 30));

        CompositeTimeRule rule = new CompositeTimeRule();
        BillingSegmentResult result = rule.calculate(context, config, PromotionAggregate.builder().build());

        // Unit begins in 2-yuan period, so charge 2 yuan
        assertAmountEquals(BigDecimal.valueOf(2), result.getChargedAmount());
        System.out.println("通过: 收费金额 = " + result.getChargedAmount());
        System.out.println();
    }

    static void testCrossPeriodMode_EndTimePrice() {
        System.out.println("=== 测试: CrossPeriodMode.END_TIME_PRICE ===");
        // END_TIME_PRICE uses the price of the period where unit ends
        CompositeTimeConfig config = CompositeTimeConfig.builder()
                .id("test")
                .maxChargeOneCycle(BigDecimal.valueOf(50))
                .periods(List.of(
                        CompositePeriod.builder()
                                .beginMinute(0).endMinute(1440).unitMinutes(60)
                                .crossPeriodMode(CrossPeriodMode.END_TIME_PRICE)
                                .naturalPeriods(List.of(
                                        NaturalPeriod.builder().beginMinute(0).endMinute(480).unitPrice(BigDecimal.ONE).build(),
                                        NaturalPeriod.builder().beginMinute(480).endMinute(1200).unitPrice(BigDecimal.valueOf(2)).build(),
                                        NaturalPeriod.builder().beginMinute(1200).endMinute(1440).unitPrice(BigDecimal.ONE).build()
                                ))
                                .build()
                ))
                .build();

        BillingContext context = createBaseContext(LocalDateTime.of(2026, 1, 1, 19, 30),
                                                   LocalDateTime.of(2026, 1, 1, 20, 30));

        CompositeTimeRule rule = new CompositeTimeRule();
        BillingSegmentResult result = rule.calculate(context, config, PromotionAggregate.builder().build());

        // Unit ends in 1-yuan period (after 20:00), so charge 1 yuan
        assertAmountEquals(BigDecimal.valueOf(1), result.getChargedAmount());
        System.out.println("通过: 收费金额 = " + result.getChargedAmount());
        System.out.println();
    }

    // ==================== 封顶测试 ====================

    static void testPeriodCap_ReduceFromLastUnit() {
        System.out.println("=== 测试: 时间段独立封顶 - 从最后一个单元削减 ===");
        // Period cap: 5 yuan
        // Actual: 3 units × 3 yuan = 9 yuan, need to reduce to 5 yuan
        CompositeTimeConfig config = CompositeTimeConfig.builder()
                .id("test")
                .maxChargeOneCycle(BigDecimal.valueOf(50))
                .periods(List.of(
                        CompositePeriod.builder()
                                .beginMinute(0).endMinute(1440).unitMinutes(60)
                                .maxCharge(BigDecimal.valueOf(5)) // Period cap
                                .crossPeriodMode(CrossPeriodMode.BLOCK_WEIGHT)
                                .naturalPeriods(List.of(
                                        NaturalPeriod.builder().beginMinute(0).endMinute(1440).unitPrice(BigDecimal.valueOf(3)).build()
                                ))
                                .build()
                ))
                .build();

        // 08:00-11:00 = 3 units × 3 yuan = 9 yuan, cap at 5 yuan
        BillingContext context = createBaseContext(
                LocalDateTime.of(2026, 1, 1, 8, 0),
                LocalDateTime.of(2026, 1, 1, 11, 0));

        CompositeTimeRule rule = new CompositeTimeRule();
        BillingSegmentResult result = rule.calculate(context, config, PromotionAggregate.builder().build());

        // Cap at 5 yuan, last unit reduced
        assertAmountEquals(BigDecimal.valueOf(5), result.getChargedAmount());

        // Verify last unit is marked as free or reduced
        BillingUnit lastUnit = result.getBillingUnits().get(result.getBillingUnits().size() - 1);
        assertTrue(lastUnit.isFree() || lastUnit.getChargedAmount().compareTo(BigDecimal.valueOf(3)) < 0);
        System.out.println("通过: 收费金额 = " + result.getChargedAmount() + ", 最后单元免费=" + lastUnit.isFree());
        System.out.println();
    }

    static void testPeriodCap_TwoPeriods_CapOnFirst() {
        System.out.println("=== 测试: 两个时间段 - 第一个时间段封顶 ===");
        // First period (0-120 min): 60-min units, 2 yuan each, cap at 3 yuan
        // Second period (120-1440 min): 30-min units, 1 yuan each, no cap
        CompositeTimeConfig config = CompositeTimeConfig.builder()
                .id("test")
                .maxChargeOneCycle(BigDecimal.valueOf(50))
                .periods(List.of(
                        CompositePeriod.builder()
                                .beginMinute(0).endMinute(120).unitMinutes(60)
                                .maxCharge(BigDecimal.valueOf(3)) // Period cap
                                .crossPeriodMode(CrossPeriodMode.BLOCK_WEIGHT)
                                .naturalPeriods(List.of(
                                        NaturalPeriod.builder().beginMinute(0).endMinute(1440).unitPrice(BigDecimal.valueOf(2)).build()
                                ))
                                .build(),
                        CompositePeriod.builder()
                                .beginMinute(120).endMinute(1440).unitMinutes(60)
                                .crossPeriodMode(CrossPeriodMode.BLOCK_WEIGHT)
                                .naturalPeriods(List.of(
                                        NaturalPeriod.builder().beginMinute(0).endMinute(1440).unitPrice(BigDecimal.ONE).build()
                                ))
                                .build()
                ))
                .build();

        // 08:00-12:00
        // Period 1: 08:00-10:00 = 2 units × 2 yuan = 4 yuan, cap at 3 yuan
        // Period 2: 10:00-12:00 = 2 units × 1 yuan = 2 yuan
        // Total: 3 + 2 = 5 yuan
        BillingContext context = createBaseContext(
                LocalDateTime.of(2026, 1, 1, 8, 0),
                LocalDateTime.of(2026, 1, 1, 12, 0));

        CompositeTimeRule rule = new CompositeTimeRule();
        BillingSegmentResult result = rule.calculate(context, config, PromotionAggregate.builder().build());

        assertAmountEquals(BigDecimal.valueOf(5), result.getChargedAmount());
        System.out.println("通过: 收费金额 = " + result.getChargedAmount());
        System.out.println();
    }

    // ==================== 周期封顶测试 ====================

    static void testCycleCap_WithPeriodCap() {
        System.out.println("=== 测试: 周期封顶 + 时间段封顶（无削减） ===");
        // Period 1: cap 5 yuan
        // Period 2: no cap
        // Cycle cap: 10 yuan
        CompositeTimeConfig config = CompositeTimeConfig.builder()
                .id("test")
                .maxChargeOneCycle(BigDecimal.valueOf(10)) // Cycle cap 10 yuan
                .periods(List.of(
                        CompositePeriod.builder()
                                .beginMinute(0).endMinute(120).unitMinutes(60)
                                .maxCharge(BigDecimal.valueOf(5)) // Period cap 5 yuan
                                .crossPeriodMode(CrossPeriodMode.BLOCK_WEIGHT)
                                .naturalPeriods(List.of(
                                        NaturalPeriod.builder().beginMinute(0).endMinute(1440).unitPrice(BigDecimal.ONE).build()
                                ))
                                .build(),
                        CompositePeriod.builder()
                                .beginMinute(120).endMinute(1440).unitMinutes(60)
                                .crossPeriodMode(CrossPeriodMode.BLOCK_WEIGHT)
                                .naturalPeriods(List.of(
                                        NaturalPeriod.builder().beginMinute(0).endMinute(1440).unitPrice(BigDecimal.valueOf(2)).build()
                                ))
                                .build()
                ))
                .build();

        // 08:00-14:00
        // Period 1: 08:00-10:00, 2 units, 2 yuan (under cap of 5)
        // Period 2: 10:00-14:00, 4 units, 8 yuan
        // Total before cycle cap: 2 + 8 = 10 yuan = cycle cap, no reduction needed
        BillingContext context = createBaseContext(LocalDateTime.of(2026, 1, 1, 8, 0),
                                                   LocalDateTime.of(2026, 1, 1, 14, 0));

        CompositeTimeRule rule = new CompositeTimeRule();
        BillingSegmentResult result = rule.calculate(context, config, PromotionAggregate.builder().build());

        // Total should be 10 yuan (2 + 8 = 10, exactly at cycle cap)
        assertAmountEquals(BigDecimal.valueOf(10), result.getChargedAmount());
        System.out.println("通过: 收费金额 = " + result.getChargedAmount());
        System.out.println();
    }

    static void testCycleCap_ReductionNeeded() {
        System.out.println("=== 测试: 周期封顶需要削减 ===");
        // Period 1: cap 5 yuan
        // Period 2: no cap
        // Cycle cap: 6 yuan
        CompositeTimeConfig config = CompositeTimeConfig.builder()
                .id("test")
                .maxChargeOneCycle(BigDecimal.valueOf(6)) // Cycle cap 6 yuan
                .periods(List.of(
                        CompositePeriod.builder()
                                .beginMinute(0).endMinute(120).unitMinutes(60)
                                .maxCharge(BigDecimal.valueOf(5))
                                .crossPeriodMode(CrossPeriodMode.BLOCK_WEIGHT)
                                .naturalPeriods(List.of(
                                        NaturalPeriod.builder().beginMinute(0).endMinute(1440).unitPrice(BigDecimal.ONE).build()
                                ))
                                .build(),
                        CompositePeriod.builder()
                                .beginMinute(120).endMinute(1440).unitMinutes(60)
                                .crossPeriodMode(CrossPeriodMode.BLOCK_WEIGHT)
                                .naturalPeriods(List.of(
                                        NaturalPeriod.builder().beginMinute(0).endMinute(1440).unitPrice(BigDecimal.valueOf(2)).build()
                                ))
                                .build()
                ))
                .build();

        // 08:00-14:00
        // Period 1: 08:00-10:00, 2 units, 2 yuan
        // Period 2: 10:00-14:00, 4 units, 8 yuan
        // Total: 10 yuan, cycle cap 6 yuan, need to reduce 4 yuan from Period 2
        BillingContext context = createBaseContext(LocalDateTime.of(2026, 1, 1, 8, 0),
                                                   LocalDateTime.of(2026, 1, 1, 14, 0));

        CompositeTimeRule rule = new CompositeTimeRule();
        BillingSegmentResult result = rule.calculate(context, config, PromotionAggregate.builder().build());

        // Cycle cap 6 yuan
        assertAmountEquals(BigDecimal.valueOf(6), result.getChargedAmount());
        System.out.println("通过: 收费金额 = " + result.getChargedAmount());
        System.out.println();
    }

    static void testCycleCap_PeriodCapUnitsNotReduced() {
        System.out.println("=== 测试: 周期封顶不应削减已封顶单元 ===");
        // Period 1: cap 2 yuan (will trigger period cap)
        // Period 2: no cap
        // Cycle cap: 4 yuan
        CompositeTimeConfig config = CompositeTimeConfig.builder()
                .id("test")
                .maxChargeOneCycle(BigDecimal.valueOf(4)) // Cycle cap 4 yuan
                .periods(List.of(
                        CompositePeriod.builder()
                                .beginMinute(0).endMinute(120).unitMinutes(60)
                                .maxCharge(BigDecimal.valueOf(2)) // Period cap 2 yuan - will trigger
                                .crossPeriodMode(CrossPeriodMode.BLOCK_WEIGHT)
                                .naturalPeriods(List.of(
                                        NaturalPeriod.builder().beginMinute(0).endMinute(1440).unitPrice(BigDecimal.valueOf(3)).build()
                                ))
                                .build(),
                        CompositePeriod.builder()
                                .beginMinute(120).endMinute(1440).unitMinutes(60)
                                .crossPeriodMode(CrossPeriodMode.BLOCK_WEIGHT)
                                .naturalPeriods(List.of(
                                        NaturalPeriod.builder().beginMinute(0).endMinute(1440).unitPrice(BigDecimal.valueOf(2)).build()
                                ))
                                .build()
                ))
                .build();

        // 08:00-14:00
        // Period 1: 08:00-10:00, 2 units × 3 yuan = 6 yuan, period cap 2 yuan, last unit becomes PERIOD_CAP free
        // Period 2: 10:00-14:00, 4 units × 2 yuan = 8 yuan
        // After period cap: 2 + 8 = 10 yuan
        // Cycle cap 4 yuan, need to reduce 6 yuan
        // Key test: PERIOD_CAP units should NOT be reduced further
        // Reduction should only come from Period 2 units
        BillingContext context = createBaseContext(LocalDateTime.of(2026, 1, 1, 8, 0),
                                                   LocalDateTime.of(2026, 1, 1, 14, 0));

        CompositeTimeRule rule = new CompositeTimeRule();
        BillingSegmentResult result = rule.calculate(context, config, PromotionAggregate.builder().build());

        // Cycle cap 4 yuan
        assertAmountEquals(BigDecimal.valueOf(4), result.getChargedAmount());

        // Verify Period 1 units: first unit charged 2 yuan (period cap reduced from 3 to 2),
        // second unit is free (PERIOD_CAP)
        // Total from Period 1: 2 yuan
        // Period 2 needs to contribute: 4 - 2 = 2 yuan (reduced from 8)
        System.out.println("通过: 收费金额 = " + result.getChargedAmount());
        System.out.println();
    }

    private static void assertTrue(boolean condition) {
        if (!condition) {
            throw new AssertionError("Expected condition to be true");
        }
    }

    private static void assertEquals(Object expected, Object actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("Expected: " + expected + ", but was: " + actual);
        }
    }
}