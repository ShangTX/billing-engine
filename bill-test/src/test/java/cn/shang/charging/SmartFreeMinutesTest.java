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
import cn.shang.charging.promotion.pojo.PromotionUsage;
import cn.shang.charging.promotion.rules.PromotionRuleRegistry;
import cn.shang.charging.settlement.ResultAssembler;
import cn.shang.charging.billing.PromotionEquivalentCalculator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SMART_FREE_MINUTES（智能免费分钟）专项测试（TODO-20260706-002 阶段5）。
 * <p>
 * 覆盖：
 * <ul>
 *   <li>GLOBAL 优先高价分配：高单价时段优先消费，金额/明细正确</li>
 *   <li>非 GLOBAL 报错（CONTINUOUS/UNIT_BASED/DURATION_PERIOD 遇 SMART_FREE_MINUTES 抛异常）</li>
 *   <li>同时存在 FREE_MINUTES + SMART_FREE_MINUTES：按 priority 排序，各自分配</li>
 *   <li>PromotionUsage.type 区分 FREE_MINUTES / SMART_FREE_MINUTES</li>
 * </ul>
 * 配置：dayNight 日段 8:00-20:00 单价 2 元/h，夜段单价 1 元/h，unitMinutes=60。
 */
class SmartFreeMinutesTest {

    /** GLOBAL 优先高价：窗口跨日夜，SMART 优先消费高价日段。 */
    @Test
    void globalMode_smartFreeMinutes_prefersHighPriceSegment() {
        DayNightConfig config = dayNightConfig("smart-high", new BigDecimal("1000.00"));
        BillingService service = createService(config, BConstants.CalculationMode.DURATION_GLOBAL);

        // 06:00-10:00：2h 夜段(1元/h) + 2h 日段(2元/h)
        // SMART_FREE_MINUTES=60min → 优先高价分配到日段 08:00-09:00（省 2 元）
        // 实收：2h夜×1 + 1h日×2 = 2 + 2 = 4 元
        BillingRequest req = request(
                LocalDateTime.of(2026, 1, 1, 6, 0),
                LocalDateTime.of(2026, 1, 1, 10, 0));
        req.setExternalPromotions(List.of(
                PromotionGrant.builder()
                        .id("smart-60")
                        .type(BConstants.PromotionType.SMART_FREE_MINUTES)
                        .freeMinutes(60)
                        .priority(1)
                        .build()
        ));

        BillingResult result = service.calculate(req);

        // 实收 4 元（对比 FREE_MINUTES 从窗口起点分配会落在夜段 06:00-07:00，实收 5 元）
        assertEquals(0, new BigDecimal("4.00").compareTo(result.getFinalAmount()),
                "SMART_FREE_MINUTES 应优先消费高价日段");

        // SMART 免费段落在日段 08:00-09:00
        List<DurationSegment> segs = result.getDurationSegments();
        assertNotNull(segs);
        DurationSegment smartFree = segs.stream()
                .filter(s -> s.chargedMinutes() == 0)
                .findFirst()
                .orElseThrow(() -> new AssertionError("未找到免费段"));
        assertEquals(LocalDateTime.of(2026, 1, 1, 8, 0), smartFree.beginTime());
        assertEquals(LocalDateTime.of(2026, 1, 1, 9, 0), smartFree.endTime());

        // SMART_FREE_MINUTES usage 记录
        PromotionUsage smartUsage = result.getPromotionUsages().stream()
                .filter(u -> u.getType() == BConstants.PromotionType.SMART_FREE_MINUTES)
                .findFirst()
                .orElseThrow(() -> new AssertionError("未找到 SMART_FREE_MINUTES usage"));
        assertEquals("smart-60", smartUsage.getPromotionId());
        assertEquals(60, smartUsage.getGrantedMinutes());
        assertEquals(60, smartUsage.getUsedMinutes());
        assertEquals(LocalDateTime.of(2026, 1, 1, 8, 0), smartUsage.getUsedFrom());
        assertEquals(LocalDateTime.of(2026, 1, 1, 9, 0), smartUsage.getUsedTo());
    }

