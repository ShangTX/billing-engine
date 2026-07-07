package cn.shang.charging;

import cn.shang.charging.billing.BillingCalculator;
import cn.shang.charging.billing.BillingConfigResolver;
import cn.shang.charging.billing.BillingService;
import cn.shang.charging.billing.SegmentBuilder;
import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.BillingRequest;
import cn.shang.charging.billing.pojo.BillingResult;
import cn.shang.charging.billing.pojo.BillingUnit;
import cn.shang.charging.billing.pojo.RuleConfig;
import cn.shang.charging.billing.pojo.TimeRoundingMode;
import cn.shang.charging.charge.rules.BillingRuleRegistry;
import cn.shang.charging.charge.rules.daynight.DayNightConfig;
import cn.shang.charging.charge.rules.daynight.DayNightRule;
import cn.shang.charging.promotion.FreeTimeRangeMerger;
import cn.shang.charging.promotion.PromotionEngine;
import cn.shang.charging.promotion.pojo.PromotionGrant;
import cn.shang.charging.promotion.rules.PromotionRuleRegistry;
import cn.shang.charging.settlement.ResultAssembler;
import cn.shang.charging.wrapper.BillingTemplate;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 秒数取整测试：所有时间带秒数时，验证计费/优惠时间按既定原则对齐到分钟。
 * <p>
 * 原则：
 * <ul>
 *   <li>计费尽量短：beginTime 向上（ceil）、endTime 向下（truncate）= CEIL_BEGIN_TRUNCATE_END</li>
 *   <li>优惠尽量长：FREE_RANGE 的 begin 向下（truncate）、end 向上（ceil）</li>
 *   <li>-1 分钟守卫：取整后 beginTime >= endTime 时调到一致（计费 0）</li>
 * </ul>
 */
class SecondsRoundingTest {

    /** 计费时间带秒数 + 默认取整：beginTime ceil、endTime truncate。 */
    @Test
    void billingTimeSeconds_defaultRounding_ceilBeginTruncateEnd() {
        BillingTemplate template = createTemplate(BConstants.CalculationMode.CONTINUOUS);

        BillingRequest req = request(
                LocalDateTime.of(2026, 7, 7, 9, 0, 30),   // ceil → 09:01
                LocalDateTime.of(2026, 7, 7, 10, 30, 45)); // truncate → 10:30
        BillingResult result = template.calculate(req);

        // 取整后 [09:01, 10:30]：60min 单元 ×1 + 29min 截断（FULL_CHARGE）→ 2 + 2 = 4 元
        assertEquals(0, new BigDecimal("4.00").compareTo(result.getFinalAmount()));
        // 无 0 分钟单元
        assertNoZeroMinuteUnit(result);
    }

    /** 外部优惠 FREE_RANGE 带秒数：begin 向下、end 向上取整，不产生 0 分钟段。 */
    @Test
    void externalPromotionSeconds_rounded_promotionLonger() {
        BillingTemplate template = createTemplate(BConstants.CalculationMode.CONTINUOUS);

        BillingRequest req = request(
                LocalDateTime.of(2026, 7, 7, 9, 0, 30),    // ceil → 09:01
                LocalDateTime.of(2026, 7, 7, 12, 30, 45));  // truncate → 12:30
        req.setExternalPromotions(List.of(PromotionGrant.builder()
                .id("free-lunch-seconds")
                .type(BConstants.PromotionType.FREE_RANGE)
                .source(BConstants.PromotionSource.COUPON)
                .priority(1)
                .beginTime(LocalDateTime.of(2026, 7, 7, 11, 0, 15))  // truncate → 11:00
                .endTime(LocalDateTime.of(2026, 7, 7, 12, 0, 20))    // ceil → 12:01
                .build()));

        BillingResult result = template.calculate(req);

        // 取整后免费段 [11:00, 12:01]（61min，比原 60min5s 略长）。计费窗口 [09:01, 12:30]
        // 09:01-11:00 计费 + 11:00-12:01 免费 + 12:01-12:30 计费
        // 无 0 分钟段（修复前会产生 12:00:15—12:00:20 的 0 分钟段）
        assertNoZeroMinuteUnit(result);

        // 优惠使用记录的 usedFrom/usedTo 是取整后的时间
        var usage = result.getPromotionUsages().stream()
                .filter(u -> "free-lunch-seconds".equals(u.getPromotionId()))
                .findFirst().orElseThrow();
        assertEquals(LocalDateTime.of(2026, 7, 7, 11, 0, 0), usage.getUsedFrom());
        assertEquals(LocalDateTime.of(2026, 7, 7, 12, 1, 0), usage.getUsedTo());
    }

    /** -1 分钟守卫：begin/endTime 取整后倒置（ceil(begin) > truncate(end)）→ 调到一致，计费 0。 */
    @Test
    void negativeOneMinuteGuard_beginAfterEnd_zeroCharge() {
        BillingTemplate template = createTemplate(BConstants.CalculationMode.CONTINUOUS);

        // begin=10:31:30 ceil→10:32, end=10:31:50 truncate→10:31 → begin > end → 守卫调一致
        BillingRequest req = request(
                LocalDateTime.of(2026, 7, 7, 10, 31, 30),
                LocalDateTime.of(2026, 7, 7, 10, 31, 50));
        BillingResult result = template.calculate(req);

        // 计费 0（beginTime=endTime=10:31）
        assertEquals(0, BigDecimal.ZERO.compareTo(result.getFinalAmount()));
    }

