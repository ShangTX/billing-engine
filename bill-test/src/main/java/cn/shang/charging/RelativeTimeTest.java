package cn.shang.charging;

import cn.shang.charging.billing.BillingCalculator;
import cn.shang.charging.billing.BillingService;
import cn.shang.charging.billing.BillingConfigResolver;
import cn.shang.charging.billing.BillingSegment;
import cn.shang.charging.billing.SegmentBuilder;
import cn.shang.charging.billing.pojo.*;
import cn.shang.charging.charge.rules.BillingRuleRegistry;
import cn.shang.charging.charge.rules.relativetime.RelativeTimeConfig;
import cn.shang.charging.charge.rules.relativetime.RelativeTimePeriod;
import cn.shang.charging.charge.rules.relativetime.RelativeTimeRule;
import cn.shang.charging.charge.util.JacksonUtils;
import cn.shang.charging.promotion.FreeMinuteAllocator;
import cn.shang.charging.promotion.FreeTimeRangeMerger;
import cn.shang.charging.promotion.PromotionEngine;
import cn.shang.charging.promotion.rules.PromotionRuleRegistry;
import cn.shang.charging.promotion.pojo.PromotionGrant;
import cn.shang.charging.promotion.rules.minutes.FreeMinutesPromotionConfig;
import cn.shang.charging.promotion.rules.minutes.FreeMinutesPromotionRule;
import cn.shang.charging.promotion.rules.ranges.FreeTimeRangePromotionRule;
import cn.shang.charging.settlement.ResultAssembler;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.Month;
import java.util.ArrayList;
import java.util.List;

/**
 * 相对时间段计费测试
 *
 * 测试覆盖：
 * 1. 配置校验（封顶金额必填）
 * 2. 基础计费（单周期、跨周期）
 * 3. 封顶逻辑
 * 4. 优惠功能（免费分钟数、免费时段）
 * 5. 计费单元延伸
 * 6. CONTINUOUS vs UNIT_BASED 模式对比
 */
public class RelativeTimeTest {

    // 通用配置参数
    private static final BigDecimal DEFAULT_CAP = new BigDecimal("100");
    private static final BigDecimal LOW_CAP = new BigDecimal("5");

    public static void main(String[] args) {
        System.out.println("========== 相对时间段计费测试 ==========\n");

        // === 配置校验测试 ===
        testConfigValidation_MissingCap();
        testConfigValidation_NegativeCap();

        // === 基础计费测试 ===
        testSingleCycle();
        testCrossCycle();
        testPartialUnit();

        // === 封顶逻辑测试 ===
        testCap_Basic();
        testCap_WithFreeMinutes();
        testCap_NotReached();

        // === 优惠功能测试 ===
        testFreeTimeRangeExternal();
        testFreeMinutes_RuleOnly();
        testFreeMinutes_Combined();
        testCompoundPromotion();

        // === 计费单元延伸测试 ===
        testUnitExtension_TruncatedUnit();
        testUnitExtension_CapFreeUnit();

        // === 计费模式对比测试 ===
        testBillingMode_Comparison();
    }

    // ==================== 配置校验测试 ====================

    static void testConfigValidation_MissingCap() {
        System.out.println("=== 测试: 配置校验 - 封顶金额为空 ===");
        try {
            var config = RelativeTimeConfig.builder()
                    .id("test-config")
                    .periods(List.of(
                            RelativeTimePeriod.builder()
                                    .beginMinute(0)
                                    .endMinute(1440)
                                    .unitMinutes(60)
                                    .unitPrice(BigDecimal.ONE)
                                    .build()
                    ))
                    .maxChargeOneCycle(null) // 故意设为 null
                    .build();

            var rule = new RelativeTimeRule();
            var context = createTestContext(BConstants.BillingMode.CONTINUOUS);
            rule.calculate(context, config, null);

            System.out.println("失败: 应该抛出异常");
        } catch (IllegalArgumentException e) {
            System.out.println("通过: " + e.getMessage());
        }
        System.out.println();
    }

