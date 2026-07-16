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
import cn.shang.charging.wrapper.TimeRounding;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 秒数取整测试：BillingTemplate 在调用 core 前执行接入层时间归一化。
 * <p>
 * 原则：
 * <ul>
 *   <li>默认 {@code TRUNCATE_BOTH} 保持统一向下取整</li>
 *   <li>{@code CEIL_BEGIN_TRUNCATE_END} 收窄计费时间，并放宽外部 FREE_RANGE 优惠时间段</li>
 *   <li>同一分钟内的 begin/end 会统一向下取整，避免 begin &gt; end 倒置</li>
 * </ul>
 */
class SecondsRoundingTest {

    /** 计费时间带秒数：begin/end 统一向下取整。 */
    @Test
    void billingTimeSeconds_unifiedTruncate() {
        BillingTemplate template = createTemplate(BConstants.CalculationMode.CONTINUOUS);

        BillingRequest req = request(
                LocalDateTime.of(2026, 7, 7, 9, 0, 30),    // truncate → 09:00
                LocalDateTime.of(2026, 7, 7, 10, 30, 45)); // truncate → 10:30
        BillingResult result = template.calculate(req);

        // 窗口 [09:00, 10:30] = 90min：60min 单元 + 30min 截断（FULL_CHARGE）→ 2 + 2 = 4 元
        assertEquals(0, new BigDecimal("4.00").compareTo(result.getFinalAmount()));
        assertNoZeroMinuteUnit(result);
    }

    /** 外部优惠 FREE_RANGE 带秒数：统一向下取整，不产生 0 分钟段。 */
    @Test
    void externalPromotionSeconds_unifiedTruncate() {
        BillingTemplate template = createTemplate(BConstants.CalculationMode.CONTINUOUS);

        BillingRequest req = request(
                LocalDateTime.of(2026, 7, 7, 9, 0, 30),    // → 09:00
                LocalDateTime.of(2026, 7, 7, 12, 30, 45));  // → 12:30
        req.setExternalPromotions(List.of(PromotionGrant.builder()
                .id("free-lunch-seconds")
                .type(BConstants.PromotionType.FREE_RANGE)
                .source(BConstants.PromotionSource.COUPON)
                .priority(1)
                .beginTime(LocalDateTime.of(2026, 7, 7, 11, 0, 15))  // → 11:00
                .endTime(LocalDateTime.of(2026, 7, 7, 12, 0, 20))    // → 12:00
                .build()));

        BillingResult result = template.calculate(req);

        // 窗口 [09:00, 12:30]，免费段 [11:00, 12:00]
        // 09:00-10:00=2 + 10:00-11:00=2 + 11:00-12:00免费 + 12:00-12:30截断=2 → 6 元
        assertEquals(0, new BigDecimal("6.00").compareTo(result.getFinalAmount()));
        assertNoZeroMinuteUnit(result);

        // 优惠使用记录的 usedFrom/usedTo 是向下取整后的时间
        var usage = result.getPromotionUsages().stream()
                .filter(u -> "free-lunch-seconds".equals(u.getPromotionId()))
                .findFirst().orElseThrow();
        assertEquals(LocalDateTime.of(2026, 7, 7, 11, 0, 0), usage.getUsedFrom());
        assertEquals(LocalDateTime.of(2026, 7, 7, 12, 0, 0), usage.getUsedTo());
    }

    /** CEIL_BEGIN_TRUNCATE_END 会把外部 FREE_RANGE 的结束时间向上取整。 */
    @Test
    void ceilBeginTruncateEnd_widensExternalPromotionDuringCalculate() {
        BillingTemplate template = createTemplate(BConstants.CalculationMode.CONTINUOUS);

        BillingRequest req = request(
                LocalDateTime.of(2026, 7, 7, 9, 0, 0),
                LocalDateTime.of(2026, 7, 7, 12, 1, 0));
        req.setTimeRoundingMode(TimeRoundingMode.CEIL_BEGIN_TRUNCATE_END);
        req.setExternalPromotions(List.of(PromotionGrant.builder()
                .id("free-lunch-wide")
                .type(BConstants.PromotionType.FREE_RANGE)
                .source(BConstants.PromotionSource.COUPON)
                .priority(1)
                .beginTime(LocalDateTime.of(2026, 7, 7, 11, 0, 15))
                .endTime(LocalDateTime.of(2026, 7, 7, 12, 0, 20))
                .build()));

        BillingResult result = template.calculate(req);

        assertEquals(0, new BigDecimal("4.00").compareTo(result.getFinalAmount()));
        var usage = result.getPromotionUsages().stream()
                .filter(u -> "free-lunch-wide".equals(u.getPromotionId()))
                .findFirst().orElseThrow();
        assertEquals(LocalDateTime.of(2026, 7, 7, 11, 0, 0), usage.getUsedFrom());
        assertEquals(LocalDateTime.of(2026, 7, 7, 12, 1, 0), usage.getUsedTo());
    }