    /** -1 分钟守卫：begin/endTime 取整后恰好相等（ceil(begin)=truncate(end)）→ 计费 0。 */
    @Test
    void zeroMinuteGuard_beginEqualsEnd_zeroCharge() {
        BillingTemplate template = createTemplate(BConstants.CalculationMode.CONTINUOUS);

        // begin=10:30:30 ceil→10:31, end=10:31:20 truncate→10:31 → begin=end=10:31 → 计费 0
        BillingRequest req = request(
                LocalDateTime.of(2026, 7, 7, 10, 30, 30),
                LocalDateTime.of(2026, 7, 7, 10, 31, 20));
        BillingResult result = template.calculate(req);

        assertEquals(0, BigDecimal.ZERO.compareTo(result.getFinalAmount()));
    }

    /** KEEP_SECONDS 模式下，计费时间不取整，但优惠时间仍按「优惠尽量长」取整。 */
    @Test
    void keepSeconds_promotionStillRounded() {
        BillingTemplate template = createTemplate(BConstants.CalculationMode.CONTINUOUS);

        BillingRequest req = request(
                LocalDateTime.of(2026, 7, 7, 9, 0, 0),
                LocalDateTime.of(2026, 7, 7, 12, 0, 0));
        req.setTimeRoundingMode(TimeRoundingMode.KEEP_SECONDS);
        req.setExternalPromotions(List.of(PromotionGrant.builder()
                .id("free-seconds")
                .type(BConstants.PromotionType.FREE_RANGE)
                .source(BConstants.PromotionSource.COUPON)
                .priority(1)
                .beginTime(LocalDateTime.of(2026, 7, 7, 10, 0, 15))  // truncate → 10:00
                .endTime(LocalDateTime.of(2026, 7, 7, 11, 0, 20))    // ceil → 11:01
                .build()));

        BillingResult result = template.calculate(req);

        // 优惠时间被取整（独立于 KEEP_SECONDS）：usedFrom=10:00, usedTo=11:01
        var usage = result.getPromotionUsages().stream()
                .filter(u -> "free-seconds".equals(u.getPromotionId()))
                .findFirst().orElseThrow();
        assertEquals(LocalDateTime.of(2026, 7, 7, 10, 0, 0), usage.getUsedFrom());
        assertEquals(LocalDateTime.of(2026, 7, 7, 11, 1, 0), usage.getUsedTo());
    }

    // ==================== 辅助方法 ====================

    private void assertNoZeroMinuteUnit(BillingResult result) {
        assertNotNull(result.getUnits());
        for (BillingUnit u : result.getUnits()) {
            assertTrue(u.getDurationMinutes() > 0,
                    "存在 0 分钟单元: " + u.getBeginTime() + "—" + u.getEndTime());
        }
    }

    private BillingRequest request(LocalDateTime begin, LocalDateTime end) {
        BillingRequest r = new BillingRequest();
        r.setBeginTime(begin);
        r.setEndTime(end);
        r.setSchemeId("scheme-1");
        r.setSchemeChanges(List.of());
        r.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SINGLE);
        r.setExternalPromotions(List.of());
        return r;
    }

    private BillingTemplate createTemplate(BConstants.CalculationMode mode) {
        DayNightConfig config = new DayNightConfig()
                .setId("dn-seconds")
                .setDayBeginMinute(8 * 60)
                .setDayEndMinute(20 * 60)
                .setDayUnitPrice(new BigDecimal("2"))
                .setNightUnitPrice(new BigDecimal("1"))
                .setUnitMinutes(60)
                .setMaxChargeOneDay(new BigDecimal("100"))
                .setBlockWeight(new BigDecimal("0.5"));

        BillingConfigResolver resolver = new BillingConfigResolver() {
            @Override
            public BConstants.CalculationMode resolveCalculationMode(String schemeId, Map<String, Object> context) {
                return mode;
            }

            @Override
            public RuleConfig resolveChargingRule(String schemeId, LocalDateTime s, LocalDateTime e, Map<String, Object> context) {
                return config;
            }

            @Override
            public List<cn.shang.charging.billing.pojo.PromotionRuleConfig> resolvePromotionRules(
                    String schemeId, LocalDateTime s, LocalDateTime e, Map<String, Object> context) {
                return List.of();
            }
        };

        BillingRuleRegistry ruleRegistry = new BillingRuleRegistry();
        PromotionEngine promotionEngine = new PromotionEngine(resolver, new FreeTimeRangeMerger(), new PromotionRuleRegistry());
        BillingService billingService = new BillingService(
                new SegmentBuilder(), resolver, promotionEngine,
                new BillingCalculator(ruleRegistry), new ResultAssembler());
        return new BillingTemplate(billingService, resolver);
    }
}
