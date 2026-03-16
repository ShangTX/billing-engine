package cn.shang.charging;

import cn.shang.charging.billing.BillingCalculator;
import cn.shang.charging.billing.BillingService;
import cn.shang.charging.billing.BillingConfigResolver;
import cn.shang.charging.billing.SegmentBuilder;
import cn.shang.charging.billing.pojo.*;
import cn.shang.charging.charge.rules.BillingRuleRegistry;
import cn.shang.charging.charge.rules.daynight.DayNightConfig;
import cn.shang.charging.charge.rules.daynight.DayNightRule;
import cn.shang.charging.charge.rules.relativetime.RelativeTimeConfig;
import cn.shang.charging.charge.rules.relativetime.RelativeTimePeriod;
import cn.shang.charging.charge.rules.relativetime.RelativeTimeRule;
import cn.shang.charging.promotion.FreeMinuteAllocator;
import cn.shang.charging.promotion.FreeTimeRangeMerger;
import cn.shang.charging.promotion.PromotionEngine;
import cn.shang.charging.promotion.pojo.PromotionGrant;
import cn.shang.charging.promotion.rules.PromotionRuleRegistry;
import cn.shang.charging.promotion.rules.minutes.FreeMinutesPromotionConfig;
import cn.shang.charging.promotion.rules.minutes.FreeMinutesPromotionRule;
import cn.shang.charging.promotion.rules.ranges.FreeTimeRangePromotionRule;
import cn.shang.charging.settlement.ResultAssembler;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.Month;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 优惠结转功能测试
 *
 * 测试场景：
 * 1. 免费分钟数结转 - 部分使用后继续
 * 2. 免费时段结转 - 部分使用后继续
 * 3. 多次继续计算 - 优惠状态正确传递
 * 4. 混合优惠结转 - 同时有分钟数和时段
 */
public class PromotionCarryOverTest {

    public static void main(String[] args) {
        System.out.println("========== 优惠结转功能测试 ==========\n");

        // ==================== 免费分钟数结转测试 ====================
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║                   免费分钟数结转测试                         ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝\n");

        testFreeMinutes_RemainingCarryOver();
        testFreeMinutes_MultiContinue();
        testFreeMinutes_ExternalCoupon();

        // ==================== 免费时段结转测试 ====================
        System.out.println("\n╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║                   免费时段结转测试                           ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝\n");

        testFreeTimeRange_PartialUseCarryOver();
        testFreeTimeRange_MultiContinue();
        testFreeTimeRange_OverlapBoundary();

        // ==================== 混合优惠结转测试 ====================
        System.out.println("\n╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║                   混合优惠结转测试                           ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝\n");

        testMixedPromotion_BothTypes();
        testMixedPromotion_PriorityOrder();

        // ==================== 结转状态验证测试 ====================
        System.out.println("\n╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║                   结转状态验证测试                           ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝\n");

        testCarryOverState_Structure();
        testCarryOverState_NullWhenEmpty();

        System.out.println("\n========== 测试完成 ==========\n");
    }

    // ==================== 免费分钟数结转测试 ====================

