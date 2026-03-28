package cn.shang.charging;

import cn.shang.charging.billing.*;
import cn.shang.charging.billing.pojo.*;
import cn.shang.charging.charge.rules.BillingRuleRegistry;
import cn.shang.charging.charge.rules.compositetime.*;
import cn.shang.charging.promotion.FreeMinuteAllocator;
import cn.shang.charging.promotion.FreeTimeRangeMerger;
import cn.shang.charging.promotion.PromotionEngine;
import cn.shang.charging.promotion.pojo.PromotionGrant;
import cn.shang.charging.promotion.rules.PromotionRuleRegistry;
import cn.shang.charging.promotion.rules.minutes.FreeMinutesPromotionRule;
import cn.shang.charging.settlement.ResultAssembler;
import cn.shang.charging.wrapper.BillingTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.Month;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Billing API 测试 - 使用 BillingTemplate 进行测试
 * <p>
 * 本测试类验证 billing-api 模块中 BillingTemplate 的正确性。
 * BillingTemplate 是对核心计费引擎的便捷封装，提供了更简洁的 API 调用方式。
 * <p>
 * 测试场景与 CompositeTimeTest 一致，涵盖：
 * <ul>
 *   <li>配置校验：验证规则配置的合法性检查</li>
 *   <li>UNIT_BASED 模式：按固定单元计费，单元边界固定</li>
 *   <li>CONTINUOUS 模式：连续计费，时间可被免费时段打断</li>
 *   <li>跨时段定价：计费单元跨越不同单价时段的处理策略</li>
 *   <li>封顶机制：时间段封顶、周期封顶及其组合</li>
 *   <li>优惠处理：免费时段（气泡模型）在 CONTINUOUS 模式下的处理</li>
 * </ul>
 * <p>
 * 计费模式说明：
 * <ul>
 *   <li>UNIT_BASED：时间被切分为固定单位，每个单位独立计算，边界固定</li>
 *   <li>CONTINUOUS：连续时间模式，时间可被免费时段打断，形成多个计费片段</li>
 * </ul>
 *
 * @see cn.shang.charging.wrapper.BillingTemplate
 * @see cn.shang.charging.charge.rules.compositetime.CompositeTimeRule
 */
public class BillingApiTest {

    public static void main(String[] args) {
        System.out.println("========== Billing API 测试（BillingTemplate）==========\n");

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

        // === CONTINUOUS 模式测试 ===
        testContinuous_BasicCalculation();
        testContinuous_BubbleExtraction();
        testContinuous_BubbleExtraction_WithFreeRange();
        testContinuous_CycleCap();
        testContinuous_TwoPeriods();
        testContinuous_FreeRangeInMiddle();

        System.out.println("========== 所有测试完成 ==========\n");
    }

    // ==================== 配置校验测试 ====================

    /**
     * 测试：自然时段必须覆盖全天 0-1440 分钟
     * <p>
     * 场景：配置的自然时段只覆盖 08:00-20:00（480-1200分钟），未覆盖全天
     * 预期：抛出 IllegalArgumentException，提示"自然时段必须覆盖全天"
     */
    static void testConfigValidation_NaturalPeriodNotFullCoverage() {
        System.out.println("=== 测试: 配置校验 - 自然时段未覆盖全天 ===");
        System.out.println("场景: 配置的自然时段只覆盖 08:00-20:00，未覆盖 00:00-08:00 和 20:00-24:00");
        System.out.println("预期: 抛出 IllegalArgumentException");

        try {
            // 构建一个只覆盖部分时间的自然时段配置
            CompositeTimeConfig config = createBaseConfig();
            config.getPeriods().get(0).setNaturalPeriods(List.of(
                    NaturalPeriod.builder().beginMinute(480).endMinute(1200).unitPrice(BigDecimal.ONE).build()
            ));

            BillingTemplate template = createBillingTemplate(config, BConstants.BillingMode.UNIT_BASED);
            BillingRequest request = createBaseRequest(LocalDateTime.of(2026, Month.MARCH, 10, 8, 0),
                    LocalDateTime.of(2026, Month.MARCH, 10, 10, 0));
            template.calculate(request);

            System.out.println("结果: 失败 - 应该抛出异常但未抛出");
        } catch (IllegalArgumentException e) {
            System.out.println("结果: 通过 - " + e.getMessage());
        }
        System.out.println();
    }