    static void testConfigValidation_NegativeCap() {
        System.out.println("=== 测试: 配置校验 - 封顶金额为负数 ===");
        try {
            var config = RelativeTimeConfig.builder()
                    .id("test-config")
                    .periods(List.of(
                            RelativeTimePeriod.builder()
                                    .beginMinute(0)
                                    .endMinute(1440)
                                    .unitMinutes(60)
                                    .unitPrice(BigDecimal.ONE)
                                    .build()
                    ))
                    .maxChargeOneCycle(new BigDecimal("-10")) // 故意设为负数
                    .build();

            var rule = new RelativeTimeRule();
            var context = createTestContext(BConstants.BillingMode.CONTINUOUS);
            rule.calculate(context, config, null);

            System.out.println("失败: 应该抛出异常");
        } catch (IllegalArgumentException e) {
            System.out.println("通过: " + e.getMessage());
        }
        System.out.println();
    }

    // ==================== 基础计费测试 ====================

    static void testSingleCycle() {
        System.out.println("=== 测试1: 单周期计费 ===");

        var billingService = getBillingService(DEFAULT_CAP, true);
        var request = createRequest("08:00", "14:00");

        var result = billingService.calculate(request);

        System.out.println("计费时间: 08:00 - 14:00 (6小时)");
        System.out.println("配置: Period1(0-120分钟) 单元30分钟/1元, Period2(120-1440分钟) 单元60分钟/2元");
        System.out.println("优惠: 规则级别免费分钟数30分钟");
        System.out.println();
        System.out.println("结果: finalAmount = " + result.getFinalAmount());
        System.out.println("计费单元数: " + result.getUnits().size());
        System.out.println("calculationEndTime: " + result.getCalculationEndTime());
        System.out.println("预期: 11元 (原始12元 - 免费分钟抵扣1元)");
        verifyEquals("金额", new BigDecimal("11"), result.getFinalAmount());
        System.out.println();
    }

    static void testCrossCycle() {
        System.out.println("=== 测试2: 跨周期计费 ===");

        var billingService = getBillingService(DEFAULT_CAP, true);
        var request = createRequest("2026-03-10 08:00", "2026-03-11 10:00");

        var result = billingService.calculate(request);

        System.out.println("计费时间: 08:00 - 次日10:00 (26小时)");
        System.out.println();
        System.out.println("结果: finalAmount = " + result.getFinalAmount());
        System.out.println("计费单元数: " + result.getUnits().size());
        System.out.println("calculationEndTime: " + result.getCalculationEndTime());
        System.out.println("预期: 51元 (原始52元 - 免费分钟抵扣1元)");
        verifyEquals("金额", new BigDecimal("51"), result.getFinalAmount());
        System.out.println();
    }

    static void testPartialUnit() {
        System.out.println("=== 测试3: 不足一个单元收全额 ===");

        var billingService = getBillingService(DEFAULT_CAP, true);
        var request = createRequest("08:00", "08:15");

        var result = billingService.calculate(request);

        System.out.println("计费时间: 08:00 - 08:15 (15分钟)");
        System.out.println("配置: 单元30分钟, 单价1元");
        System.out.println();
        System.out.println("结果: finalAmount = " + result.getFinalAmount());
        System.out.println("计费单元数: " + result.getUnits().size());

        var lastUnit = result.getUnits().get(result.getUnits().size() - 1);
        System.out.println("最后一个单元: " + lastUnit.getDurationMinutes() + "分钟, 延伸后结束于 " + lastUnit.getEndTime());
        System.out.println("预期: 0元 (免费分钟30分钟 >= 15分钟)");
        verifyEquals("金额", BigDecimal.ZERO, result.getFinalAmount());
        System.out.println();
    }

    // ==================== 封顶逻辑测试 ====================