    /**
     * 测试：免费分钟数部分使用后继续计算
     * 场景：60分钟免费额度，第一次用30分钟，第二次继续使用剩余30分钟
     */
    static void testFreeMinutes_RemainingCarryOver() {
        System.out.println("=== 测试: 免费分钟数部分使用后继续 ===\n");

        var billingService = createBillingService(false);

        // 外部优惠: 60分钟免费
        var freeMinutes = PromotionGrant.builder()
                .id("coupon-60min")
                .type(BConstants.PromotionType.FREE_MINUTES)
                .priority(1)
                .source(BConstants.PromotionSource.COUPON)
                .freeMinutes(60)
                .build();

        // 第一次计算: 08:00 - 09:30 (90分钟), 免费分钟覆盖前60分钟
        var request1 = createRequest("08:00", "09:30");
        request1.setExternalPromotions(List.of(freeMinutes));
        var result1 = billingService.calculate(request1);

        System.out.println("第一次计算: 08:00 - 09:30 (90分钟)");
        System.out.println("  外部优惠: 60分钟免费");
        System.out.println("  结果金额: " + result1.getFinalAmount());
        System.out.println("  预期: 前60分钟免费, 后30分钟收费");

        // 验证剩余免费分钟数
        var promoCarryOver = getPromotionCarryOver(result1);
        if (promoCarryOver != null && promoCarryOver.getRemainingMinutes() != null) {
            Integer remaining = promoCarryOver.getRemainingMinutes().get("coupon-60min");
            System.out.println("  剩余免费分钟: " + remaining + " 分钟");
        } else {
            System.out.println("  剩余免费分钟: 0 分钟 (已用完)");
        }

        // 第二次计算（CONTINUE）: 继续到 11:00
        var request2 = createRequest("08:00", "11:00");
        request2.setExternalPromotions(List.of(freeMinutes));
        request2.setPreviousCarryOver(result1.getCarryOver());
        var result2 = billingService.calculate(request2);

        System.out.println("\n第二次计算（CONTINUE）: 09:30 - 11:00 (90分钟)");
        System.out.println("  结果金额: " + result2.getFinalAmount());
        System.out.println("  预期: 无剩余免费分钟, 全部收费");

        // 验证总金额
        var totalFromScratch = createBillingService(false)
                .calculate(createRequestWithPromo("08:00", "11:00", freeMinutes));
        System.out.println("  一次性计算金额: " + totalFromScratch.getFinalAmount());
        System.out.println("  分两次计算累计: " + result1.getFinalAmount().add(result2.getFinalAmount()));

        System.out.println();
    }

    /**
     * 测试：多次继续计算，免费分钟数正确传递
     */
    static void testFreeMinutes_MultiContinue() {
        System.out.println("=== 测试: 多次继续计算 - 免费分钟数传递 ===\n");

        var billingService = createBillingService(false);

        // 规则级别免费分钟: 45分钟
        var billingServiceWithFreeMin = createBillingServiceWithRuleFreeMinutes(45);

        // 第一次: 08:00 - 09:00 (60分钟)
        var request1 = createRequest("08:00", "09:00");
        var result1 = billingServiceWithFreeMin.calculate(request1);

        System.out.println("第一次计算: 08:00 - 09:00 (60分钟)");
        System.out.println("  规则级别免费分钟: 45分钟");
        System.out.println("  结果金额: " + result1.getFinalAmount());

        var promo1 = getPromotionCarryOver(result1);
        if (promo1 != null && promo1.getRemainingMinutes() != null) {
            System.out.println("  剩余免费分钟: " + promo1.getRemainingMinutes());
        }

        // 第二次: 继续 09:00 - 10:00
        var request2 = createRequest("08:00", "10:00");
        request2.setPreviousCarryOver(result1.getCarryOver());
        var result2 = billingServiceWithFreeMin.calculate(request2);

        System.out.println("\n第二次计算（CONTINUE）: 09:00 - 10:00 (60分钟)");
        System.out.println("  结果金额: " + result2.getFinalAmount());

        // 第三次: 继续 10:00 - 11:00
        var request3 = createRequest("08:00", "11:00");
        request3.setPreviousCarryOver(result2.getCarryOver());
        var result3 = billingServiceWithFreeMin.calculate(request3);

        System.out.println("\n第三次计算（CONTINUE）: 10:00 - 11:00 (60分钟)");
        System.out.println("  结果金额: " + result3.getFinalAmount());

        // 汇总
        System.out.println("\n三次计算累计金额: " +
                result1.getFinalAmount().add(result2.getFinalAmount()).add(result3.getFinalAmount()));

        System.out.println();
    }

