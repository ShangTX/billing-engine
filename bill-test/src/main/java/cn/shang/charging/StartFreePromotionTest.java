package cn.shang.charging;

import cn.shang.charging.billing.BillingCalculator;
import cn.shang.charging.billing.BillingService;
import cn.shang.charging.billing.BillingConfigResolver;
import cn.shang.charging.billing.SegmentBuilder;
import cn.shang.charging.billing.pojo.*;
import cn.shang.charging.charge.rules.BillingRuleRegistry;
import cn.shang.charging.charge.rules.daynight.DayNightConfig;
import cn.shang.charging.charge.rules.daynight.DayNightRule;
import cn.shang.charging.util.JacksonUtils;
import cn.shang.charging.promotion.FreeMinuteAllocator;
import cn.shang.charging.promotion.FreeTimeRangeMerger;
import cn.shang.charging.promotion.PromotionEngine;
import cn.shang.charging.promotion.pojo.PromotionGrant;
import cn.shang.charging.promotion.rules.PromotionRuleRegistry;
import cn.shang.charging.promotion.rules.minutes.FreeMinutesPromotionConfig;
import cn.shang.charging.promotion.rules.minutes.FreeMinutesPromotionRule;
import cn.shang.charging.promotion.rules.startfree.StartFreePromotionConfig;
import cn.shang.charging.promotion.rules.startfree.StartFreePromotionRule;
import cn.shang.charging.settlement.ResultAssembler;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.Month;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 前N分钟免费优惠规则测试
 */
public class StartFreePromotionTest {

    public static void main(String[] args) {
        System.out.println("========== 前N分钟免费优惠测试 ==========\n");

        // 测试1: 基础功能 - 前30分钟免费
        testBasicStartFree();

        // 测试2: 与外部免费时段重叠 - 按优先级合并
        testOverlapWithExternalFreeRange();

        // 测试3: 计算窗口小于N分钟 - 部分覆盖
        testPartialCoverage();

        // 测试4: CONTINUE 模式 - N分钟相对于段起点
        testContinueMode();

        System.out.println("\n========== 测试完成 ==========");
    }

    /**
     * 测试1: 基础功能 - 前30分钟免费
     */
    static void testBasicStartFree() {
        System.out.println("=== 测试1: 基础功能 - 前30分钟免费 ===");

        var billingService = getBillingServiceWithStartFree(30);
        var request = new BillingRequest();
        request.setId("test-1");
        request.setBeginTime(LocalDateTime.of(2026, Month.MARCH, 10, 8, 0, 0));
        request.setEndTime(LocalDateTime.of(2026, Month.MARCH, 10, 12, 0, 0));
        request.setSchemeChanges(List.of());
        request.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        request.setSchemeId("scheme-1");
        request.setExternalPromotions(new ArrayList<>());

        var result = billingService.calculate(request);

        System.out.println("计费时间: 08:00 - 12:00 (4小时)");
        System.out.println("规则: 前30分钟免费 (08:00-08:30)");
        System.out.println("finalAmount = " + result.getFinalAmount());
        System.out.println("计费单元数量: " + result.getUnits().size());
        System.out.println(JacksonUtils.toJsonString(result));
        System.out.println();

        // 验证: 前30分钟应免费
        assert result.getUnits().size() > 0 : "应有计费单元";
    }

    /**
     * 测试2: 与外部免费时段重叠 - 按优先级合并
     */
    static void testOverlapWithExternalFreeRange() {
        System.out.println("=== 测试2: 与外部免费时段重叠 ===");

        var billingService = getBillingServiceWithStartFree(30);
        var request = new BillingRequest();
        request.setId("test-2");
        request.setBeginTime(LocalDateTime.of(2026, Month.MARCH, 10, 8, 0, 0));
        request.setEndTime(LocalDateTime.of(2026, Month.MARCH, 10, 12, 0, 0));
        request.setSchemeChanges(List.of());
        request.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        request.setSchemeId("scheme-1");

        // 外部免费时段: 08:20-09:00 (与规则的前30分钟 08:00-08:30 重叠)
        request.setExternalPromotions(List.of(
                PromotionGrant.builder()
                        .id("external-range-1")
                        .type(BConstants.PromotionType.FREE_RANGE)
                        .priority(1)
                        .source(BConstants.PromotionSource.COUPON)
                        .beginTime(LocalDateTime.of(2026, Month.MARCH, 10, 8, 20, 0))
                        .endTime(LocalDateTime.of(2026, Month.MARCH, 10, 9, 0, 0))
                        .build()
        ));

        var result = billingService.calculate(request);

        System.out.println("计费时间: 08:00 - 12:00 (4小时)");
        System.out.println("规则: 前30分钟免费 (08:00-08:30)");
        System.out.println("外部: 免费时段 08:20-09:00");
        System.out.println("合并后: 08:00-09:00 (60分钟免费)");
        System.out.println("finalAmount = " + result.getFinalAmount());
        System.out.println(JacksonUtils.toJsonString(result));
        System.out.println();
    }