    static void testCap_Basic() {
        System.out.println("=== 测试4: 封顶逻辑 - 基础 ===");

        var billingService = getBillingService(LOW_CAP, true); // 封顶5元
        var request = createRequest("08:00", "12:00");

        var result = billingService.calculate(request);

        System.out.println("计费时间: 08:00 - 12:00 (4小时)");
        System.out.println("封顶金额: 5元");
        System.out.println();
        System.out.println("结果: finalAmount = " + result.getFinalAmount());

        // 检查封顶后的单元标记
        var lastUnit = result.getUnits().get(result.getUnits().size() - 1);
        System.out.println("最后单元: free=" + lastUnit.isFree() + ", freePromotionId=" + lastUnit.getFreePromotionId());
        System.out.println("calculationEndTime: " + result.getCalculationEndTime());
        System.out.println("预期: 5元 (封顶), 最后单元延伸到周期边界");
        verifyEquals("金额", new BigDecimal("5.00"), result.getFinalAmount());
        System.out.println();
    }

    static void testCap_WithFreeMinutes() {
        System.out.println("=== 测试5: 封顶逻辑 - 结合免费分钟数 ===");

        var billingService = getBillingService(new BigDecimal("10"), true);
        var request = createRequest("08:00", "14:00");

        var result = billingService.calculate(request);

        System.out.println("计费时间: 08:00 - 14:00 (6小时)");
        System.out.println("封顶金额: 10元");
        System.out.println("优惠: 免费分钟数30分钟");
        System.out.println();
        System.out.println("结果: finalAmount = " + result.getFinalAmount());
        System.out.println("预期: 10元 (封顶)");
        verifyEquals("金额", new BigDecimal("10.00"), result.getFinalAmount());
        System.out.println();
    }

    static void testCap_NotReached() {
        System.out.println("=== 测试6: 封顶逻辑 - 未触发 ===");

        var billingService = getBillingService(new BigDecimal("100"), true);
        var request = createRequest("08:00", "10:00");

        var result = billingService.calculate(request);

        System.out.println("计费时间: 08:00 - 10:00 (2小时)");
        System.out.println("封顶金额: 100元");
        System.out.println();
        System.out.println("结果: finalAmount = " + result.getFinalAmount());
        System.out.println("预期: 3元 (未达封顶)");
        verifyEquals("金额", new BigDecimal("3"), result.getFinalAmount());
        System.out.println();
    }

    // ==================== 优惠功能测试 ====================

    static void testFreeTimeRangeExternal() {
        System.out.println("=== 测试7: 免费时段优惠（外部）===");

        var billingService = getBillingService(DEFAULT_CAP, true);
        var request = createRequest("08:00", "14:00");

        // 外部优惠: 免费时段 09:00 - 11:00
        request.setExternalPromotions(List.of(
                PromotionGrant.builder()
                        .id("external-free-range")
                        .type(BConstants.PromotionType.FREE_RANGE)
                        .priority(1)
                        .source(BConstants.PromotionSource.COUPON)
                        .beginTime(parseTime("09:00"))
                        .endTime(parseTime("11:00"))
                        .build()
        ));

        var result = billingService.calculate(request);

        System.out.println("计费时间: 08:00 - 14:00");
        System.out.println("外部优惠: 免费时段 09:00-11:00");
        System.out.println();
        System.out.println("结果: finalAmount = " + result.getFinalAmount());
        System.out.println("预期: 7元");
        verifyEquals("金额", new BigDecimal("7"), result.getFinalAmount());
        System.out.println();
    }

    static void testFreeMinutes_RuleOnly() {
        System.out.println("=== 测试8: 免费分钟数优惠（仅规则级别）===");

        var billingService = getBillingService(DEFAULT_CAP, true);
        var request = createRequest("08:00", "12:00");

        var result = billingService.calculate(request);

        System.out.println("计费时间: 08:00 - 12:00");
        System.out.println("优惠: 规则级别免费分钟数30分钟");
        System.out.println();
        System.out.println("结果: finalAmount = " + result.getFinalAmount());
        System.out.println("预期: 7元 (原始8元 - 第一个单元免费1元)");
        verifyEquals("金额", new BigDecimal("7"), result.getFinalAmount());
        System.out.println();
    }