    /**
     * 测试：外部优惠券免费分钟数结转
     */
    static void testFreeMinutes_ExternalCoupon() {
        System.out.println("=== 测试: 外部优惠券免费分钟数结转 ===\n");

        var billingService = createBillingService(false);

        // 外部优惠券: 30分钟免费
        var coupon = PromotionGrant.builder()
                .id("external-coupon-30")
                .type(BConstants.PromotionType.FREE_MINUTES)
                .priority(2)
                .source(BConstants.PromotionSource.COUPON)
                .freeMinutes(30)
                .build();

        // 第一次计算: 只用了一部分
        var request1 = createRequest("08:00", "08:45");
        request1.setExternalPromotions(List.of(coupon));
        var result1 = billingService.calculate(request1);

        System.out.println("第一次计算: 08:00 - 08:45 (45分钟)");
        System.out.println("  外部优惠券: 30分钟免费");
        System.out.println("  结果金额: " + result1.getFinalAmount());

        // 验证剩余
        var promo1 = getPromotionCarryOver(result1);
        System.out.println("  剩余免费分钟: " + (promo1 != null ? promo1.getRemainingMinutes() : "无"));

        // 第二次继续计算
        var request2 = createRequest("08:00", "10:00");
        request2.setExternalPromotions(List.of(coupon));
        request2.setPreviousCarryOver(result1.getCarryOver());
        var result2 = billingService.calculate(request2);

        System.out.println("\n第二次计算（CONTINUE）: 08:45 - 10:00 (75分钟)");
        System.out.println("  结果金额: " + result2.getFinalAmount());
        System.out.println("  预期: 剩余0分钟免费额度已用完, 全部收费");

        System.out.println();
    }

    // ==================== 免费时段结转测试 ====================

    /**
     * 测试：免费时段部分使用后继续计算
     */
    static void testFreeTimeRange_PartialUseCarryOver() {
        System.out.println("=== 测试: 免费时段部分使用后继续 ===\n");

        var billingService = createBillingService(false);

        // 外部优惠: 免费时段 09:00 - 12:00
        var freeRange = PromotionGrant.builder()
                .id("free-range-9-12")
                .type(BConstants.PromotionType.FREE_RANGE)
                .priority(1)
                .source(BConstants.PromotionSource.COUPON)
                .beginTime(parseTime("09:00"))
                .endTime(parseTime("12:00"))
                .build();

        // 第一次计算: 08:00 - 10:00, 只用了 09:00-10:00 部分
        var request1 = createRequest("08:00", "10:00");
        request1.setExternalPromotions(List.of(freeRange));
        var result1 = billingService.calculate(request1);

        System.out.println("第一次计算: 08:00 - 10:00 (2小时)");
        System.out.println("  外部优惠: 免费时段 09:00-12:00 (3小时)");
        System.out.println("  结果金额: " + result1.getFinalAmount());
        System.out.println("  预期: 08:00-09:00收费, 09:00-10:00免费");

        // 验证已使用免费时段
        var promo1 = getPromotionCarryOver(result1);
        if (promo1 != null && promo1.getUsedFreeRanges() != null) {
            System.out.println("  已使用免费时段: " + promo1.getUsedFreeRanges());
        }

        // 第二次计算（CONTINUE）: 继续到 14:00
        var request2 = createRequest("08:00", "14:00");
        request2.setExternalPromotions(List.of(freeRange));
        request2.setPreviousCarryOver(result1.getCarryOver());
        var result2 = billingService.calculate(request2);

        System.out.println("\n第二次计算（CONTINUE）: 10:00 - 14:00 (4小时)");
        System.out.println("  结果金额: " + result2.getFinalAmount());
        System.out.println("  预期: 10:00-12:00免费, 12:00-14:00收费");

        // 验证一致性
        var totalFromScratch = createBillingService(false)
                .calculate(createRequestWithPromo("08:00", "14:00", freeRange));
        System.out.println("  一次性计算金额: " + totalFromScratch.getFinalAmount());
        System.out.println("  分两次计算累计: " + result1.getFinalAmount().add(result2.getFinalAmount()));

        System.out.println();
    }

