package cn.shang.charging;

import cn.shang.charging.billing.BillingCalculator;
import cn.shang.charging.billing.BillingService;
import cn.shang.charging.billing.BillingConfigResolver;
import cn.shang.charging.billing.SegmentBuilder;
import cn.shang.charging.billing.pojo.*;
import cn.shang.charging.charge.rules.BillingRuleRegistry;
import cn.shang.charging.charge.rules.flatfree.FlatFreeConfig;
import cn.shang.charging.charge.rules.flatfree.FlatFreeRule;
import cn.shang.charging.util.JacksonUtils;
import cn.shang.charging.promotion.FreeMinuteAllocator;
import cn.shang.charging.promotion.FreeTimeRangeMerger;
import cn.shang.charging.promotion.PromotionEngine;
import cn.shang.charging.promotion.rules.PromotionRuleRegistry;
import cn.shang.charging.promotion.rules.minutes.FreeMinutesPromotionConfig;
import cn.shang.charging.promotion.rules.minutes.FreeMinutesPromotionRule;
import cn.shang.charging.promotion.pojo.PromotionGrant;
import cn.shang.charging.settlement.ResultAssembler;

import java.time.LocalDateTime;
import java.time.Month;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 统一免费计费规则测试
 */
public class FlatFreeTest {

    public static void main(String[] args) {
        System.out.println("========== 统一免费计费规则测试 ==========\n");

        // 测试1: CONTINUOUS 模式 - 免费
        testContinuousMode();

        // 测试2: UNIT_BASED 模式 - 免费
        testUnitBasedMode();

        // 测试3: CONTINUE 模式 - 从上次结果继续
        testContinueMode();

        // 测试4: 带外部优惠但仍返回免费（优惠被忽略）
        testWithPromotionsIgnored();

        System.out.println("\n========== 测试完成 ==========");
    }

    /**
     * 测试1: CONTINUOUS 模式
     */
    static void testContinuousMode() {
        System.out.println("=== 测试1: CONTINUOUS 模式 ===");

        var billingService = getBillingService(BConstants.BillingMode.CONTINUOUS);
        var request = new BillingRequest();
        request.setId("test-1");
        request.setBeginTime(LocalDateTime.of(2026, Month.MARCH, 10, 8, 0, 0));
        request.setEndTime(LocalDateTime.of(2026, Month.MARCH, 10, 14, 0, 0));
        request.setSchemeChanges(List.of());
        request.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        request.setSchemeId("free-scheme");
        request.setExternalPromotions(new ArrayList<>());

        var result = billingService.calculate(request);

        System.out.println("计费时间: 08:00 - 14:00 (6小时)");
        System.out.println("模式: CONTINUOUS");
        System.out.println("finalAmount = " + result.getFinalAmount());
        System.out.println("计费单元数量: " + result.getUnits().size());
        System.out.println(JacksonUtils.toJsonString(result));
        System.out.println();

        // 验证
        assert result.getFinalAmount().compareTo(java.math.BigDecimal.ZERO) == 0 : "金额应为 0";
        assert result.getUnits().size() == 1 : "应返回 1 个计费单元";
        assert result.getUnits().get(0).isFree() : "单元应标记为免费";
        assert result.getUnits().get(0).getFreePromotionId().equals("FLAT_FREE") : "freePromotionId 应为 FLAT_FREE";
    }

    /**
     * 测试2: UNIT_BASED 模式
     */
    static void testUnitBasedMode() {
        System.out.println("=== 测试2: UNIT_BASED 模式 ===");

        var billingService = getBillingService(BConstants.BillingMode.UNIT_BASED);
        var request = new BillingRequest();
        request.setId("test-2");
        request.setBeginTime(LocalDateTime.of(2026, Month.MARCH, 10, 8, 0, 0));
        request.setEndTime(LocalDateTime.of(2026, Month.MARCH, 10, 14, 0, 0));
        request.setSchemeChanges(List.of());
        request.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        request.setSchemeId("free-scheme");
        request.setExternalPromotions(new ArrayList<>());

        var result = billingService.calculate(request);

        System.out.println("计费时间: 08:00 - 14:00 (6小时)");
        System.out.println("模式: UNIT_BASED");
        System.out.println("finalAmount = " + result.getFinalAmount());
        System.out.println("计费单元数量: " + result.getUnits().size());
        System.out.println(JacksonUtils.toJsonString(result));
        System.out.println();

        // 验证
        assert result.getFinalAmount().compareTo(java.math.BigDecimal.ZERO) == 0 : "金额应为 0";
        assert result.getUnits().size() == 1 : "应返回 1 个计费单元（两种模式行为一致）";
        assert result.getUnits().get(0).isFree() : "单元应标记为免费";
    }

