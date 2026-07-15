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
import cn.shang.charging.charge.rules.BillingRuleRegistry;
import cn.shang.charging.charge.rules.daynight.DayNightConfig;
import cn.shang.charging.promotion.FreeTimeRangeMerger;
import cn.shang.charging.promotion.PromotionEngine;
import cn.shang.charging.promotion.pojo.PromotionGrant;
import cn.shang.charging.promotion.rules.PromotionRuleRegistry;
import cn.shang.charging.promotion.rules.minutes.FreeMinutesPromotionRule;
import cn.shang.charging.settlement.ResultAssembler;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * CONTINUOUS 模式简化计算测试（含 FREE_MINUTES 放宽验证）。
 * <p>
 * 验证 CONTINUOUS 简化在 FREE_MINUTES 存在时也能正确处理：
 * FREE_MINUTES 时段化后免费段参与 gaps，简化区间正确，金额与详细路径对账。
 */
class ContinuousSimplificationTest {

    /**
     * CONTINUOUS + FREE_MINUTES：时段化后免费段参与 gaps，简化正确处理。
     * <p>
     * 4 周期（1日0:00-5日0:00），FREE_MINUTES 90min 时段化到 [1日0:00, 1日1:30]，
     * gap [1日1:30, 5日0:00]，简化 3 周期(150) + 头片段 22.5h(67.5→50) = 200。
     */
    @Test
    void continuousMode_simplification_freeMinutes_simplify() {
        BillingService service = createService(2);

        BillingRequest req = request(
                LocalDateTime.of(2026, 1, 1, 0, 0),
                LocalDateTime.of(2026, 1, 5, 0, 0));
        req.setExternalPromotions(List.of(PromotionGrant.builder()
                .id("free-90min")
                .type(BConstants.PromotionType.FREE_MINUTES)
                .source(BConstants.PromotionSource.COUPON)
                .freeMinutes(90)
                .priority(1)
                .build()));

        BillingResult result = service.calculate(req);

        // 简化 3 周期(150) + 头片段 22.5h(67.5→50) = 200，与详细路径（周期1 封顶50 + 周期2-4 各50）一致
        assertEquals(0, new BigDecimal("200").compareTo(result.getFinalAmount()));
        assertEquals(1, simplifiedUnitCount(result));
    }

    /** CONTINUOUS 无优惠长周期简化（基准对账）。 */
    @Test
    void continuousMode_simplification_noPromotion_longCycle() {
        BillingService service = createService(2);

        BillingRequest req = request(
                LocalDateTime.of(2026, 1, 1, 0, 0),
                LocalDateTime.of(2026, 1, 5, 0, 0));

        BillingResult result = service.calculate(req);

        // 4 周期 > threshold(2) → 简化为 1 单元，charged = 50×4 = 200
        assertEquals(0, new BigDecimal("200").compareTo(result.getFinalAmount()));
        assertEquals(1, simplifiedUnitCount(result));
    }

    /** 完整周期原价未达到日封顶时，不能用 maxChargeOneDay × cycleCount 简化。 */
    @Test
    void continuousMode_simplification_disabledWhenFullCycleDoesNotReachCap() {
        BillingService service = createService(2, nonCappingConfig());

        BillingRequest req = request(
                LocalDateTime.of(2026, 1, 1, 0, 0),
                LocalDateTime.of(2026, 1, 5, 0, 0));

        BillingResult result = service.calculate(req);

        // 4 天 × 24h × 1 元/h = 96，低于每日封顶 50×4，不能简化成 200。
        assertEquals(0, new BigDecimal("96.00").compareTo(result.getFinalAmount()));
        assertEquals(0, simplifiedUnitCount(result));
    }

    // ==================== 辅助方法 ====================

    private long simplifiedUnitCount(BillingResult result) {
        return result.getUnits().stream()
                .filter(u -> {
                    Object rd = u.getRuleData();
                    return rd instanceof Map && Boolean.TRUE.equals(((Map<?, ?>) rd).get("isSimplified"));
                })
                .count();
    }

    private DayNightConfig config() {
        return new DayNightConfig()
                .setId("dn-continuous-simpl")
                .setDayBeginMinute(0)
                .setDayEndMinute(1440)
                .setDayUnitPrice(new BigDecimal("3"))
                .setNightUnitPrice(new BigDecimal("3"))
                .setUnitMinutes(60)
                .setMaxChargeOneDay(new BigDecimal("50"))
                .setBlockWeight(new BigDecimal("0.5"));
    }

    private DayNightConfig nonCappingConfig() {
        return new DayNightConfig()
                .setId("dn-continuous-no-cap-simpl")
                .setDayBeginMinute(0)
                .setDayEndMinute(1440)
                .setDayUnitPrice(new BigDecimal("1"))
                .setNightUnitPrice(new BigDecimal("1"))
                .setUnitMinutes(60)
                .setMaxChargeOneDay(new BigDecimal("50"))
                .setBlockWeight(new BigDecimal("0.5"));
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

    private BillingService createService(int threshold) {
        return createService(threshold, config());
    }

    private BillingService createService(int threshold, DayNightConfig config) {
        BillingConfigResolver resolver = new BillingConfigResolver() {
            @Override
            public BConstants.CalculationMode resolveCalculationMode(String schemeId, Map<String, Object> context) {
                return BConstants.CalculationMode.CONTINUOUS;
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

            @Override
            public int getSimplifiedCycleThreshold() {
                return threshold;
            }
        };
        BillingRuleRegistry ruleRegistry = new BillingRuleRegistry();
        PromotionRuleRegistry promotionRuleRegistry = new PromotionRuleRegistry();
        promotionRuleRegistry.register(BConstants.PromotionRuleType.FREE_MINUTES, new FreeMinutesPromotionRule());
        PromotionEngine promotionEngine = new PromotionEngine(resolver, new FreeTimeRangeMerger(), promotionRuleRegistry);
        return new BillingService(
                new SegmentBuilder(), resolver, promotionEngine,
                new BillingCalculator(ruleRegistry), new ResultAssembler());
    }
}
