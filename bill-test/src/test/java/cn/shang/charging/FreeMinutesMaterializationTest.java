package cn.shang.charging;

import cn.shang.charging.billing.BillingCalculator;
import cn.shang.charging.billing.BillingConfigResolver;
import cn.shang.charging.billing.BillingService;
import cn.shang.charging.billing.SegmentBuilder;
import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.BillingRequest;
import cn.shang.charging.billing.pojo.BillingResult;
import cn.shang.charging.billing.pojo.DurationSegment;
import cn.shang.charging.billing.pojo.PromotionRuleConfig;
import cn.shang.charging.billing.pojo.RuleConfig;
import cn.shang.charging.charge.rules.BillingRuleRegistry;
import cn.shang.charging.charge.rules.daynight.DayNightConfig;
import cn.shang.charging.charge.rules.daynight.DayNightRule;
import cn.shang.charging.promotion.FreeTimeRangeMerger;
import cn.shang.charging.promotion.PromotionEngine;
import cn.shang.charging.promotion.pojo.PromotionGrant;
import cn.shang.charging.promotion.rules.PromotionRuleRegistry;
import cn.shang.charging.settlement.ResultAssembler;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FREE_MINUTES 时段化下放到策略侧测试（TODO-20260702-004）。
 * <p>
 * 验证：
 * <ul>
 *   <li>GLOBAL 不时段化，按分钟扣减 chargedMinutes，finalAmount 与时段化路径等价</li>
 *   <li>PERIOD / CONTINUOUS 时段化 FREE_MINUTES，行为与现状一致</li>
 *   <li>PromotionEngine 产出中间形式（freeMinutesList），不再集中时段化</li>
 * </ul>
 */
class FreeMinutesMaterializationTest {

    /** GLOBAL：FREE_MINUTES 在单一日段内扣减，与时段化路径等价。 8:00-12:00 日段 4h=8 元，60 分钟免费 → 6 元。 */
    @Test
    void global_freeMinutes_singleDaySegment() {
        DayNightConfig config = dayNightConfig(new BigDecimal("100.00"));
        BillingService service = createService(config, BConstants.CalculationMode.DURATION_GLOBAL);

        BillingRequest req = request(LocalDateTime.of(2026, 1, 1, 8, 0),
                LocalDateTime.of(2026, 1, 1, 12, 0));
        req.setExternalPromotions(List.of(freeMinutes("fm-60", 60, 1)));

        BillingResult result = service.calculate(req);
        // 8 元 - 1h(2 元) = 6 元
        assertEquals(0, new BigDecimal("6.00").compareTo(result.getFinalAmount()));
        // FREE_MINUTES usage 产出（usedMinutes=60）
        assertTrue(result.getPromotionUsages().stream()
                .anyMatch(u -> u.getType() == BConstants.PromotionType.FREE_MINUTES
                        && u.getUsedMinutes() == 60));
    }

    /** GLOBAL：FREE_MINUTES 跨日夜段扣减。 18:00-22:00 = 2h 日(4)+2h 夜(2)=6 元，90 分钟免费 → 3 元。 */
    @Test
    void global_freeMinutes_crossDayNightSegment() {
        DayNightConfig config = dayNightConfig(new BigDecimal("100.00"));
        BillingService service = createService(config, BConstants.CalculationMode.DURATION_GLOBAL);

        BillingRequest req = request(LocalDateTime.of(2026, 1, 1, 18, 0),
                LocalDateTime.of(2026, 1, 1, 22, 0));
        req.setExternalPromotions(List.of(freeMinutes("fm-90", 90, 1)));

        BillingResult result = service.calculate(req);
        // 90 分钟从 18:00 起：18:00-19:30 日段(2/h)=3 元免费。6-3=3 元
        assertEquals(0, new BigDecimal("3.00").compareTo(result.getFinalAmount()));
        assertTrue(result.getPromotionUsages().stream()
                .anyMatch(u -> u.getType() == BConstants.PromotionType.FREE_MINUTES
                        && u.getUsedMinutes() == 90));
    }