    /** GLOBAL 优先高价：SMART 分钟数超过高价段容量，溢出到次高价段。 */
    @Test
    void globalMode_smartFreeMinutes_overflowsToLowerPriceSegment() {
        DayNightConfig config = dayNightConfig("smart-overflow", new BigDecimal("1000.00"));
        BillingService service = createService(config, BConstants.CalculationMode.DURATION_GLOBAL);

        // 06:00-10:00：2h 夜段(1元/h) + 2h 日段(2元/h)
        // SMART_FREE_MINUTES=180min → 日段 120min 全免 + 溢出 60min 到夜段 06:00-07:00
        // 实收：1h夜×1 = 1 元（夜段剩 06:00-07:00 收费，07:00-08:00 被 SMART 溢出消费）
        BillingRequest req = request(
                LocalDateTime.of(2026, 1, 1, 6, 0),
                LocalDateTime.of(2026, 1, 1, 10, 0));
        req.setExternalPromotions(List.of(
                PromotionGrant.builder()
                        .id("smart-180")
                        .type(BConstants.PromotionType.SMART_FREE_MINUTES)
                        .freeMinutes(180)
                        .priority(1)
                        .build()
        ));

        BillingResult result = service.calculate(req);

        // 日段全免(2h×2=4) + 夜段免 1h(剩 1h×1=1) = 实收 1 元
        assertEquals(0, new BigDecimal("1.00").compareTo(result.getFinalAmount()),
                "SMART 应先填满高价日段再溢出到夜段");

        // 免费段总分钟 = 180
        int freeMinutes = result.getDurationSegments().stream()
                .filter(s -> s.chargedMinutes() == 0)
                .mapToInt(s -> (int) java.time.Duration.between(s.beginTime(), s.endTime()).toMinutes())
                .sum();
        assertEquals(180, freeMinutes);
    }

    /** GLOBAL 全日段窗口：SMART 行为与 FREE_MINUTES 等价（同价无优先差异）。 */
    @Test
    void globalMode_smartFreeMinutes_homogeneousSameAsFreeMinutes() {
        DayNightConfig config = dayNightConfig("smart-homo", new BigDecimal("1000.00"));
        BillingService service = createService(config, BConstants.CalculationMode.DURATION_GLOBAL);

        // 10:00-14:00 全日段（4h=240min，2元/h），SMART=60min → 10:00-11:00 免费
        BillingRequest req = request(
                LocalDateTime.of(2026, 1, 1, 10, 0),
                LocalDateTime.of(2026, 1, 1, 14, 0));
        req.setExternalPromotions(List.of(
                PromotionGrant.builder()
                        .id("smart-60-homo")
                        .type(BConstants.PromotionType.SMART_FREE_MINUTES)
                        .freeMinutes(60)
                        .priority(1)
                        .build()
        ));

        BillingResult result = service.calculate(req);

        // 4h - 1h 免费 = 3h × 2 = 6 元
        assertEquals(0, new BigDecimal("6.00").compareTo(result.getFinalAmount()));

        // 免费段同质（chargedMinutes=0 或段时长）
        for (DurationSegment seg : result.getDurationSegments()) {
            int span = (int) java.time.Duration.between(seg.beginTime(), seg.endTime()).toMinutes();
            assertTrue(seg.chargedMinutes() == 0 || seg.chargedMinutes() == span,
                    "段非同质: " + seg.beginTime() + "-" + seg.endTime() + " charged=" + seg.chargedMinutes());
        }
    }

    /** 非 GLOBAL 模式遇 SMART_FREE_MINUTES 报错：CONTINUOUS。 */
    @Test
    void continuousMode_smartFreeMinutes_throws() {
        assertThrowsWithSmartFreeMinutes(BConstants.CalculationMode.CONTINUOUS);
    }

    /** 非 GLOBAL 模式遇 SMART_FREE_MINUTES 报错：UNIT_BASED。 */
    @Test
    void unitBasedMode_smartFreeMinutes_throws() {
        assertThrowsWithSmartFreeMinutes(BConstants.CalculationMode.UNIT_BASED);
    }

    /** 非 GLOBAL 模式遇 SMART_FREE_MINUTES 报错：DURATION_PERIOD。 */
    @Test
    void periodMode_smartFreeMinutes_throws() {
        assertThrowsWithSmartFreeMinutes(BConstants.CalculationMode.DURATION_PERIOD);
    }