    /**
     * 测试：多次继续计算，免费时段正确追踪
     */
    static void testFreeTimeRange_MultiContinue() {
        System.out.println("=== 测试: 多次继续计算 - 免费时段追踪 ===\n");

        var billingService = createBillingService(false);

        // 外部优惠: 免费时段 10:00 - 14:00
        var freeRange = PromotionGrant.builder()
                .id("free-10-14")
                .type(BConstants.PromotionType.FREE_RANGE)
                .priority(1)
                .source(BConstants.PromotionSource.COUPON)
                .beginTime(parseTime("10:00"))
                .endTime(parseTime("14:00"))
                .build();

        // 第一次: 08:00 - 11:00
        var request1 = createRequest("08:00", "11:00");
        request1.setExternalPromotions(List.of(freeRange));
        var result1 = billingService.calculate(request1);

        System.out.println("第一次计算: 08:00 - 11:00");
        System.out.println("  免费时段: 10:00-14:00");
        System.out.println("  结果金额: " + result1.getFinalAmount() + " (08:00-10:00收费, 10:00-11:00免费)");

        // 第二次: 继续 11:00 - 13:00
        var request2 = createRequest("08:00", "13:00");
        request2.setExternalPromotions(List.of(freeRange));
        request2.setPreviousCarryOver(result1.getCarryOver());
        var result2 = billingService.calculate(request2);

        System.out.println("\n第二次计算（CONTINUE）: 11:00 - 13:00");
        System.out.println("  结果金额: " + result2.getFinalAmount() + " (11:00-13:00免费)");

        // 第三次: 继续 13:00 - 16:00
        var request3 = createRequest("08:00", "16:00");
        request3.setExternalPromotions(List.of(freeRange));
        request3.setPreviousCarryOver(result2.getCarryOver());
        var result3 = billingService.calculate(request3);

        System.out.println("\n第三次计算（CONTINUE）: 13:00 - 16:00");
        System.out.println("  结果金额: " + result3.getFinalAmount() + " (13:00-14:00免费, 14:00-16:00收费)");

        // 汇总
        var total = result1.getFinalAmount().add(result2.getFinalAmount()).add(result3.getFinalAmount());
        System.out.println("\n三次计算累计: " + total);

        System.out.println();
    }

    /**
     * 测试：免费时段边界重叠
     */
    static void testFreeTimeRange_OverlapBoundary() {
        System.out.println("=== 测试: 免费时段边界重叠 ===\n");

        var billingService = createBillingService(false);

        // 外部优惠: 免费时段 10:00 - 12:00
        var freeRange = PromotionGrant.builder()
                .id("free-10-12")
                .type(BConstants.PromotionType.FREE_RANGE)
                .priority(1)
                .source(BConstants.PromotionSource.COUPON)
                .beginTime(parseTime("10:00"))
                .endTime(parseTime("12:00"))
                .build();

        // 第一次计算恰好到免费时段结束
        var request1 = createRequest("09:00", "12:00");
        request1.setExternalPromotions(List.of(freeRange));
        var result1 = billingService.calculate(request1);

        System.out.println("第一次计算: 09:00 - 12:00 (恰好到免费时段边界)");
        System.out.println("  免费时段: 10:00-12:00");
        System.out.println("  结果金额: " + result1.getFinalAmount());

        var promo1 = getPromotionCarryOver(result1);
        if (promo1 != null && promo1.getUsedFreeRanges() != null) {
            System.out.println("  已使用免费时段: " + promo1.getUsedFreeRanges());
        }

        // 第二次继续计算，应该没有免费时段剩余
        var request2 = createRequest("09:00", "14:00");
        request2.setExternalPromotions(List.of(freeRange));
        request2.setPreviousCarryOver(result1.getCarryOver());
        var result2 = billingService.calculate(request2);

        System.out.println("\n第二次计算（CONTINUE）: 12:00 - 14:00");
        System.out.println("  结果金额: " + result2.getFinalAmount());
        System.out.println("  预期: 免费时段已用完，全部收费");

        System.out.println();
    }

    // ==================== 混合优惠结转测试 ====================