    /** GLOBAL：FREE_MINUTES 完全覆盖第一段并部分扣减第二段。 18:00-22:00，150 分钟免费 → 1.5 元。 */
    @Test
    void global_freeMinutes_fullyCoverFirstSegment() {
        DayNightConfig config = dayNightConfig(new BigDecimal("100.00"));
        BillingService service = createService(config, BConstants.CalculationMode.DURATION_GLOBAL);

        BillingRequest req = request(LocalDateTime.of(2026, 1, 1, 18, 0),
                LocalDateTime.of(2026, 1, 1, 22, 0));
        req.setExternalPromotions(List.of(freeMinutes("fm-150", 150, 1)));

        BillingResult result = service.calculate(req);
        // 150 分钟：120 日段(4 元)全免 + 30 夜段(0.5 元)免。6-4.5=1.5 元
        assertEquals(0, new BigDecimal("1.50").compareTo(result.getFinalAmount()));
        // 验证 DurationSegment：第一段 chargedMinutes=0，第二段 chargedMinutes=90
        List<DurationSegment> segs = result.getDurationSegments();
        assertNotNull(segs);
        assertTrue(segs.size() >= 2);
        DurationSegment first = segs.get(0);
        assertEquals(0, first.chargedMinutes()); // 日段全免
    }

    /** PERIOD：FREE_MINUTES 时段化，行为与现状一致。 8:00-12:00，60 分钟免费 → 6 元。 */
    @Test
    void period_freeMinutes_materialized() {
        DayNightConfig config = dayNightConfig(new BigDecimal("100.00"));
        BillingService service = createService(config, BConstants.CalculationMode.DURATION_PERIOD);

        BillingRequest req = request(LocalDateTime.of(2026, 1, 1, 8, 0),
                LocalDateTime.of(2026, 1, 1, 12, 0));
        req.setExternalPromotions(List.of(freeMinutes("fm-60", 60, 1)));

        BillingResult result = service.calculate(req);
        // 时段化：60 分钟落在 8:00-9:00 日段，免费 2 元。8-2=6 元
        assertEquals(0, new BigDecimal("6.00").compareTo(result.getFinalAmount()));
        assertTrue(result.getPromotionUsages().stream()
                .anyMatch(u -> u.getType() == BConstants.PromotionType.FREE_MINUTES
                        && u.getUsedMinutes() == 60));
    }

    /** CONTINUOUS：FREE_MINUTES 时段化经 RuleSupport.materializeFreeMinutes，行为不变。 */
    @Test
    void continuous_freeMinutes_materialized() {
        DayNightConfig config = dayNightConfig(new BigDecimal("100.00"));
        BillingService service = createService(config, null); // DurationMode=null → CONTINUOUS 路径

        BillingRequest req = request(LocalDateTime.of(2026, 1, 1, 8, 0),
                LocalDateTime.of(2026, 1, 1, 12, 0));
        req.setExternalPromotions(List.of(freeMinutes("fm-60", 60, 1)));

        BillingResult result = service.calculate(req);
        // CONTINUOUS：60 分钟免费段覆盖首个单元，8:00-9:00 免费。3 单元收费 × 2 = 6 元
        assertEquals(0, new BigDecimal("6.00").compareTo(result.getFinalAmount()));
        assertTrue(result.getPromotionUsages().stream()
                .anyMatch(u -> u.getType() == BConstants.PromotionType.FREE_MINUTES
                        && u.getUsedMinutes() == 60));
    }