    private void assertThrowsWithSmartFreeMinutes(BConstants.CalculationMode mode) {
        DayNightConfig config = dayNightConfig("smart-err-" + mode, new BigDecimal("1000.00"));
        BillingService service = createService(config, mode);

        BillingRequest req = request(
                LocalDateTime.of(2026, 1, 1, 8, 0),
                LocalDateTime.of(2026, 1, 1, 12, 0));
        req.setExternalPromotions(List.of(
                PromotionGrant.builder()
                        .id("smart-err")
                        .type(BConstants.PromotionType.SMART_FREE_MINUTES)
                        .freeMinutes(60)
                        .priority(1)
                        .build()
        ));

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> service.calculate(req));
        assertTrue(ex.getMessage().contains("SMART_FREE_MINUTES"),
                "异常消息应提及 SMART_FREE_MINUTES: " + ex.getMessage());
    }

    /** 同时存在 FREE_MINUTES + SMART_FREE_MINUTES：按 priority 排序，各自分配。 */
    @Test
    void globalMode_freeMinutesAndSmartFreeMinutes_coexist() {
        DayNightConfig config = dayNightConfig("smart-coexist", new BigDecimal("1000.00"));
        BillingService service = createService(config, BConstants.CalculationMode.DURATION_GLOBAL);

        // 06:00-10:00：2h 夜段(1元/h) + 2h 日段(2元/h)
        // FREE_MINUTES=60 priority=1（高优先）→ 从窗口起点 06:00-07:00 夜段消费
        // SMART_FREE_MINUTES=60 priority=2（低优先）→ 优先高价日段 08:00-09:00 消费
        // 实收：1h夜×1(07:00-08:00) + 1h日×2(09:00-10:00) = 1 + 2 = 3 元
        BillingRequest req = request(
                LocalDateTime.of(2026, 1, 1, 6, 0),
                LocalDateTime.of(2026, 1, 1, 10, 0));
        req.setExternalPromotions(List.of(
                PromotionGrant.builder()
                        .id("fm-60")
                        .type(BConstants.PromotionType.FREE_MINUTES)
                        .freeMinutes(60)
                        .priority(1)
                        .build(),
                PromotionGrant.builder()
                        .id("smart-60")
                        .type(BConstants.PromotionType.SMART_FREE_MINUTES)
                        .freeMinutes(60)
                        .priority(2)
                        .build()
        ));

        BillingResult result = service.calculate(req);

        // 实收 3 元
        assertEquals(0, new BigDecimal("3.00").compareTo(result.getFinalAmount()),
                "FREE_MINUTES + SMART_FREE_MINUTES 应各自分配");

        // 两种 usage 都存在，type 区分
        boolean hasFreeMinutesUsage = result.getPromotionUsages().stream()
                .anyMatch(u -> u.getType() == BConstants.PromotionType.FREE_MINUTES
                        && "fm-60".equals(u.getPromotionId()));
        boolean hasSmartUsage = result.getPromotionUsages().stream()
                .anyMatch(u -> u.getType() == BConstants.PromotionType.SMART_FREE_MINUTES
                        && "smart-60".equals(u.getPromotionId()));
        assertTrue(hasFreeMinutesUsage, "应有 FREE_MINUTES usage");
        assertTrue(hasSmartUsage, "应有 SMART_FREE_MINUTES usage");

        // 免费段总分钟 = 120（60 + 60）
        int totalFreeMinutes = result.getDurationSegments().stream()
                .filter(s -> s.chargedMinutes() == 0)
                .mapToInt(s -> (int) java.time.Duration.between(s.beginTime(), s.endTime()).toMinutes())
                .sum();
        assertEquals(120, totalFreeMinutes);
    }

    /** 同时存在且 SMART 优先级更高（priority 数字更小）：SMART 先分配高价日段，FREE_MINUTES 再从窗口起点。 */
    @Test
    void globalMode_smartHighPriorityThenFreeMinutes() {
        DayNightConfig config = dayNightConfig("smart-prio", new BigDecimal("1000.00"));
        BillingService service = createService(config, BConstants.CalculationMode.DURATION_GLOBAL);

        // 06:00-10:00：2h 夜段 + 2h 日段
        // SMART priority=1（高优先）→ 日段 08:00-09:00 消费 60min
        // FREE_MINUTES priority=2（低优先）→ 从窗口起点 06:00-07:00 夜段消费 60min
        // 实收：1h夜×1 + 1h日×2 = 3 元
        BillingRequest req = request(
                LocalDateTime.of(2026, 1, 1, 6, 0),
                LocalDateTime.of(2026, 1, 1, 10, 0));
        req.setExternalPromotions(List.of(
                PromotionGrant.builder()
                        .id("smart-60")
                        .type(BConstants.PromotionType.SMART_FREE_MINUTES)
                        .freeMinutes(60)
                        .priority(1)
                        .build(),
                PromotionGrant.builder()
                        .id("fm-60")
                        .type(BConstants.PromotionType.FREE_MINUTES)
                        .freeMinutes(60)
                        .priority(2)
                        .build()
        ));

        BillingResult result = service.calculate(req);

        // 实收 3 元（SMART 消费日段省 2，FREE_MINUTES 消费夜段省 1，总省 3；原 6 元 - 3 = 3 元）
        assertEquals(0, new BigDecimal("3.00").compareTo(result.getFinalAmount()));

        // SMART 落在日段 08:00-09:00（usedFrom/usedTo 由 DurationGlobalStrategy 记录）
        PromotionUsage smartUsage = result.getPromotionUsages().stream()
                .filter(u -> u.getType() == BConstants.PromotionType.SMART_FREE_MINUTES)
                .findFirst()
                .orElseThrow();
        assertEquals(LocalDateTime.of(2026, 1, 1, 8, 0), smartUsage.getUsedFrom());
        assertEquals(LocalDateTime.of(2026, 1, 1, 9, 0), smartUsage.getUsedTo());

        // FREE_MINUTES 落在夜段 06:00-07:00（从窗口起点消费；FreeMinuteAllocator 填 usedFrom/usedTo）
        PromotionUsage fmUsage = result.getPromotionUsages().stream()
                .filter(u -> u.getType() == BConstants.PromotionType.FREE_MINUTES)
                .findFirst()
                .orElseThrow();
        assertEquals(60, fmUsage.getUsedMinutes());
        assertEquals(LocalDateTime.of(2026, 1, 1, 6, 0), fmUsage.getUsedFrom());
        assertEquals(LocalDateTime.of(2026, 1, 1, 7, 0), fmUsage.getUsedTo());
    }

    /** SMART_FREE_MINUTES 与 FREE_RANGE 共存：SMART 跳过 FREE_RANGE 已占用时段。 */
    @Test
    void globalMode_smartFreeMinutesSkipsFreeRange() {
        DayNightConfig config = dayNightConfig("smart-skip", new BigDecimal("1000.00"));
        BillingService service = createService(config, BConstants.CalculationMode.DURATION_GLOBAL);

        // 06:00-10:00：2h 夜段(1元/h) + 2h 日段(2元/h)
        // FREE_RANGE 08:00-09:00 占用日段前 1h
        // SMART=60min → 日段剩余 09:00-10:00 消费（仍是高价段，跳过 FREE_RANGE 占用）
        // 实收：2h夜×1 + 0h日(08:00-09:00 FREE_RANGE + 09:00-10:00 SMART 全免) = 2 元
        BillingRequest req = request(
                LocalDateTime.of(2026, 1, 1, 6, 0),
                LocalDateTime.of(2026, 1, 1, 10, 0));
        req.setExternalPromotions(List.of(
                PromotionGrant.builder()
                        .id("range-08-09")
                        .type(BConstants.PromotionType.FREE_RANGE)
                        .beginTime(LocalDateTime.of(2026, 1, 1, 8, 0))
                        .endTime(LocalDateTime.of(2026, 1, 1, 9, 0))
                        .priority(1)
                        .build(),
                PromotionGrant.builder()
                        .id("smart-60")
                        .type(BConstants.PromotionType.SMART_FREE_MINUTES)
                        .freeMinutes(60)
                        .priority(1)
                        .build()
        ));

        BillingResult result = service.calculate(req);

        // 实收 2 元（仅 2h 夜段收费，日段全被 FREE_RANGE+SMART 覆盖）
        assertEquals(0, new BigDecimal("2.00").compareTo(result.getFinalAmount()),
                "SMART 应跳过 FREE_RANGE 占用，落在日段剩余部分");

        // SMART usage 落在 09:00-10:00
        PromotionUsage smartUsage = result.getPromotionUsages().stream()
                .filter(u -> u.getType() == BConstants.PromotionType.SMART_FREE_MINUTES)
                .findFirst()
                .orElseThrow();
        assertEquals(LocalDateTime.of(2026, 1, 1, 9, 0), smartUsage.getUsedFrom());
        assertEquals(LocalDateTime.of(2026, 1, 1, 10, 0), smartUsage.getUsedTo());
    }

    /** PromotionEquivalentCalculator 对 SMART_FREE_MINUTES 等效金额正确（消去法）。 */
    @Test
    void equivalentAmount_smartFreeMinutes_correct() {
        DayNightConfig config = dayNightConfig("smart-equiv", new BigDecimal("1000.00"));
        BillingService service = createService(config, BConstants.CalculationMode.DURATION_GLOBAL);
        PromotionEquivalentCalculator equivalentCalculator = new PromotionEquivalentCalculator(service);

        // 06:00-10:00：2h 夜段(1元/h) + 2h 日段(2元/h)
        // SMART=60min → 日段 08:00-09:00 消费（省 2 元）
        // baseline 实收 4 元，消去 SMART 后实收 6 元，等效 = 6 - 4 = 2 元
        BillingRequest req = request(
                LocalDateTime.of(2026, 1, 1, 6, 0),
                LocalDateTime.of(2026, 1, 1, 10, 0));
        req.setExternalPromotions(List.of(
                PromotionGrant.builder()
                        .id("smart-60")
                        .type(BConstants.PromotionType.SMART_FREE_MINUTES)
                        .freeMinutes(60)
                        .priority(1)
                        .build()
        ));

        Map<String, BigDecimal> equivalents = equivalentCalculator.calculate(req);

        assertEquals(1, equivalents.size());
        // SMART 落在日段 1h，单价 2 元/h，unitMinutes=60 → 等效 2 元
        assertEquals(0, new BigDecimal("2.00").compareTo(equivalents.get("smart-60")),
                "SMART_FREE_MINUTES 等效金额应为消费时段的原价");
    }

    /** PromotionEquivalentCalculator 同时存在 FREE_MINUTES + SMART_FREE_MINUTES 等效金额各自正确。 */
    @Test
    void equivalentAmount_freeMinutesAndSmartFreeMinutes_eachCorrect() {
        DayNightConfig config = dayNightConfig("smart-equiv-coexist", new BigDecimal("1000.00"));
        BillingService service = createService(config, BConstants.CalculationMode.DURATION_GLOBAL);
        PromotionEquivalentCalculator equivalentCalculator = new PromotionEquivalentCalculator(service);

        // 06:00-10:00：2h 夜段(1元/h) + 2h 日段(2元/h)
        // SMART priority=1 → 日段 08:00-09:00（省 2 元）
        // FREE_MINUTES priority=2 → 夜段 06:00-07:00（省 1 元）
        // baseline 实收 3 元
        BillingRequest req = request(
                LocalDateTime.of(2026, 1, 1, 6, 0),
                LocalDateTime.of(2026, 1, 1, 10, 0));
        req.setExternalPromotions(List.of(
                PromotionGrant.builder()
                        .id("smart-60")
                        .type(BConstants.PromotionType.SMART_FREE_MINUTES)
                        .freeMinutes(60)
                        .priority(1)
                        .build(),
                PromotionGrant.builder()
                        .id("fm-60")
                        .type(BConstants.PromotionType.FREE_MINUTES)
                        .freeMinutes(60)
                        .priority(2)
                        .build()
        ));

        Map<String, BigDecimal> equivalents = equivalentCalculator.calculate(req);

        assertEquals(2, equivalents.size());
        // SMART 落在日段，等效 2 元
        assertEquals(0, new BigDecimal("2.00").compareTo(equivalents.get("smart-60")),
                "SMART 等效 = 日段 1h 原价");
        // FREE_MINUTES 落在夜段，等效 1 元
        assertEquals(0, new BigDecimal("1.00").compareTo(equivalents.get("fm-60")),
                "FREE_MINUTES 等效 = 夜段 1h 原价");
    }

    // ==================== 辅助方法（与 DurationBillingModeTest 一致） ====================

    private DayNightConfig dayNightConfig(String id, BigDecimal maxCharge) {
        return DayNightConfig.builder()
                .id(id)
                .dayBeginMinute(8 * 60)
                .dayEndMinute(20 * 60)
                .dayUnitPrice(new BigDecimal("2.00"))
                .nightUnitPrice(new BigDecimal("1.00"))
                .maxChargeOneDay(maxCharge)
                .unitMinutes(60)
                .blockWeight(new BigDecimal("0.5"))
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

    private BillingService createService(DayNightConfig config, BConstants.CalculationMode calculationMode) {
        BillingConfigResolver resolver = new BillingConfigResolver() {
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