    /**
     * 测试：同时有免费分钟数和免费时段
     */
    static void testMixedPromotion_BothTypes() {
        System.out.println("=== 测试: 混合优惠 - 免费分钟数 + 免费时段 ===\n");

        var billingService = createBillingService(false);

        // 外部优惠1: 免费分钟数 30分钟
        var freeMinutes = PromotionGrant.builder()
                .id("free-min-30")
                .type(BConstants.PromotionType.FREE_MINUTES)
                .priority(1)
                .source(BConstants.PromotionSource.COUPON)
                .freeMinutes(30)
                .build();

        // 外部优惠2: 免费时段 11:00 - 13:00
        var freeRange = PromotionGrant.builder()
                .id("free-range-11-13")
                .type(BConstants.PromotionType.FREE_RANGE)
                .priority(2)
                .source(BConstants.PromotionSource.COUPON)
                .beginTime(parseTime("11:00"))
                .endTime(parseTime("13:00"))
                .build();

        // 第一次计算
        var request1 = createRequest("08:00", "12:00");
        request1.setExternalPromotions(List.of(freeMinutes, freeRange));
        var result1 = billingService.calculate(request1);

        System.out.println("第一次计算: 08:00 - 12:00 (4小时)");
        System.out.println("  优惠1: 30分钟免费");
        System.out.println("  优惠2: 免费时段 11:00-13:00");
        System.out.println("  结果金额: " + result1.getFinalAmount());

        var promo1 = getPromotionCarryOver(result1);
        if (promo1 != null) {
            System.out.println("  剩余免费分钟: " + promo1.getRemainingMinutes());
            System.out.println("  已使用免费时段: " + promo1.getUsedFreeRanges());
        }

        // 第二次继续计算
        var request2 = createRequest("08:00", "15:00");
        request2.setExternalPromotions(List.of(freeMinutes, freeRange));
        request2.setPreviousCarryOver(result1.getCarryOver());
        var result2 = billingService.calculate(request2);

        System.out.println("\n第二次计算（CONTINUE）: 12:00 - 15:00 (3小时)");
        System.out.println("  结果金额: " + result2.getFinalAmount());
        System.out.println("  预期: 12:00-13:00免费(时段), 13:00-15:00收费");

        System.out.println();
    }

    /**
     * 测试：优惠优先级对结转的影响
     */
    static void testMixedPromotion_PriorityOrder() {
        System.out.println("=== 测试: 优惠优先级对结转的影响 ===\n");

        var billingService = createBillingService(false);

        // 高优先级免费时段
        var highPriorityRange = PromotionGrant.builder()
                .id("high-priority-range")
                .type(BConstants.PromotionType.FREE_RANGE)
                .priority(1)  // 高优先级
                .source(BConstants.PromotionSource.COUPON)
                .beginTime(parseTime("10:00"))
                .endTime(parseTime("12:00"))
                .build();

        // 低优先级免费分钟数
        var lowPriorityMinutes = PromotionGrant.builder()
                .id("low-priority-minutes")
                .type(BConstants.PromotionType.FREE_MINUTES)
                .priority(2)  // 低优先级
                .source(BConstants.PromotionSource.COUPON)
                .freeMinutes(60)
                .build();

        // 第一次计算
        var request1 = createRequest("08:00", "11:00");
        request1.setExternalPromotions(List.of(highPriorityRange, lowPriorityMinutes));
        var result1 = billingService.calculate(request1);

        System.out.println("第一次计算: 08:00 - 11:00");
        System.out.println("  高优先级: 免费时段 10:00-12:00");
        System.out.println("  低优先级: 60分钟免费");
        System.out.println("  结果金额: " + result1.getFinalAmount());
        System.out.println("  预期: 08:00-10:00使用免费分钟, 10:00-11:00使用免费时段");

        var promo1 = getPromotionCarryOver(result1);
        if (promo1 != null && promo1.getRemainingMinutes() != null) {
            System.out.println("  剩余免费分钟: " + promo1.getRemainingMinutes());
        }

        System.out.println();
    }

    // ==================== 结转状态验证测试 ====================