    @Test
    void ceilBeginTruncateEnd_sameMinute_zeroCharge() {
        BillingTemplate template = createTemplate(BConstants.CalculationMode.CONTINUOUS);

        BillingRequest req = request(
                LocalDateTime.of(2026, 7, 7, 10, 30, 30),
                LocalDateTime.of(2026, 7, 7, 10, 30, 50));
        req.setTimeRoundingMode(TimeRoundingMode.CEIL_BEGIN_TRUNCATE_END);

        BillingResult result = template.calculate(req);

        assertEquals(0, BigDecimal.ZERO.compareTo(result.getFinalAmount()));
    }

    /** begin/endTime 在同一分钟内：向下取整后相等，计费 0。 */
    @Test
    void sameMinute_zeroCharge() {
        BillingTemplate template = createTemplate(BConstants.CalculationMode.CONTINUOUS);

        // begin=10:30:30 → 10:30, end=10:30:50 → 10:30，begin=end → 计费 0
        BillingRequest req = request(
                LocalDateTime.of(2026, 7, 7, 10, 30, 30),
                LocalDateTime.of(2026, 7, 7, 10, 30, 50));
        BillingResult result = template.calculate(req);

        assertEquals(0, BigDecimal.ZERO.compareTo(result.getFinalAmount()));
    }

    /** KEEP_SECONDS 是兼容旧值，BillingTemplate 当前按 TRUNCATE_BOTH 处理。 */
    @Test
    void keepSeconds_ignored_stillTruncated() {
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
                .beginTime(LocalDateTime.of(2026, 7, 7, 10, 0, 15))  // → 10:00
                .endTime(LocalDateTime.of(2026, 7, 7, 11, 0, 20))    // → 11:00
                .build()));

        BillingResult result = template.calculate(req);

        // 优惠时间被向下取整（KEEP_SECONDS 按 TRUNCATE_BOTH 处理）
        var usage = result.getPromotionUsages().stream()
                .filter(u -> "free-seconds".equals(u.getPromotionId()))
                .findFirst().orElseThrow();
        assertEquals(LocalDateTime.of(2026, 7, 7, 10, 0, 0), usage.getUsedFrom());
        assertEquals(LocalDateTime.of(2026, 7, 7, 11, 0, 0), usage.getUsedTo());
    }

    /** 外部预处理链路：外部 ceil(begin) 后传入，默认归一化不改变外部意图。 */
    @Test
    void externalPreprocess_ceilThenEngineTruncate_unchanged() {
        BillingTemplate template = createTemplate(BConstants.CalculationMode.CONTINUOUS);

        // 外部想「进场多算」：自行 ceil(begin)
        LocalDateTime rawBegin = LocalDateTime.of(2026, 7, 7, 9, 0, 30);
        LocalDateTime preprocessedBegin = TimeRounding.ceil(rawBegin);  // → 09:01
        BillingRequest req = request(preprocessedBegin,
                LocalDateTime.of(2026, 7, 7, 10, 30, 45));  // 默认归一化 → 10:30
        BillingResult result = template.calculate(req);

        // 默认归一化不会改变 preprocessedBegin（已对齐到分钟）→ 窗口 [09:01, 10:30] = 89min
        // 60min 单元 + 29min 截断（FULL_CHARGE）→ 2 + 2 = 4 元
        assertEquals(0, new BigDecimal("4.00").compareTo(result.getFinalAmount()));
        // 第一个单元从 09:01 开始（外部 ceil 意图保留）
        assertEquals(LocalDateTime.of(2026, 7, 7, 9, 1, 0), result.getUnits().get(0).getBeginTime());
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