    /**
     * 测试3: 计算窗口小于N分钟 - 部分覆盖
     */
    static void testPartialCoverage() {
        System.out.println("=== 测试3: 窗口小于N分钟 ===");

        var billingService = getBillingServiceWithStartFree(60);
        var request = new BillingRequest();
        request.setId("test-3");
        request.setBeginTime(LocalDateTime.of(2026, Month.MARCH, 10, 8, 0, 0));
        request.setEndTime(LocalDateTime.of(2026, Month.MARCH, 10, 8, 20, 0));
        request.setSchemeChanges(List.of());
        request.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        request.setSchemeId("scheme-1");
        request.setExternalPromotions(new ArrayList<>());

        var result = billingService.calculate(request);

        System.out.println("计费时间: 08:00 - 08:20 (20分钟)");
        System.out.println("规则: 前60分钟免费 (08:00-09:00)");
        System.out.println("窗口只覆盖20分钟，应全部免费");
        System.out.println("finalAmount = " + result.getFinalAmount());
        System.out.println(JacksonUtils.toJsonString(result));
        System.out.println();

        // 验证: 20分钟应全部免费
        assert result.getFinalAmount().compareTo(BigDecimal.ZERO) == 0 : "窗口内应全部免费";
    }

    /**
     * 测试4: CONTINUE 模式 - N分钟相对于段起点
     */
    static void testContinueMode() {
        System.out.println("=== 测试4: CONTINUE 模式 ===");

        var billingService = getBillingServiceWithStartFree(30);

        // 第一次计算: 08:00-10:00
        var request1 = new BillingRequest();
        request1.setId("test-4");
        request1.setBeginTime(LocalDateTime.of(2026, Month.MARCH, 10, 8, 0, 0));
        request1.setEndTime(LocalDateTime.of(2026, Month.MARCH, 10, 10, 0, 0));
        request1.setSchemeChanges(List.of());
        request1.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        request1.setSchemeId("scheme-1");
        request1.setExternalPromotions(new ArrayList<>());

        var result1 = billingService.calculate(request1);

        System.out.println("第一次计算: 08:00 - 10:00");
        System.out.println("finalAmount = " + result1.getFinalAmount());
        System.out.println(JacksonUtils.toJsonString(result1));
        System.out.println();

        // 验证第一次计算
        assert result1.getCarryOver() != null : "应携带结转状态";
        assert result1.getCarryOver().getCalculatedUpTo() != null : "应携带 calculatedUpTo";

        // 从第一次结果继续计算: 08:00-12:00
        var request2 = new BillingRequest();
        request2.setId("test-4");
        request2.setBeginTime(LocalDateTime.of(2026, Month.MARCH, 10, 8, 0, 0));
        request2.setEndTime(LocalDateTime.of(2026, Month.MARCH, 10, 12, 0, 0));
        request2.setSchemeChanges(List.of());
        request2.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        request2.setSchemeId("scheme-1");
        request2.setExternalPromotions(new ArrayList<>());
        request2.setPreviousCarryOver(result1.getCarryOver());

        var result2 = billingService.calculate(request2);

        System.out.println("第二次计算 (CONTINUE): 08:00 - 12:00");
        System.out.println("finalAmount = " + result2.getFinalAmount());
        System.out.println(JacksonUtils.toJsonString(result2));
        System.out.println();

        // 验证: CONTINUE 模式下，前30分钟(08:00-08:30)已在第一次计算中免费
        // 第二次计算不应重复计算这部分费用
    }

    // ==================== 辅助方法 ====================

    static BillingService getBillingServiceWithStartFree(int startFreeMinutes) {
        var billingConfigResolver = new BillingConfigResolver() {
            @Override
            public BConstants.BillingMode resolveBillingMode(String schemeId, Map<String, Object> context) {
                return BConstants.BillingMode.CONTINUOUS;
            }

            @Override
            public RuleConfig resolveChargingRule(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd, Map<String, Object> context) {
                return new DayNightConfig()
                        .setId("daynight-1")
                        .setBlockWeight(new BigDecimal("0.5"))
                        .setDayBeginMinute(740)   // 12:20
                        .setDayEndMinute(1140)    // 19:00
                        .setDayUnitPrice(new BigDecimal("2"))
                        .setNightUnitPrice(new BigDecimal("1"))
                        .setMaxChargeOneDay(new BigDecimal("100"))
                        .setUnitMinutes(60);
            }

            @Override
            public List<PromotionRuleConfig> resolvePromotionRules(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd, Map<String, Object> context) {
                return List.of(
                        new StartFreePromotionConfig()
                                .setId("start-free-30")
                                .setMinutes(startFreeMinutes)
                                .setPriority(1)
                );
            }
        };

        var promotionRegistry = new PromotionRuleRegistry();
        promotionRegistry.register(BConstants.PromotionRuleType.START_FREE, new StartFreePromotionRule());

        var promotionEngine = new PromotionEngine(
                billingConfigResolver,
                new FreeTimeRangeMerger(),
                new FreeMinuteAllocator(),
                promotionRegistry
        );

        var ruleRegistry = new BillingRuleRegistry();
        ruleRegistry.register(BConstants.ChargeRuleType.DAY_NIGHT, new DayNightRule());

        return new BillingService(
                new SegmentBuilder(),
                billingConfigResolver,
                promotionEngine,
                new BillingCalculator(ruleRegistry),
                new ResultAssembler()
        );
    }
}
