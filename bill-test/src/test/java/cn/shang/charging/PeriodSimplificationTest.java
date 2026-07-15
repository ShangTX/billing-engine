package cn.shang.charging;

import cn.shang.charging.billing.BillingCalculator;
import cn.shang.charging.billing.BillingConfigResolver;
import cn.shang.charging.billing.BillingService;
import cn.shang.charging.billing.SegmentBuilder;
import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.BillingRequest;
import cn.shang.charging.billing.pojo.BillingResult;
import cn.shang.charging.billing.pojo.RuleConfig;
import cn.shang.charging.charge.rules.BillingRuleRegistry;
import cn.shang.charging.charge.rules.daynight.DayNightConfig;
import cn.shang.charging.promotion.FreeTimeRangeMerger;
import cn.shang.charging.promotion.PromotionEngine;
import cn.shang.charging.promotion.pojo.FreeTimeRangeType;
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
 * PERIOD 时长模式简化计算测试。
 * <p>
 * 验证简化路径（{@code DurationSupport.buildPeriodModeSimplified}）：
 * <ul>
 *   <li>无 bubble 长周期简化（与详细路径金额对账）</li>
 *   <li>有 bubble 长周期简化（effective 边界后移）</li>
 *   <li>短周期不简化（&lt; 阈值）</li>
 *   <li>FREE_MINUTES 存在时不简化</li>
 * </ul>
 * 场景统一：单价 3 元/时（全天），24h 周期封顶 50，threshold=2。
 */
class PeriodSimplificationTest {

    /** 无 bubble 4 周期：简化为 1 段，charged = 50×4 = 200。 */
    @Test
    void periodMode_simplification_noBubble_longCycle() {
        BillingService service = createService(2);

        BillingRequest req = request(
                LocalDateTime.of(2026, 1, 1, 0, 0),
                LocalDateTime.of(2026, 1, 5, 0, 0));

        BillingResult result = service.calculate(req);

        // 4 周期 > threshold(2) → 简化，charged = 50×4 = 200
        assertEquals(0, new BigDecimal("200").compareTo(result.getFinalAmount()));
        assertEquals(1, simplifiedCount(result));
    }

    /** 有 bubble 6h：effective 90h，简化 3 周期(150) + 尾部 18h(54→50) = 200。 */
    @Test
    void periodMode_simplification_withBubble() {
        BillingService service = createService(2);

        BillingRequest req = request(
                LocalDateTime.of(2026, 1, 1, 0, 0),
                LocalDateTime.of(2026, 1, 5, 0, 0));
        req.setExternalPromotions(List.of(freeRange("bubble",
                LocalDateTime.of(2026, 1, 1, 0, 0),
                LocalDateTime.of(2026, 1, 1, 6, 0),
                FreeTimeRangeType.BUBBLE)));

        BillingResult result = service.calculate(req);

        // bubble 6h 不计 effective：effOffset 0-90，简化 3 周期(150) + 尾部 18h(54→50) = 200
        assertEquals(0, new BigDecimal("200").compareTo(result.getFinalAmount()));
        assertEquals(1, simplifiedCount(result));
    }

    /** 短周期 1 周期：不足阈值，走详细，无简化段。 */
    @Test
    void periodMode_simplification_shortCycle_noSimplify() {
        BillingService service = createService(2);

        BillingRequest req = request(
                LocalDateTime.of(2026, 1, 1, 0, 0),
                LocalDateTime.of(2026, 1, 2, 0, 0));

        BillingResult result = service.calculate(req);

        // 1 周期 < threshold(2) → 不简化，24×3=72>50 封顶 50
        assertEquals(0, new BigDecimal("50").compareTo(result.getFinalAmount()));
        assertEquals(0, simplifiedCount(result));
    }

    /** FREE_MINUTES 存在：时段化后免费段参与 gaps，简化正确处理（与详细路径对账）。 */
    @Test
    void periodMode_simplification_freeMinutes_simplify() {
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

        // FREE_MINUTES 90min 时段化到 [1日0:00, 1日1:30]，gap [1日1:30, 5日0:00]
        // 简化 3 周期(150) + 头片段 22.5h(67.5→50) = 200，与详细路径（周期1 封顶50 + 周期2-4 各50）一致
        assertEquals(0, new BigDecimal("200").compareTo(result.getFinalAmount()));
        assertEquals(1, simplifiedCount(result));
    }

    /** 简化段金额正确：无 bubble 4 周期，简化段 chargedAmount = 200。 */
    @Test
    void periodMode_simplification_segmentAmount() {
        BillingService service = createService(2);

        BillingRequest req = request(
                LocalDateTime.of(2026, 1, 1, 0, 0),
                LocalDateTime.of(2026, 1, 5, 0, 0));

        BillingResult result = service.calculate(req);

        result.getDurationSegments().stream()
                .filter(ds -> "SIMPLIFIED".equals(ds.periodLabel()))
                .findFirst()
                .ifPresent(ds -> {
                    assertEquals(0, new BigDecimal("200").compareTo(ds.chargedAmount()));
                    assertEquals(4 * 1440, ds.chargedMinutes());
                });
    }

    // ==================== 辅助方法 ====================

    private long simplifiedCount(BillingResult result) {
        return result.getDurationSegments().stream()
                .filter(ds -> "SIMPLIFIED".equals(ds.periodLabel()))
                .count();
    }

    private DayNightConfig config() {
        return new DayNightConfig()
                .setId("dn-period-simpl")
                .setDayBeginMinute(0)
                .setDayEndMinute(1440)
                .setDayUnitPrice(new BigDecimal("3"))
                .setNightUnitPrice(new BigDecimal("3"))
                .setUnitMinutes(60)
                .setMaxChargeOneDay(new BigDecimal("50"))
                .setBlockWeight(new BigDecimal("0.5"));
    }

    private PromotionGrant freeRange(String id, LocalDateTime begin, LocalDateTime end, FreeTimeRangeType rangeType) {
        return PromotionGrant.builder()
                .id(id)
                .type(BConstants.PromotionType.FREE_RANGE)
                .source(BConstants.PromotionSource.COUPON)
                .priority(1)
                .rangeType(rangeType)
                .beginTime(begin)
                .endTime(end)
                .build();
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
        BillingConfigResolver resolver = new BillingConfigResolver() {
            @Override
            public BConstants.CalculationMode resolveCalculationMode(String schemeId, Map<String, Object> context) {
                return BConstants.CalculationMode.DURATION_PERIOD;
            }

            @Override
            public RuleConfig resolveChargingRule(String schemeId, LocalDateTime s, LocalDateTime e, Map<String, Object> context) {
                return config();
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