    /**
     * 测试3: CONTINUE 模式
     */
    static void testContinueMode() {
        System.out.println("=== 测试3: CONTINUE 模式 ===");

        // 第一次计算
        var billingService = getBillingService(BConstants.BillingMode.CONTINUOUS);
        var request1 = new BillingRequest();
        request1.setId("test-3");
        request1.setBeginTime(LocalDateTime.of(2026, Month.MARCH, 10, 8, 0, 0));
        request1.setEndTime(LocalDateTime.of(2026, Month.MARCH, 10, 12, 0, 0));
        request1.setSchemeChanges(List.of());
        request1.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        request1.setSchemeId("free-scheme");
        request1.setExternalPromotions(new ArrayList<>());

        var result1 = billingService.calculate(request1);

        System.out.println("第一次计算: 08:00 - 12:00");
        System.out.println("finalAmount = " + result1.getFinalAmount());
        System.out.println("carryOver.calculatedUpTo = " + result1.getCarryOver().getCalculatedUpTo());
        System.out.println();

        // 验证第一次计算
        assert result1.getFinalAmount().compareTo(java.math.BigDecimal.ZERO) == 0 : "第一次金额应为 0";
        assert result1.getCarryOver() != null : "应携带结转状态";
        assert result1.getCarryOver().getCalculatedUpTo() != null : "应携带 calculatedUpTo";
        assert result1.getCarryOver().getSegments() != null : "应携带分段状态";

        // 从第一次结果继续计算
        var request2 = new BillingRequest();
        request2.setId("test-3");
        request2.setBeginTime(LocalDateTime.of(2026, Month.MARCH, 10, 8, 0, 0));
        request2.setEndTime(LocalDateTime.of(2026, Month.MARCH, 10, 14, 0, 0));
        request2.setSchemeChanges(List.of());
        request2.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        request2.setSchemeId("free-scheme");
        request2.setExternalPromotions(new ArrayList<>());
        request2.setPreviousCarryOver(result1.getCarryOver());

        var result2 = billingService.calculate(request2);

        System.out.println("第二次计算 (CONTINUE): 08:00 - 14:00");
        System.out.println("finalAmount = " + result2.getFinalAmount());
        System.out.println("carryOver.calculatedUpTo = " + result2.getCarryOver().getCalculatedUpTo());
        System.out.println(JacksonUtils.toJsonString(result2));
        System.out.println();

        // 验证第二次计算
        assert result2.getFinalAmount().compareTo(java.math.BigDecimal.ZERO) == 0 : "第二次金额应为 0";
        assert result2.getCarryOver().getCalculatedUpTo() != null : "应携带新的 calculatedUpTo";
    }

    /**
     * 测试4: 带外部优惠但仍返回免费（优惠被忽略）
     */
    static void testWithPromotionsIgnored() {
        System.out.println("=== 测试4: 外部优惠被忽略 ===");

        var billingService = getBillingService(BConstants.BillingMode.CONTINUOUS);
        var request = new BillingRequest();
        request.setId("test-4");
        request.setBeginTime(LocalDateTime.of(2026, Month.MARCH, 10, 8, 0, 0));
        request.setEndTime(LocalDateTime.of(2026, Month.MARCH, 10, 14, 0, 0));
        request.setSchemeChanges(List.of());
        request.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        request.setSchemeId("free-scheme");

        // 添加外部免费时段优惠（应被规则忽略）
        request.setExternalPromotions(List.of(
                new PromotionGrant()
                        .setId("external-free-range")
                        .setType(BConstants.PromotionType.FREE_RANGE)
                        .setBeginTime(LocalDateTime.of(2026, Month.MARCH, 10, 9, 0, 0))
                        .setEndTime(LocalDateTime.of(2026, Month.MARCH, 10, 11, 0, 0))
                        .setPriority(1)
                        .setSource(BConstants.PromotionSource.COUPON)
        ));

        var result = billingService.calculate(request);

        System.out.println("计费时间: 08:00 - 14:00");
        System.out.println("外部优惠: 09:00-11:00 免费时段");
        System.out.println("finalAmount = " + result.getFinalAmount());
        System.out.println("计费单元数量: " + result.getUnits().size());
        System.out.println(JacksonUtils.toJsonString(result));
        System.out.println();

        // 验证
        assert result.getFinalAmount().compareTo(java.math.BigDecimal.ZERO) == 0 : "金额应为 0";
        assert result.getUnits().size() == 1 : "应返回 1 个计费单元（不因外部优惠切分）";
    }

    // ==================== 辅助方法 ====================

    static BillingService getBillingService(BConstants.BillingMode billingMode) {
        var billingConfigResolver = new BillingConfigResolver() {
            @Override
            public BConstants.BillingMode resolveBillingMode(String schemeId, Map<String, Object> context) {
                return billingMode;
            }

            @Override
            public RuleConfig resolveChargingRule(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd, Map<String, Object> context) {
                return FlatFreeConfig.builder()
                        .id("flat-free-001")
                        .build();
            }

            @Override
            public List<PromotionRuleConfig> resolvePromotionRules(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd, Map<String, Object> context) {
                // 返回免费分钟规则配置，但 FlatFreeRule 会忽略它
                return List.of(
                        new FreeMinutesPromotionConfig()
                                .setId("rule-free-min")
                                .setPriority(1)
                                .setMinutes(30)
                );
            }
        };

        var promotionRegistry = new PromotionRuleRegistry();
        promotionRegistry.register(BConstants.PromotionRuleType.FREE_MINUTES, new FreeMinutesPromotionRule());

        var promotionEngine = new PromotionEngine(
                billingConfigResolver,
                new FreeTimeRangeMerger(),
                new FreeMinuteAllocator(),
                promotionRegistry
        );

        var ruleRegistry = new BillingRuleRegistry();
        ruleRegistry.register(BConstants.ChargeRuleType.FLAT_FREE, new FlatFreeRule());

        return new BillingService(
                new SegmentBuilder(),
                billingConfigResolver,
                promotionEngine,
                new BillingCalculator(ruleRegistry),
                new ResultAssembler()
        );
    }
}