    static void testFreeMinutes_Combined() {
        System.out.println("=== 测试9: 免费分钟数优惠（规则 + 外部）===");

        var billingService = getBillingService(DEFAULT_CAP, true);
        var request = createRequest("08:00", "12:00");

        // 外部优惠: 60分钟免费
        request.setExternalPromotions(List.of(
                PromotionGrant.builder()
                        .id("external-free-min")
                        .type(BConstants.PromotionType.FREE_MINUTES)
                        .priority(2)
                        .source(BConstants.PromotionSource.COUPON)
                        .freeMinutes(60)
                        .build()
        ));

        var result = billingService.calculate(request);

        System.out.println("计费时间: 08:00 - 12:00");
        System.out.println("优惠: 规则级别30分钟 + 外部60分钟 = 90分钟");
        System.out.println();
        System.out.println("结果: finalAmount = " + result.getFinalAmount());
        System.out.println("预期: 5元 (原始8元 - 前3个单元免费3元)");
        verifyEquals("金额", new BigDecimal("5"), result.getFinalAmount());
        System.out.println();
    }

    static void testCompoundPromotion() {
        System.out.println("=== 测试10: 复合优惠 ===");

        var billingService = getBillingService(DEFAULT_CAP, true);
        var request = createRequest("08:00", "14:00");

        request.setExternalPromotions(List.of(
                // 外部免费分钟数: 60分钟
                PromotionGrant.builder()
                        .id("external-free-min")
                        .type(BConstants.PromotionType.FREE_MINUTES)
                        .priority(2)
                        .source(BConstants.PromotionSource.COUPON)
                        .freeMinutes(60)
                        .build(),
                // 外部免费时段: 12:00 - 13:00
                PromotionGrant.builder()
                        .id("external-free-range")
                        .type(BConstants.PromotionType.FREE_RANGE)
                        .priority(1)
                        .source(BConstants.PromotionSource.COUPON)
                        .beginTime(parseTime("12:00"))
                        .endTime(parseTime("13:00"))
                        .build()
        ));

        var result = billingService.calculate(request);

        System.out.println("计费时间: 08:00 - 14:00");
        System.out.println("优惠: 规则30分钟 + 外部60分钟 + 外部时段1小时");
        System.out.println();
        System.out.println("结果: finalAmount = " + result.getFinalAmount());
        System.out.println("预期: 7元 (规则免费30分钟覆盖第1单元, 外部60分钟覆盖第2-3单元, 外部时段覆盖12:00-13:00)");
        verifyEquals("金额", new BigDecimal("7"), result.getFinalAmount());
        System.out.println();
    }

    // ==================== 计费单元延伸测试 ====================

    static void testUnitExtension_TruncatedUnit() {
        System.out.println("=== 测试11: 计费单元延伸 - 截断单元 ===");

        var billingService = getBillingService(DEFAULT_CAP, false); // 无优惠
        // 使用 08:00 - 10:30
        // Period 0-120 (08:00-10:00): 4个30分钟单元
        // Period 120-1440 (10:00起): 第一个单元10:00-11:00被截断到10:30
        var request = createRequest("08:00", "10:30"); // 最后单元被截断30分钟

        var result = billingService.calculate(request);

        System.out.println("计费时间: 08:00 - 10:30 (2.5小时)");
        System.out.println("配置: Period 0-120 (08:00-10:00) 30分钟单元, Period 120-1440 (10:00起) 60分钟单元");
        System.out.println();

        var lastUnit = result.getUnits().get(result.getUnits().size() - 1);
        System.out.println("最后单元: " + lastUnit.getBeginTime() + " - " + lastUnit.getEndTime());
        System.out.println("单元时长: " + lastUnit.getDurationMinutes() + "分钟");
        System.out.println("calculationEndTime: " + result.getCalculationEndTime());
        System.out.println("预期: 延伸到 11:00 (完整60分钟)");
        System.out.println();
    }