    /** 中间形式：PromotionEngine 产出 freeMinutesList（未时段化），FREE_MINUTES 不混入 freeTimeRanges。 */
    @Test
    void promotionEngine_producesIntermediateForm() {
        DayNightConfig config = dayNightConfig(new BigDecimal("100.00"));
        BillingConfigResolver resolver = resolver(config, BConstants.CalculationMode.DURATION_GLOBAL);
        PromotionEngine engine = new PromotionEngine(resolver, new FreeTimeRangeMerger(), new PromotionRuleRegistry());

        cn.shang.charging.billing.pojo.CalculationWindow window =
                new cn.shang.charging.billing.pojo.CalculationWindow();
        window.setCalculationBegin(LocalDateTime.of(2026, 1, 1, 8, 0));
        window.setCalculationEnd(LocalDateTime.of(2026, 1, 1, 12, 0));
        cn.shang.charging.billing.pojo.BillingContext context = cn.shang.charging.billing.pojo.BillingContext.builder()
                .beginTime(LocalDateTime.of(2026, 1, 1, 8, 0))
                .endTime(LocalDateTime.of(2026, 1, 1, 12, 0))
                .window(window)
                .externalPromotions(List.of(freeMinutes("fm-60", 60, 1)))
                .promotionRules(List.of())
                .build();
        var aggregate = engine.evaluate(context);

        // freeMinutesList 非空（未时段化）
        assertNotNull(aggregate.getFreeMinutesList());
        assertEquals(1, aggregate.getFreeMinutesList().size());
        assertEquals(60, aggregate.getFreeMinutesList().get(0).getMinutes());
        // freeTimeRanges 不含 FREE_MINUTES（仅 FREE_RANGE，这里无）
        assertTrue(aggregate.getFreeTimeRanges() == null || aggregate.getFreeTimeRanges().isEmpty());
        // usages / promotionCarryOver 不再由 PromotionEngine 填充
    }

    // ==================== 辅助方法 ====================

    private DayNightConfig dayNightConfig(BigDecimal maxCharge) {
        return DayNightConfig.builder()
                .id("fm-mat")
                .dayBeginMinute(8 * 60)
                .dayEndMinute(20 * 60)
                .dayUnitPrice(new BigDecimal("2.00"))
                .nightUnitPrice(new BigDecimal("1.00"))
                .maxChargeOneDay(maxCharge)
                .unitMinutes(60)
                .blockWeight(new BigDecimal("0.5"))
                .build();
    }

    private PromotionGrant freeMinutes(String id, int minutes, int priority) {
        return PromotionGrant.builder()
                .id(id)
                .type(BConstants.PromotionType.FREE_MINUTES)
                .freeMinutes(minutes)
                .priority(priority)
                .build();
    }

    private BillingRequest request(LocalDateTime begin, LocalDateTime end) {
        BillingRequest r = new BillingRequest();
        r.setBeginTime(begin);
        r.setEndTime(end);
        r.setSchemeId("scheme-1");
        r.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SINGLE);
        r.setSchemeChanges(List.of());
        r.setExternalPromotions(List.of());
        return r;
    }

    private BillingConfigResolver resolver(DayNightConfig config, BConstants.CalculationMode calculationMode) {
        return new BillingConfigResolver() {
            @Override
            public RuleConfig resolveChargingRule(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd, Map<String, Object> context) {
                return config;
            }

            @Override
            public List<PromotionRuleConfig> resolvePromotionRules(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd, Map<String, Object> context) {
                return List.of();
            }

            @Override
            public BConstants.CalculationMode resolveCalculationMode(String schemeId, Map<String, Object> context) {
                return calculationMode;
            }
        };
    }

    private BillingService createService(DayNightConfig config, BConstants.CalculationMode calculationMode) {
        BillingConfigResolver resolver = resolver(config, calculationMode);
        BillingRuleRegistry ruleRegistry = new BillingRuleRegistry();
        ruleRegistry.register(BConstants.ChargeRuleType.DAY_NIGHT, new DayNightRule());

        return new BillingService(
                new SegmentBuilder(),
                resolver,
                new PromotionEngine(resolver, new FreeTimeRangeMerger(), new PromotionRuleRegistry()),
                new BillingCalculator(ruleRegistry),
                new ResultAssembler()
        );
    }
}