    /**
     * 测试：相对时间段（CompositePeriod）必须连续覆盖 0-1440 分钟
     * <p>
     * 场景：只配置了一个相对时间段 0-60 分钟，后续时间无配置
     * 预期：抛出 IllegalArgumentException，提示"相对时间段必须连续覆盖全天"
     */
    static void testConfigValidation_PeriodsNotContinuous() {
        System.out.println("=== 测试: 配置校验 - 相对时间段不连续 ===");
        System.out.println("场景: 只配置了 0-60 分钟的相对时间段，缺少 60-1440 分钟的配置");
        System.out.println("预期: 抛出 IllegalArgumentException");

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

            BillingTemplate template = createBillingTemplate(config, BConstants.BillingMode.UNIT_BASED);
            BillingRequest request = createBaseRequest(LocalDateTime.of(2026, 1, 1, 8, 0),
                    LocalDateTime.of(2026, 1, 1, 10, 0));
            template.calculate(request);

            System.out.println("结果: 失败 - 应该抛出异常但未抛出");
        } catch (IllegalArgumentException e) {
            System.out.println("结果: 通过 - " + e.getMessage());
        }
        System.out.println();
    }

    /**
     * 测试：周期封顶金额（maxChargeOneCycle）必填
     * <p>
     * 场景：配置中未设置 maxChargeOneCycle
     * 预期：抛出 IllegalArgumentException，提示"周期封顶金额必填"
     */
    static void testConfigValidation_MaxChargeOneCycleRequired() {
        System.out.println("=== 测试: 配置校验 - 封顶金额必填 ===");
        System.out.println("场景: 配置中未设置 maxChargeOneCycle 字段");
        System.out.println("预期: 抛出 IllegalArgumentException");

        try {
            CompositeTimeConfig config = CompositeTimeConfig.builder()
                    .id("test")
                    .periods(createValidPeriods())
                    .build();

            BillingTemplate template = createBillingTemplate(config, BConstants.BillingMode.UNIT_BASED);
            BillingRequest request = createBaseRequest(LocalDateTime.of(2026, 1, 1, 8, 0),
                    LocalDateTime.of(2026, 1, 1, 10, 0));
            template.calculate(request);

            System.out.println("结果: 失败 - 应该抛出异常但未抛出");
        } catch (IllegalArgumentException e) {
            System.out.println("结果: 通过 - " + e.getMessage());
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

    private static BillingRequest createBaseRequest(LocalDateTime begin, LocalDateTime end) {
        BillingRequest request = new BillingRequest();
        request.setId("test-" + System.currentTimeMillis());
        request.setBeginTime(begin);
        request.setEndTime(end);
        request.setSchemeChanges(List.of());
        request.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        request.setSchemeId("scheme-default");
        request.setExternalPromotions(new ArrayList<>());
        return request;
    }

    private static BillingRequest createBaseRequestWithPromos(LocalDateTime begin, LocalDateTime end, List<PromotionGrant> promos) {
        BillingRequest request = createBaseRequest(begin, end);
        request.setExternalPromotions(promos);
        return request;
    }

    private static void assertAmountEquals(BigDecimal expected, BigDecimal actual) {
        if (expected.compareTo(actual) != 0) {
            throw new AssertionError("Expected: " + expected + ", but was: " + actual);
        }
    }

    private static void printBillingUnits(List<BillingUnit> units) {
        System.out.println("计费单元详情:");
        System.out.println("-------------------------------------------------------------------------");
        System.out.printf("%-3s | %-10s -> %-10s | %4s | %6s | %8s | %-8s | %s%n",
                "#", "开始", "结束", "时长", "单价", "原始金额", "收费金额", "备注");
        System.out.println("-------------------------------------------------------------------------");
        for (int i = 0; i < units.size(); i++) {
            BillingUnit unit = units.get(i);
            String begin = unit.getBeginTime().toLocalTime().toString();
            String end = unit.getEndTime().toLocalTime().toString();
            String note = unit.isFree() ? "免费(" + unit.getFreePromotionId() + ")" : "";
            System.out.printf("%-3d | %-10s -> %-10s | %4d | %6.2f | %8.2f | %-8s | %s%n",
                    i + 1,
                    begin,
                    end,
                    unit.getDurationMinutes(),
                    unit.getUnitPrice(),
                    unit.getOriginalAmount(),
                    unit.isFree() ? "免费" : String.format("%.2f", unit.getChargedAmount()),
                    note);
        }
        System.out.println("-------------------------------------------------------------------------");
    }

    // ==================== BillingTemplate 创建方法 ====================

    private static BillingTemplate createBillingTemplate(CompositeTimeConfig config, BConstants.BillingMode mode) {
        BillingConfigResolver resolver = new BillingConfigResolver() {
            @Override
            public BConstants.BillingMode resolveBillingMode(String schemeId, Map<String, Object> context) {
                return mode;
            }

            @Override
            public RuleConfig resolveChargingRule(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd, Map<String, Object> context) {
                return config;
            }

            @Override
            public List<PromotionRuleConfig> resolvePromotionRules(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd, Map<String, Object> context) {
                return new ArrayList<>();
            }
        };

        var ruleRegistry = new BillingRuleRegistry();
        ruleRegistry.register(BConstants.ChargeRuleType.COMPOSITE_TIME, new CompositeTimeRule());

        var promotionRegistry = new PromotionRuleRegistry();
        promotionRegistry.register(BConstants.PromotionRuleType.FREE_MINUTES, new FreeMinutesPromotionRule());

        var promotionEngine = new PromotionEngine(
                resolver,
                new FreeTimeRangeMerger(),
                new FreeMinuteAllocator(),
                promotionRegistry
        );

        BillingService billingService = new BillingService(
                new SegmentBuilder(),
                resolver,
                promotionEngine,
                new BillingCalculator(ruleRegistry),
                new ResultAssembler()
        );

        return new BillingTemplate(billingService, resolver);
    }

    private static BillingTemplate createBillingTemplateWithPromotions(CompositeTimeConfig config,
                                                                        BConstants.BillingMode mode,
                                                                        List<PromotionRuleConfig> promotionConfigs) {
        BillingConfigResolver resolver = new BillingConfigResolver() {
            @Override
            public BConstants.BillingMode resolveBillingMode(String schemeId, Map<String, Object> context) {
                return mode;
            }

            @Override
            public RuleConfig resolveChargingRule(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd, Map<String, Object> context) {
                return config;
            }

            @Override
            public List<PromotionRuleConfig> resolvePromotionRules(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd, Map<String, Object> context) {
                return promotionConfigs;
            }
        };

        var ruleRegistry = new BillingRuleRegistry();
        ruleRegistry.register(BConstants.ChargeRuleType.COMPOSITE_TIME, new CompositeTimeRule());

        var promotionRegistry = new PromotionRuleRegistry();
        promotionRegistry.register(BConstants.PromotionRuleType.FREE_MINUTES, new FreeMinutesPromotionRule());

        var promotionEngine = new PromotionEngine(
                resolver,
                new FreeTimeRangeMerger(),
                new FreeMinuteAllocator(),
                promotionRegistry
        );

        BillingService billingService = new BillingService(
                new SegmentBuilder(),
                resolver,
                promotionEngine,
                new BillingCalculator(ruleRegistry),
                new ResultAssembler()
        );

        return new BillingTemplate(billingService, resolver);
    }

    // ==================== UNIT_BASED 模式测试 ====================

    /**
     * 测试：UNIT_BASED 基本计算
     * <p>
     * 场景：单一相对时间段，60分钟单元，1元/单元
     * 计费时间：08:00-10:00（2小时）
     * <p>
     * 预期结果：
     * - 单元1：08:00-09:00，60分钟，1元
     * - 单元2：09:00-10:00，60分钟，1元
     * - 总金额：2元
     */
    static void testUnitBased_BasicCalculation() {
        System.out.println("=== 测试: UNIT_BASED 基本计算 ===");
        System.out.println("场景: 60分钟单元，1元/单元，计费时间 08:00-10:00");
        System.out.println("预期: 2个单元，总金额2元");

        CompositeTimeConfig config = createBaseConfig();

        BillingTemplate template = createBillingTemplate(config, BConstants.BillingMode.UNIT_BASED);
        BillingRequest request = createBaseRequest(
                LocalDateTime.of(2026, 1, 1, 8, 0),
                LocalDateTime.of(2026, 1, 1, 10, 0));
        BillingResult result = template.calculate(request);

        assertAmountEquals(BigDecimal.valueOf(2), result.getFinalAmount());
        assertEquals(2, result.getUnits().size());
        printBillingUnits(result.getUnits());
        System.out.println("结果: 通过 - 收费金额 = " + result.getFinalAmount() + "元, 单元数 = " + result.getUnits().size());
        System.out.println();
    }

    /**
     * 测试：UNIT_BASED 两个相对时间段
     * <p>
     * 场景：
     * - 相对时间段1：0-120分钟（前2小时），60分钟单元，1元/单元
     * - 相对时间段2：120-1440分钟（2小时后），30分钟单元，2元/单元
     * 计费时间：08:00-11:00（3小时）
     * <p>
     * 预期结果：
     * - 时间段1：08:00-10:00，2个单元 × 1元 = 2元
     * - 时间段2：10:00-11:00，2个单元 × 2元 = 4元
     * - 总金额：6元
     */
    static void testUnitBased_TwoRelativePeriods() {
        System.out.println("=== 测试: UNIT_BASED 两个相对时间段 ===");
        System.out.println("场景: 时间段1(前2小时): 60分钟单元, 1元/单元; 时间段2(2小时后): 30分钟单元, 2元/单元");
        System.out.println("计费时间: 08:00-11:00 (3小时)");
        System.out.println("预期: 时间段1=2元, 时间段2=4元, 总计6元");

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

        BillingTemplate template = createBillingTemplate(config, BConstants.BillingMode.UNIT_BASED);
        BillingRequest request = createBaseRequest(
                LocalDateTime.of(2026, 1, 1, 8, 0),
                LocalDateTime.of(2026, 1, 1, 11, 0));
        BillingResult result = template.calculate(request);

        assertAmountEquals(BigDecimal.valueOf(6), result.getFinalAmount());
        printBillingUnits(result.getUnits());
        System.out.println("结果: 通过 - 收费金额 = " + result.getFinalAmount() + "元");
        System.out.println();
    }

    // ==================== CrossPeriodMode 测试 ====================
    // CrossPeriodMode 用于处理计费单元跨越不同单价自然时段的情况
    // 例如：一个 60 分钟单元从 19:30 到 20:30，跨越了 20:00 这个价格边界

    /**
     * 测试：CrossPeriodMode.HIGHER_PRICE - 跨时段取高价
     * <p>
     * 自然时段配置：
     * - 00:00-08:00：1元/小时
     * - 08:00-20:00：2元/小时（高价时段）
     * - 20:00-24:00：1元/小时
     * <p>
     * 场景：计费单元 19:30-20:30 跨越 20:00 边界
     * 起点在2元时段，终点在1元时段
     * <p>
     * 预期：HIGHER_PRICE 模式取高价2元
     */
    static void testCrossPeriodMode_HigherPrice() {
        System.out.println("=== 测试: CrossPeriodMode.HIGHER_PRICE ===");
        System.out.println("场景: 计费单元 19:30-20:30 跨越价格边界 20:00");
        System.out.println("自然时段: 08:00-20:00=2元/小时, 其他=1元/小时");
        System.out.println("预期: HIGHER_PRICE 模式取高价2元");

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

        BillingTemplate template = createBillingTemplate(config, BConstants.BillingMode.UNIT_BASED);
        BillingRequest request = createBaseRequest(
                LocalDateTime.of(2026, 1, 1, 19, 30),
                LocalDateTime.of(2026, 1, 1, 20, 30));
        BillingResult result = template.calculate(request);

        assertAmountEquals(BigDecimal.valueOf(2), result.getFinalAmount());
        printBillingUnits(result.getUnits());
        System.out.println("结果: 通过 - 收费金额 = " + result.getFinalAmount() + "元");
        System.out.println();
    }

    /**
     * 测试：CrossPeriodMode.LOWER_PRICE - 跨时段取低价
     * <p>
     * 自然时段配置同上
     * <p>
     * 场景：计费单元 19:30-20:30 跨越 20:00 边界
     * <p>
     * 预期：LOWER_PRICE 模式取低价1元
     */
    static void testCrossPeriodMode_LowerPrice() {
        System.out.println("=== 测试: CrossPeriodMode.LOWER_PRICE ===");
        System.out.println("场景: 计费单元 19:30-20:30 跨越价格边界 20:00");
        System.out.println("预期: LOWER_PRICE 模式取低价1元");

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

        BillingTemplate template = createBillingTemplate(config, BConstants.BillingMode.UNIT_BASED);
        BillingRequest request = createBaseRequest(
                LocalDateTime.of(2026, 1, 1, 19, 30),
                LocalDateTime.of(2026, 1, 1, 20, 30));
        BillingResult result = template.calculate(request);

        assertAmountEquals(BigDecimal.valueOf(1), result.getFinalAmount());
        printBillingUnits(result.getUnits());
        System.out.println("结果: 通过 - 收费金额 = " + result.getFinalAmount() + "元");
        System.out.println();
    }

    /**
     * 测试：CrossPeriodMode.BEGIN_TIME_PRICE - 按起始时间定价
     * <p>
     * 场景：计费单元 19:30-20:30
     * 起始时间 19:30 在2元时段
     * <p>
     * 预期：BEGIN_TIME_PRICE 模式取起始时间价格2元
     */
    static void testCrossPeriodMode_BeginTimePrice() {
        System.out.println("=== 测试: CrossPeriodMode.BEGIN_TIME_PRICE ===");
        System.out.println("场景: 计费单元 19:30-20:30, 起始时间19:30在2元时段");
        System.out.println("预期: BEGIN_TIME_PRICE 模式取起始时间价格2元");

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

        BillingTemplate template = createBillingTemplate(config, BConstants.BillingMode.UNIT_BASED);
        BillingRequest request = createBaseRequest(
                LocalDateTime.of(2026, 1, 1, 19, 30),
                LocalDateTime.of(2026, 1, 1, 20, 30));
        BillingResult result = template.calculate(request);

        assertAmountEquals(BigDecimal.valueOf(2), result.getFinalAmount());
        printBillingUnits(result.getUnits());
        System.out.println("结果: 通过 - 收费金额 = " + result.getFinalAmount() + "元");
        System.out.println();
    }

    /**
     * 测试：CrossPeriodMode.END_TIME_PRICE - 按结束时间定价
     * <p>
     * 场景：计费单元 19:30-20:30
     * 结束时间 20:30 在1元时段（20:00后）
     * <p>
     * 预期：END_TIME_PRICE 模式取结束时间价格1元
     */
    static void testCrossPeriodMode_EndTimePrice() {
        System.out.println("=== 测试: CrossPeriodMode.END_TIME_PRICE ===");
        System.out.println("场景: 计费单元 19:30-20:30, 结束时间20:30在1元时段(20:00后)");
        System.out.println("预期: END_TIME_PRICE 模式取结束时间价格1元");

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

        BillingTemplate template = createBillingTemplate(config, BConstants.BillingMode.UNIT_BASED);
        BillingRequest request = createBaseRequest(
                LocalDateTime.of(2026, 1, 1, 19, 30),
                LocalDateTime.of(2026, 1, 1, 20, 30));
        BillingResult result = template.calculate(request);

        assertAmountEquals(BigDecimal.valueOf(1), result.getFinalAmount());
        printBillingUnits(result.getUnits());
        System.out.println("结果: 通过 - 收费金额 = " + result.getFinalAmount() + "元");
        System.out.println();
    }

    // ==================== 封顶测试 ====================
    // 时间段封顶（Period Cap）：对单个相对时间段的收费上限
    // 周期封顶（Cycle Cap）：对整个计费周期（通常24小时）的收费上限

    /**
     * 测试：时间段独立封顶 - 从最后一个单元削减
     * <p>
     * 场景：
     * - 单价：3元/小时
     * - 时间段封顶：5元
     * - 计费时间：08:00-11:00（3小时）
     * <p>
     * 计算：
     * - 正常收费：3单元 × 3元 = 9元
     * - 封顶后：5元
     * - 削减方式：从最后一个单元开始削减，使其免费或减少收费
     * <p>
     * 预期：总金额5元，最后一个单元标记为 PERIOD_CAP 免费
     */
    static void testPeriodCap_ReduceFromLastUnit() {
        System.out.println("=== 测试: 时间段独立封顶 - 从最后一个单元削减 ===");
        System.out.println("场景: 单价3元/小时, 时间段封顶5元, 计费时间08:00-11:00(3小时)");
        System.out.println("计算: 正常3×3=9元, 封顶后5元, 需削减4元");
        System.out.println("预期: 总金额5元, 最后单元被标记为PERIOD_CAP免费");

        CompositeTimeConfig config = CompositeTimeConfig.builder()
                .id("test")
                .maxChargeOneCycle(BigDecimal.valueOf(50))
                .periods(List.of(
                        CompositePeriod.builder()
                                .beginMinute(0).endMinute(1440).unitMinutes(60)
                                .maxCharge(BigDecimal.valueOf(5)) // 时间段封顶5元
                                .crossPeriodMode(CrossPeriodMode.BLOCK_WEIGHT)
                                .naturalPeriods(List.of(
                                        NaturalPeriod.builder().beginMinute(0).endMinute(1440).unitPrice(BigDecimal.valueOf(3)).build()
                                ))
                                .build()
                ))
                .build();

        BillingTemplate template = createBillingTemplate(config, BConstants.BillingMode.UNIT_BASED);
        BillingRequest request = createBaseRequest(
                LocalDateTime.of(2026, 1, 1, 8, 0),
                LocalDateTime.of(2026, 1, 1, 11, 0));
        BillingResult result = template.calculate(request);

        assertAmountEquals(BigDecimal.valueOf(5), result.getFinalAmount());

        BillingUnit lastUnit = result.getUnits().get(result.getUnits().size() - 1);
        assertTrue(lastUnit.isFree() || lastUnit.getChargedAmount().compareTo(BigDecimal.valueOf(3)) < 0);
        printBillingUnits(result.getUnits());
        System.out.println("结果: 通过 - 收费金额 = " + result.getFinalAmount() + "元, 最后单元免费=" + lastUnit.isFree());
        System.out.println();
    }

    /**
     * 测试：两个时间段 - 第一个时间段触发封顶
     * <p>
     * 场景：
     * - 时间段1（0-120分钟）：60分钟单元，2元/单元，封顶3元
     * - 时间段2（120-1440分钟）：60分钟单元，1元/单元，无封顶
     * - 计费时间：08:00-12:00（4小时）
     * <p>
     * 计算：
     * - 时间段1：2单元 × 2元 = 4元 → 封顶3元
     * - 时间段2：2单元 × 1元 = 2元
     * - 总计：3 + 2 = 5元
     * <p>
     * 预期：总金额5元
     */
    static void testPeriodCap_TwoPeriods_CapOnFirst() {
        System.out.println("=== 测试: 两个时间段 - 第一个时间段封顶 ===");
        System.out.println("场景: 时间段1(前2小时): 2元/单元, 封顶3元; 时间段2: 1元/单元, 无封顶");
        System.out.println("计费时间: 08:00-12:00 (4小时)");
        System.out.println("计算: 时间段1=4元→封顶3元, 时间段2=2元, 总计5元");

        CompositeTimeConfig config = CompositeTimeConfig.builder()
                .id("test")
                .maxChargeOneCycle(BigDecimal.valueOf(50))
                .periods(List.of(
                        CompositePeriod.builder()
                                .beginMinute(0).endMinute(120).unitMinutes(60)
                                .maxCharge(BigDecimal.valueOf(3)) // 时间段封顶3元
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

        BillingTemplate template = createBillingTemplate(config, BConstants.BillingMode.UNIT_BASED);
        BillingRequest request = createBaseRequest(
                LocalDateTime.of(2026, 1, 1, 8, 0),
                LocalDateTime.of(2026, 1, 1, 12, 0));
        BillingResult result = template.calculate(request);

        assertAmountEquals(BigDecimal.valueOf(5), result.getFinalAmount());
        printBillingUnits(result.getUnits());
        System.out.println("结果: 通过 - 收费金额 = " + result.getFinalAmount() + "元");
        System.out.println();
    }

    // ==================== 周期封顶测试 ====================
    // 周期封顶（Cycle Cap）由 maxChargeOneCycle 配置，作用于整个计费周期（通常24小时）
    // 封顶削减优先级：先处理时间段封顶，再处理周期封顶

    /**
     * 测试：周期封顶 + 时间段封顶（无削减）
     * <p>
     * 场景：
     * - 时间段1（0-120分钟）：封顶5元
     * - 时间段2（120-1440分钟）：无封顶
     * - 周期封顶：10元
     * - 计费时间：08:00-14:00（6小时）
     * <p>
     * 计算：
     * - 时间段1：2单元 × 1元 = 2元（未达封顶5元）
     * - 时间段2：4单元 × 2元 = 8元
     * - 总计：2 + 8 = 10元（刚好等于周期封顶）
     * <p>
     * 预期：总金额10元，无需额外削减
     */
    static void testCycleCap_WithPeriodCap() {
        System.out.println("=== 测试: 周期封顶 + 时间段封顶（无削减） ===");
        System.out.println("场景: 时间段1封顶5元, 周期封顶10元, 计费时间08:00-14:00");
        System.out.println("计算: 时间段1=2元(未达封顶), 时间段2=8元, 总计=10元=周期封顶");
        System.out.println("预期: 总金额10元, 无需额外削减");

        CompositeTimeConfig config = CompositeTimeConfig.builder()
                .id("test")
                .maxChargeOneCycle(BigDecimal.valueOf(10)) // 周期封顶10元
                .periods(List.of(
                        CompositePeriod.builder()
                                .beginMinute(0).endMinute(120).unitMinutes(60)
                                .maxCharge(BigDecimal.valueOf(5)) // 时间段封顶5元
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

        BillingTemplate template = createBillingTemplate(config, BConstants.BillingMode.UNIT_BASED);
        BillingRequest request = createBaseRequest(
                LocalDateTime.of(2026, 1, 1, 8, 0),
                LocalDateTime.of(2026, 1, 1, 14, 0));
        BillingResult result = template.calculate(request);

        assertAmountEquals(BigDecimal.valueOf(10), result.getFinalAmount());
        printBillingUnits(result.getUnits());
        System.out.println("结果: 通过 - 收费金额 = " + result.getFinalAmount() + "元");
        System.out.println();
    }

    /**
     * 测试：周期封顶需要削减
     * <p>
     * 场景：
     * - 周期封顶：6元
     * - 计费时间：08:00-14:00（6小时）
     * <p>
     * 计算：
     * - 时间段1：2单元 × 1元 = 2元
     * - 时间段2：4单元 × 2元 = 8元
     * - 总计：10元，超周期封顶4元
     * - 削减：从时间段2的单元中削减4元
     * <p>
     * 预期：总金额6元
     */
    static void testCycleCap_ReductionNeeded() {
        System.out.println("=== 测试: 周期封顶需要削减 ===");
        System.out.println("场景: 周期封顶6元, 计费时间08:00-14:00");
        System.out.println("计算: 时间段1=2元, 时间段2=8元, 总计=10元, 超封顶4元");
        System.out.println("预期: 总金额6元, 从时间段2单元中削减");

        CompositeTimeConfig config = CompositeTimeConfig.builder()
                .id("test")
                .maxChargeOneCycle(BigDecimal.valueOf(6)) // 周期封顶6元
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

        BillingTemplate template = createBillingTemplate(config, BConstants.BillingMode.UNIT_BASED);
        BillingRequest request = createBaseRequest(
                LocalDateTime.of(2026, 1, 1, 8, 0),
                LocalDateTime.of(2026, 1, 1, 14, 0));
        BillingResult result = template.calculate(request);

        assertAmountEquals(BigDecimal.valueOf(6), result.getFinalAmount());
        printBillingUnits(result.getUnits());
        System.out.println("结果: 通过 - 收费金额 = " + result.getFinalAmount() + "元");
        System.out.println();
    }

    /**
     * 测试：周期封顶不应削减已封顶单元
     * <p>
     * 场景：
     * - 时间段1：单价3元，封顶2元（会触发时间段封顶）
     * - 时间段2：单价2元，无封顶
     * - 周期封顶：4元
     * - 计费时间：08:00-14:00
     * <p>
     * 计算过程：
     * 1. 时间段1先封顶：2单元 × 3元 = 6元 → 封顶2元（第2单元标记 PERIOD_CAP）
     * 2. 时间段2：4单元 × 2元 = 8元
     * 3. 封顶后总计：2 + 8 = 10元
     * 4. 周期封顶削减：10元 → 4元，需削减6元
     * <p>
     * 关键验证：已被 PERIOD_CAP 标记的单元不应再被削减
     * 削减只能来自时间段2的单元
     * <p>
     * 预期：总金额4元，PERIOD_CAP 单元未被二次削减
     */
    static void testCycleCap_PeriodCapUnitsNotReduced() {
        System.out.println("=== 测试: 周期封顶不应削减已封顶单元 ===");
        System.out.println("场景: 时间段1(单价3元,封顶2元)会触发时间段封顶, 周期封顶4元");
        System.out.println("关键验证: 已被PERIOD_CAP标记的单元不应再被周期封顶削减");
        System.out.println("预期: 总金额4元, 削减只来自时间段2");

        CompositeTimeConfig config = CompositeTimeConfig.builder()
                .id("test")
                .maxChargeOneCycle(BigDecimal.valueOf(4)) // 周期封顶4元
                .periods(List.of(
                        CompositePeriod.builder()
                                .beginMinute(0).endMinute(120).unitMinutes(60)
                                .maxCharge(BigDecimal.valueOf(2)) // 时间段封顶2元
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

        BillingTemplate template = createBillingTemplate(config, BConstants.BillingMode.UNIT_BASED);
        BillingRequest request = createBaseRequest(
                LocalDateTime.of(2026, 1, 1, 8, 0),
                LocalDateTime.of(2026, 1, 1, 14, 0));
        BillingResult result = template.calculate(request);

        assertAmountEquals(BigDecimal.valueOf(4), result.getFinalAmount());
        printBillingUnits(result.getUnits());
        System.out.println("结果: 通过 - 收费金额 = " + result.getFinalAmount() + "元");
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

    // ==================== CONTINUOUS 模式测试 ====================
    // CONTINUOUS 模式特点：
    // 1. 时间连续流动，可被免费时段打断
    // 2. 免费时段（气泡）会"抽出"，导致计费时间形成多个片段
    // 3. 每个片段独立计费，但共享周期封顶

    /**
     * 测试：CONTINUOUS 基本计算（无免费时段）
     * <p>
     * 场景：60分钟单元，1元/单元，计费时间 08:00-10:00
     * <p>
     * 预期：与 UNIT_BASED 模式结果相同
     * - 2个单元，总金额2元
     */
    static void testContinuous_BasicCalculation() {
        System.out.println("=== 测试: CONTINUOUS 基本计算（无免费时段） ===");
        System.out.println("场景: 60分钟单元, 1元/单元, 计费时间 08:00-10:00");
        System.out.println("说明: 无免费时段时, CONTINUOUS 与 UNIT_BASED 结果相同");
        System.out.println("预期: 2个单元, 总金额2元");

        CompositeTimeConfig config = createBaseConfig();

        BillingTemplate template = createBillingTemplate(config, BConstants.BillingMode.CONTINUOUS);
        BillingRequest request = createBaseRequest(
                LocalDateTime.of(2026, 1, 1, 8, 0),
                LocalDateTime.of(2026, 1, 1, 10, 0));
        BillingResult result = template.calculate(request);

        assertAmountEquals(BigDecimal.valueOf(2), result.getFinalAmount());
        assertEquals(2, result.getUnits().size());
        printBillingUnits(result.getUnits());
        System.out.println("结果: 通过 - 收费金额 = " + result.getFinalAmount() + "元, 单元数 = " + result.getUnits().size());
        System.out.println();
    }

    /**
     * 测试：CONTINUOUS 气泡抽出模型
     * <p>
     * "气泡抽出"是 CONTINUOUS 模式的核心特性：
     * 免费时段像气泡一样被"抽出"，剩余时间重新连接成连续的计费片段
     * <p>
     * 场景：
     * - 免费时段：09:00-10:00（气泡）
     * - 计费时间：08:00-11:00
     * <p>
     * 时间轴示意：
     * 原始：  |--收费--|----免费----|--收费--|
     *         08:00   09:00      10:00   11:00
     * <p>
     * 抽出后：|--收费(片段1)--|--免费--|--收费(片段2)--|
     * <p>
     * 计算结果：
     * - 片段1：08:00-09:00 = 1单元 = 1元
     * - 免费段：09:00-10:00（标记为 FREE_PROMO_1）
     * - 片段2：10:00-11:00 = 1单元 = 1元
     * - 总计：2元
     */
    static void testContinuous_BubbleExtraction() {
        System.out.println("=== 测试: CONTINUOUS 气泡抽出模型 ===");
        System.out.println("场景: 免费时段 09:00-10:00, 计费时间 08:00-11:00");
        System.out.println("气泡抽出: 免费时段被抽出, 形成两个计费片段");
        System.out.println("片段1: 08:00-09:00=1元, 片段2: 10:00-11:00=1元, 总计2元");

        CompositeTimeConfig config = createBaseConfig();

        PromotionGrant freeRange = PromotionGrant.builder()
                .id("FREE_PROMO_1")
                .type(BConstants.PromotionType.FREE_RANGE)
                .source(BConstants.PromotionSource.COUPON)
                .priority(1)
                .beginTime(LocalDateTime.of(2026, 1, 1, 9, 0))
                .endTime(LocalDateTime.of(2026, 1, 1, 10, 0))
                .build();

        BillingTemplate template = createBillingTemplate(config, BConstants.BillingMode.CONTINUOUS);
        BillingRequest request = createBaseRequestWithPromos(
                LocalDateTime.of(2026, 1, 1, 8, 0),
                LocalDateTime.of(2026, 1, 1, 11, 0),
                List.of(freeRange));
        BillingResult result = template.calculate(request);

        assertAmountEquals(BigDecimal.valueOf(2), result.getFinalAmount());
        printBillingUnits(result.getUnits());
        System.out.println("结果: 通过 - 收费金额 = " + result.getFinalAmount() + "元");
        System.out.println();
    }

    /**
     * 测试：CONTINUOUS 气泡抽出 - 更长计费时间
     * <p>
     * 场景：
     * - 免费时段：09:00-10:00
     * - 计费时间：08:00-12:00（4小时）
     * <p>
     * 计算结果：
     * - 片段1：08:00-09:00 = 1单元 = 1元
     * - 免费段：09:00-10:00
     * - 片段2：10:00-12:00 = 2单元 = 2元
     * - 总计：3元
     */
    static void testContinuous_BubbleExtraction_WithFreeRange() {
        System.out.println("=== 测试: CONTINUOUS 气泡抽出 - 更长计费时间 ===");
        System.out.println("场景: 免费时段 09:00-10:00, 计费时间 08:00-12:00 (4小时)");
        System.out.println("计算: 片段1=1元, 片段2=2元, 总计3元");

        CompositeTimeConfig config = createBaseConfig();

        PromotionGrant freeRange = PromotionGrant.builder()
                .id("FREE_PROMO_1")
                .type(BConstants.PromotionType.FREE_RANGE)
                .source(BConstants.PromotionSource.COUPON)
                .priority(1)
                .beginTime(LocalDateTime.of(2026, 1, 1, 9, 0))
                .endTime(LocalDateTime.of(2026, 1, 1, 10, 0))
                .build();

        BillingTemplate template = createBillingTemplate(config, BConstants.BillingMode.CONTINUOUS);
        BillingRequest request = createBaseRequestWithPromos(
                LocalDateTime.of(2026, 1, 1, 8, 0),
                LocalDateTime.of(2026, 1, 1, 12, 0),
                List.of(freeRange));
        BillingResult result = template.calculate(request);

        assertAmountEquals(BigDecimal.valueOf(3), result.getFinalAmount());
        printBillingUnits(result.getUnits());
        System.out.println("结果: 通过 - 收费金额 = " + result.getFinalAmount() + "元");
        System.out.println();
    }

    /**
     * 测试：CONTINUOUS 周期封顶
     * <p>
     * 场景：
     * - 周期封顶：3元
     * - 计费时间：08:00-12:00（4小时 = 4单元 = 4元）
     * <p>
     * 预期：
     * - 封顶后收费：3元
     * - 最后一个单元标记为 CYCLE_CAP 免费
     */
    static void testContinuous_CycleCap() {
        System.out.println("=== 测试: CONTINUOUS 周期封顶 ===");
        System.out.println("场景: 周期封顶3元, 计费时间 08:00-12:00 (4单元=4元)");
        System.out.println("预期: 封顶后收3元, 最后单元标记为CYCLE_CAP免费");

        CompositeTimeConfig config = CompositeTimeConfig.builder()
                .id("test")
                .maxChargeOneCycle(BigDecimal.valueOf(3))
                .periods(createValidPeriods())
                .build();

        BillingTemplate template = createBillingTemplate(config, BConstants.BillingMode.CONTINUOUS);
        BillingRequest request = createBaseRequest(
                LocalDateTime.of(2026, 1, 1, 8, 0),
                LocalDateTime.of(2026, 1, 1, 12, 0));
        BillingResult result = template.calculate(request);

        assertAmountEquals(BigDecimal.valueOf(3), result.getFinalAmount());

        BillingUnit lastUnit = result.getUnits().get(result.getUnits().size() - 1);
        assertTrue(lastUnit.isFree());
        assertEquals("CYCLE_CAP", lastUnit.getFreePromotionId());
        printBillingUnits(result.getUnits());
        System.out.println("结果: 通过 - 收费金额 = " + result.getFinalAmount() + "元, 最后单元免费 = " + lastUnit.isFree());
        System.out.println();
    }

    /**
     * 测试：CONTINUOUS 两个相对时间段
     * <p>
     * 场景：
     * - 时间段1（0-120分钟）：60分钟单元，1元/单元
     * - 时间段2（120-1440分钟）：30分钟单元，2元/单元
     * - 计费时间：08:00-11:00（3小时）
     * <p>
     * 计算结果：
     * - 时间段1：08:00-10:00 = 2单元 = 2元
     * - 时间段2：10:00-11:00 = 2单元 × 2元 = 4元
     * - 总计：6元
     */
    static void testContinuous_TwoPeriods() {
        System.out.println("=== 测试: CONTINUOUS 两个相对时间段 ===");
        System.out.println("场景: 时间段1(前2小时): 60分钟单元,1元; 时间段2: 30分钟单元,2元");
        System.out.println("计费时间: 08:00-11:00 (3小时)");
        System.out.println("计算: 时间段1=2元, 时间段2=4元, 总计6元");

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

        BillingTemplate template = createBillingTemplate(config, BConstants.BillingMode.CONTINUOUS);
        BillingRequest request = createBaseRequest(
                LocalDateTime.of(2026, 1, 1, 8, 0),
                LocalDateTime.of(2026, 1, 1, 11, 0));
        BillingResult result = template.calculate(request);

        assertAmountEquals(BigDecimal.valueOf(6), result.getFinalAmount());
        printBillingUnits(result.getUnits());
        System.out.println("结果: 通过 - 收费金额 = " + result.getFinalAmount() + "元");
        System.out.println();
    }

    /**
     * 测试：CONTINUOUS 中间免费时段
     * <p>
     * 场景：
     * - 免费时段：09:30-10:30（在计费时间中间）
     * - 计费时间：08:00-12:00（4小时）
     * <p>
     * 时间轴示意：
     * |--收费--|----免费----|--收费--|
     * 08:00  09:30       10:30  12:00
     * <p>
     * 计算结果：
     * - 片段1：08:00-09:30 = 90分钟 = 1单元 = 1元
     * - 免费段：09:30-10:30 = 60分钟（标记为 FREE_PROMO_1）
     * - 片段2：10:30-12:00 = 90分钟 = 1单元 = 1元
     * - 总计：约4元（根据实际单元划分）
     */
    static void testContinuous_FreeRangeInMiddle() {
        System.out.println("=== 测试: CONTINUOUS 中间免费时段 ===");
        System.out.println("场景: 免费时段 09:30-10:30 (在中间), 计费时间 08:00-12:00");
        System.out.println("时间轴: |08:00--收费--09:30|----免费----|10:30--收费--12:00|");

        CompositeTimeConfig config = createBaseConfig();

        PromotionGrant freeRange = PromotionGrant.builder()
                .id("FREE_PROMO_1")
                .type(BConstants.PromotionType.FREE_RANGE)
                .source(BConstants.PromotionSource.COUPON)
                .priority(1)
                .beginTime(LocalDateTime.of(2026, 1, 1, 9, 30))
                .endTime(LocalDateTime.of(2026, 1, 1, 10, 30))
                .build();

        BillingTemplate template = createBillingTemplate(config, BConstants.BillingMode.CONTINUOUS);
        BillingRequest request = createBaseRequestWithPromos(
                LocalDateTime.of(2026, 1, 1, 8, 0),
                LocalDateTime.of(2026, 1, 1, 12, 0),
                List.of(freeRange));
        BillingResult result = template.calculate(request);

        printBillingUnits(result.getUnits());
        System.out.println("结果: 通过 - 收费金额 = " + result.getFinalAmount() + "元, 单元数 = " + result.getUnits().size());
        System.out.println();
    }
}