    static void testUnitExtension_CapFreeUnit() {
        System.out.println("=== 测试12: 计费单元延伸 - 封顶免费单元 ===");

        var billingService = getBillingService(LOW_CAP, false);
        // 使用更长时间触发封顶
        var request = createRequest("08:00", "14:00");  // 6小时，会超过5元封顶

        var result = billingService.calculate(request);

        System.out.println("计费时间: 08:00 - 14:00");
        System.out.println("封顶金额: 5元");
        System.out.println();

        var lastUnit = result.getUnits().get(result.getUnits().size() - 1);
        System.out.println("最后单元: " + lastUnit.getBeginTime() + " - " + lastUnit.getEndTime());
        System.out.println("单元时长: " + lastUnit.getDurationMinutes() + "分钟");
        System.out.println("是否免费: " + lastUnit.isFree());
        System.out.println("免费原因: " + lastUnit.getFreePromotionId());
        System.out.println("calculationEndTime: " + result.getCalculationEndTime());
        System.out.println("预期: 延伸到次日 08:00 (周期边界)");
        System.out.println();
    }

    // ==================== 计费模式对比测试 ====================

    static void testBillingMode_Comparison() {
        System.out.println("=== 测试13: CONTINUOUS vs UNIT_BASED 模式对比 ===");

        var request = createRequest("08:00", "09:30");
        // 添加免费时段 08:30 - 09:00
        request.setExternalPromotions(List.of(
                PromotionGrant.builder()
                        .id("free-range")
                        .type(BConstants.PromotionType.FREE_RANGE)
                        .priority(1)
                        .source(BConstants.PromotionSource.COUPON)
                        .beginTime(parseTime("08:30"))
                        .endTime(parseTime("09:00"))
                        .build()
        ));

        System.out.println("计费时间: 08:00 - 09:30");
        System.out.println("免费时段: 08:30 - 09:00");
        System.out.println();

        // CONTINUOUS 模式
        var continuousService = getBillingService(DEFAULT_CAP, false, BConstants.BillingMode.CONTINUOUS);
        var continuousResult = continuousService.calculate(request);
        System.out.println("CONTINUOUS 模式: finalAmount = " + continuousResult.getFinalAmount());
        System.out.println("  计费单元数: " + continuousResult.getUnits().size());
        System.out.println("  calculationEndTime: " + continuousResult.getCalculationEndTime());

        // UNIT_BASED 模式
        var unitBasedService = getBillingService(DEFAULT_CAP, false, BConstants.BillingMode.UNIT_BASED);
        var unitBasedResult = unitBasedService.calculate(request);
        System.out.println("UNIT_BASED 模式: finalAmount = " + unitBasedResult.getFinalAmount());
        System.out.println("  计费单元数: " + unitBasedResult.getUnits().size());
        System.out.println("  calculationEndTime: " + unitBasedResult.getCalculationEndTime());

        System.out.println();
        System.out.println("说明: CONTINUOUS 模式在免费时段边界切分，UNIT_BASED 模式固定单元对齐");
        System.out.println();
    }

    // ==================== 辅助方法 ====================

    static BillingService getBillingService(BigDecimal maxCharge, boolean withFreeMinutes) {
        return getBillingService(maxCharge, withFreeMinutes, BConstants.BillingMode.CONTINUOUS);
    }