    /**
     * 测试：结转状态数据结构完整性
     */
    static void testCarryOverState_Structure() {
        System.out.println("=== 测试: 结转状态数据结构完整性 ===\n");

        var billingService = createBillingServiceWithRuleFreeMinutes(60);

        // 计算一次
        var request = createRequest("08:00", "09:30");
        var result = billingService.calculate(request);

        System.out.println("计算: 08:00 - 09:30 (90分钟)");
        System.out.println("  规则级别免费分钟: 60分钟");

        // 检查 carryOver 结构
        var carryOver = result.getCarryOver();
        System.out.println("\n=== carryOver 结构 ===");
        System.out.println("  calculatedUpTo: " + carryOver.getCalculatedUpTo());
        System.out.println("  segments: " + carryOver.getSegments().keySet());

        for (var entry : carryOver.getSegments().entrySet()) {
            System.out.println("\n  Segment: " + entry.getKey());
            var segmentCarryOver = entry.getValue();

            // 规则状态
            if (segmentCarryOver.getRuleState() != null) {
                System.out.println("    ruleState keys: " + segmentCarryOver.getRuleState().keySet());
            }

            // 优惠状态
            if (segmentCarryOver.getPromotionState() != null) {
                var promoState = segmentCarryOver.getPromotionState();
                System.out.println("    promotionState.remainingMinutes: " + promoState.getRemainingMinutes());
                System.out.println("    promotionState.usedFreeRanges: " + promoState.getUsedFreeRanges());
            }
        }

        System.out.println();
    }

    /**
     * 测试：无优惠时结转状态为空
     */
    static void testCarryOverState_NullWhenEmpty() {
        System.out.println("=== 测试: 无优惠时结转状态为空 ===\n");

        var billingService = createBillingService(false);

        // 无任何优惠
        var request = createRequest("08:00", "10:00");
        var result = billingService.calculate(request);

        System.out.println("计算: 08:00 - 10:00 (无优惠)");
        System.out.println("  结果金额: " + result.getFinalAmount());

        var promoCarryOver = getPromotionCarryOver(result);
        System.out.println("  promotionState: " + (promoCarryOver != null ? "存在" : "null"));

        if (promoCarryOver != null) {
            System.out.println("    remainingMinutes: " + promoCarryOver.getRemainingMinutes());
            System.out.println("    usedFreeRanges: " + promoCarryOver.getUsedFreeRanges());
        }

        System.out.println("  预期: 无优惠时 promotionState 字段为 null 或内容为空");

        System.out.println();
    }

    // ==================== 辅助方法 ====================

    static BillingService createBillingService(boolean withRuleFreeMinutes) {
        return createBillingServiceWithRuleFreeMinutes(withRuleFreeMinutes ? 30 : 0);
    }

    static BillingService createBillingServiceWithRuleFreeMinutes(int ruleFreeMinutes) {
        var billingConfigResolver = new BillingConfigResolver() {
            @Override
            public BConstants.BillingMode resolveBillingMode(String schemeId) {
                return BConstants.BillingMode.CONTINUOUS;
            }

            @Override
            public RuleConfig resolveChargingRule(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd) {
                List<RelativeTimePeriod> periods = List.of(
                        RelativeTimePeriod.builder()
                                .beginMinute(0)
                                .endMinute(1440)
                                .unitMinutes(30)
                                .unitPrice(new BigDecimal("2"))
                                .build()
                );

                return RelativeTimeConfig.builder()
                        .id("relative-time-1")
                        .periods(periods)
                        .maxChargeOneCycle(new BigDecimal("100"))
                        .build();
            }

            @Override
            public List<PromotionRuleConfig> resolvePromotionRules(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd) {
                if (ruleFreeMinutes > 0) {
                    return List.of(
                            new FreeMinutesPromotionConfig()
                                    .setId("rule-free-min")
                                    .setPriority(1)
                                    .setMinutes(ruleFreeMinutes)
                    );
                }
                return new ArrayList<>();
            }
        };

        return createBillingServiceFromResolver(billingConfigResolver);
    }

    static BillingService createBillingServiceFromResolver(BillingConfigResolver billingConfigResolver) {
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
        request.setSchemeId("scheme-test");
        request.setExternalPromotions(new ArrayList<>());
        return request;
    }

    static BillingRequest createRequestWithPromo(String begin, String end, PromotionGrant promo) {
        var request = createRequest(begin, end);
        request.setExternalPromotions(List.of(promo));
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

    static PromotionCarryOver getPromotionCarryOver(BillingResult result) {
        if (result.getCarryOver() == null || result.getCarryOver().getSegments() == null) {
            return null;
        }
        for (var segmentCarryOver : result.getCarryOver().getSegments().values()) {
            if (segmentCarryOver.getPromotionState() != null) {
                return segmentCarryOver.getPromotionState();
            }
        }
        return null;
    }
}