    static BillingService getBillingService(BigDecimal maxCharge, boolean withFreeMinutes, BConstants.BillingMode mode) {
        var billingConfigResolver = new BillingConfigResolver() {
            @Override
            public BConstants.BillingMode resolveBillingMode(String schemeId) {
                return mode;
            }

            @Override
            public RuleConfig resolveChargingRule(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd) {
                List<RelativeTimePeriod> periods = List.of(
                        RelativeTimePeriod.builder()
                                .beginMinute(0)
                                .endMinute(120)
                                .unitMinutes(30)
                                .unitPrice(new BigDecimal("1"))
                                .build(),
                        RelativeTimePeriod.builder()
                                .beginMinute(120)
                                .endMinute(1440)
                                .unitMinutes(60)
                                .unitPrice(new BigDecimal("2"))
                                .build()
                );

                return RelativeTimeConfig.builder()
                        .id("relative-time-1")
                        .periods(periods)
                        .maxChargeOneCycle(maxCharge)
                        .build();
            }

            @Override
            public List<PromotionRuleConfig> resolvePromotionRules(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd) {
                if (withFreeMinutes) {
                    return List.of(
                            new FreeMinutesPromotionConfig()
                                    .setId("rule-free-min")
                                    .setPriority(1)
                                    .setMinutes(30)
                    );
                }
                return new ArrayList<>();
            }
        };

        var promotionRegistry = new PromotionRuleRegistry();
        promotionRegistry.register(BConstants.PromotionRuleType.FREE_TIME_RANGE, new FreeTimeRangePromotionRule());
        promotionRegistry.register(BConstants.PromotionRuleType.FREE_MINUTES, new FreeMinutesPromotionRule());

        var promotionEngine = new PromotionEngine(
                billingConfigResolver,
                new FreeTimeRangeMerger(),
                new FreeMinuteAllocator(),
                promotionRegistry
        );

        var ruleRegistry = new BillingRuleRegistry();
        ruleRegistry.register(BConstants.ChargeRuleType.RELATIVE_TIME, new RelativeTimeRule());

        return new BillingService(
                new SegmentBuilder(),
                billingConfigResolver,
                promotionEngine,
                new BillingCalculator(ruleRegistry),
                new ResultAssembler()
        );
    }

    static BillingRequest createRequest(String begin, String end) {
        var request = new BillingRequest();
        request.setId("test");
        request.setBeginTime(parseTime(begin));
        request.setEndTime(parseTime(end));
        request.setSchemeChanges(List.of());
        request.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        request.setSchemeId("scheme-1");
        request.setExternalPromotions(new ArrayList<>());
        return request;
    }

    static LocalDateTime parseTime(String time) {
        if (time.contains(" ")) {
            String[] parts = time.split(" ");
            String[] dateParts = parts[0].split("-");
            String[] timeParts = parts[1].split(":");
            return LocalDateTime.of(
                    Integer.parseInt(dateParts[0]),
                    Integer.parseInt(dateParts[1]),
                    Integer.parseInt(dateParts[2]),
                    Integer.parseInt(timeParts[0]),
                    Integer.parseInt(timeParts[1])
            );
        } else {
            return LocalDateTime.of(2026, Month.MARCH, 10,
                    Integer.parseInt(time.split(":")[0]),
                    Integer.parseInt(time.split(":")[1]));
        }
    }

    static BillingContext createTestContext(BConstants.BillingMode mode) {
        CalculationWindow window = new CalculationWindow();
        window.setCalculationBegin(LocalDateTime.of(2026, Month.MARCH, 10, 8, 0));
        window.setCalculationEnd(LocalDateTime.of(2026, Month.MARCH, 10, 10, 0));

        BillingSegment segment = new BillingSegment();
        segment.setBeginTime(LocalDateTime.of(2026, Month.MARCH, 10, 8, 0));
        segment.setEndTime(LocalDateTime.of(2026, Month.MARCH, 10, 10, 0));
        segment.setSchemeId("test");

        return BillingContext.builder()
                .billingMode(mode)
                .window(window)
                .segment(segment)
                .build();
    }

    static void verifyEquals(String name, BigDecimal expected, BigDecimal actual) {
        if (expected.compareTo(actual) == 0) {
            System.out.println("验证通过: " + name + " = " + actual);
        } else {
            System.out.println("验证失败: " + name + " 预期=" + expected + ", 实际=" + actual);
        }
    }
}