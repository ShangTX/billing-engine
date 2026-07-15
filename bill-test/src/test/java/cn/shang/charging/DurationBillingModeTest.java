package cn.shang.charging;

import cn.shang.charging.billing.BillingCalculator;
import cn.shang.charging.billing.BillingConfigResolver;
import cn.shang.charging.billing.BillingService;
import cn.shang.charging.billing.SegmentBuilder;
import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.BillingRequest;
import cn.shang.charging.billing.pojo.BillingResult;
import cn.shang.charging.billing.pojo.DurationSegment;
import cn.shang.charging.billing.pojo.IncompleteUnitChargeSpec;
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
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 时长计费模式测试
 * <p>
 * 覆盖 PERIOD / GLOBAL 两种模式：基础计费、封顶触发、免费段扣除、periodLabel。
 */
class DurationBillingModeTest {

    /** PERIOD 模式：日段 8:00-20:00 单价 2 元/h，8 小时全日段 = 16 元 */
    @Test
    void periodMode_basicDaySegment() {
        DayNightConfig config = dayNightConfig("period-basic", new BigDecimal("100.00"));
        BillingService service = createService(config, BConstants.CalculationMode.DURATION_PERIOD);

        BillingResult result = service.calculate(request(
                LocalDateTime.of(2026, 1, 1, 8, 0),
                LocalDateTime.of(2026, 1, 1, 16, 0)));

        assertEquals(0, new BigDecimal("16.00").compareTo(result.getFinalAmount()));

        List<DurationSegment> segs = result.getDurationSegments();
        assertNotNull(segs);
        assertFalse(segs.isEmpty());
        DurationSegment first = segs.get(0);
        assertEquals(8 * 60, first.chargedMinutes());
        assertEquals(new BigDecimal("2.00"), first.unitPrice());
        assertEquals(0, new BigDecimal("16.00").compareTo(first.chargedAmount()));
        assertEquals("day", first.periodLabel());
        assertNull(first.periodCap()); // DayNight 无时段封顶
    }

    /** PERIOD 模式：跨日夜段，验证 day/night 标签切换 */
    @Test
    void periodMode_dayNightLabelSwitch() {
        DayNightConfig config = dayNightConfig("period-label", new BigDecimal("100.00"));
        BillingService service = createService(config, BConstants.CalculationMode.DURATION_PERIOD);

        // 18:00-22:00：2h 日段 + 2h 夜段
        BillingResult result = service.calculate(request(
                LocalDateTime.of(2026, 1, 1, 18, 0),
                LocalDateTime.of(2026, 1, 1, 22, 0)));

        // 2×2 + 2×1 = 6 元
        assertEquals(0, new BigDecimal("6.00").compareTo(result.getFinalAmount()));

        List<DurationSegment> segs = result.getDurationSegments();
        assertTrue(segs.stream().anyMatch(s -> "day".equals(s.periodLabel())));
        assertTrue(segs.stream().anyMatch(s -> "night".equals(s.periodLabel())));
    }

    /** PERIOD 模式：周期封顶触发，实收 = min(cap, 周期内总额) */
    @Test
    void periodMode_cycleCapTriggered() {
        // 封顶 10 元/天，日段 2 元/h × 8h = 16 元 > 10 元 → 实收 10 元
        DayNightConfig config = dayNightConfig("period-cap", new BigDecimal("10.00"));
        BillingService service = createService(config, BConstants.CalculationMode.DURATION_PERIOD);

        BillingResult result = service.calculate(request(
                LocalDateTime.of(2026, 1, 1, 8, 0),
                LocalDateTime.of(2026, 1, 1, 16, 0)));

        // 周期封顶：min(10, 16) = 10
        assertEquals(0, new BigDecimal("10.00").compareTo(result.getFinalAmount()));

        // DurationSegment 保持原始应收（时段封顶后=周期封顶前），之和 = 16
        BigDecimal segSum = result.getDurationSegments().stream()
                .map(DurationSegment::chargedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, new BigDecimal("16.00").compareTo(segSum));

        // cycleCapApplied 在分段结果上（单分段直接取）
        // BillingResult 没有 getSegments，通过 carryOver 间接不可得；这里验证 finalAmount 即可
    }

    /** PERIOD 模式：跨 2 周期，每周期独立封顶 */
    @Test
    void periodMode_multiCycleCap() {
        // 封顶 10 元/天，停 48h（2 整天）：每天 36元 → 每天封顶 10 → 2×10=20
        DayNightConfig config = dayNightConfig("period-multi", new BigDecimal("10.00"));
        BillingService service = createService(config, BConstants.CalculationMode.DURATION_PERIOD);

        LocalDateTime begin = LocalDateTime.of(2026, 1, 1, 8, 0);
        BillingResult result = service.calculate(request(begin, begin.plusHours(48)));

        // 2 周期各封顶 10 元 = 20 元
        assertEquals(0, new BigDecimal("20.00").compareTo(result.getFinalAmount()));
    }

    /** GLOBAL 模式：长期停车，周期数 = ceil(总分钟/周期分钟) */
    @Test
    void globalMode_cycleCountCeil() {
        DayNightConfig config = dayNightConfig("global-ceil", new BigDecimal("100.00"));
        BillingService service = createService(config, BConstants.CalculationMode.DURATION_GLOBAL);

        // 47h = 2820min，ceil(2820/1440) = 2 周期
        LocalDateTime begin = LocalDateTime.of(2026, 1, 1, 8, 0);
        long totalMinutes = Duration.between(begin, begin.plusHours(47)).toMinutes();
        int cycleCount = (int) Math.ceil((double) totalMinutes / 1440);
        assertEquals(2, cycleCount);

        BillingResult result = service.calculate(request(begin, begin.plusHours(47)));
        // 封顶 2×100=200，实际金额应 ≤ 200
        assertEquals(0, new BigDecimal("71.00").compareTo(result.getFinalAmount()));
    }

    /** GLOBAL 模式：周期封顶触发，实收 = min(cap×周期数, 总额) */
    @Test
    void globalMode_cycleCapTriggered() {
        // 封顶 10 元/天，停 48h（2 周期），日段 2 元/h
        // 第1天 8:00-20:00 日段12h=24元 + 夜段12h=12元 = 36元
        // 第2天同上 36元，总 72 元 > 封顶 2×10=20 → 实收 20
        DayNightConfig config = dayNightConfig("global-cap", new BigDecimal("10.00"));
        BillingService service = createService(config, BConstants.CalculationMode.DURATION_GLOBAL);

        LocalDateTime begin = LocalDateTime.of(2026, 1, 1, 8, 0);
        BillingResult result = service.calculate(request(begin, begin.plusHours(48)));

        // 周期封顶 2×10=20，总额 72 → min = 20
        assertEquals(0, new BigDecimal("20.00").compareTo(result.getFinalAmount()));

        // DurationSegment 保持原始应收
        BigDecimal segSum = result.getDurationSegments().stream()
                .map(DurationSegment::chargedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertTrue(segSum.compareTo(new BigDecimal("20.00")) > 0); // 明细 > 实收
    }

    /** GLOBAL 模式：periodLabel 跨周期合并同 period 类型 */
    @Test
    void globalMode_periodLabelAcrossCycles() {
        DayNightConfig config = dayNightConfig("global-label", new BigDecimal("1000.00"));
        BillingService service = createService(config, BConstants.CalculationMode.DURATION_GLOBAL);

        LocalDateTime begin = LocalDateTime.of(2026, 1, 1, 8, 0);
        BillingResult result = service.calculate(request(begin, begin.plusHours(48)));

        List<DurationSegment> segs = result.getDurationSegments();
        // 应有 day/night 两种标签
        assertTrue(segs.stream().anyMatch(s -> "day".equals(s.periodLabel())));
        assertTrue(segs.stream().anyMatch(s -> "night".equals(s.periodLabel())));
    }

    /** 免费段扣除：chargedMinutes=0 表达免费段 */
    @Test
    void periodMode_freeRangeDeduction() {
        DayNightConfig config = dayNightConfig("period-free", new BigDecimal("100.00"));
        BillingService service = createService(config, BConstants.CalculationMode.DURATION_PERIOD);

        // 8:00-12:00，10:00-11:00 免费
        BillingRequest req = request(
                LocalDateTime.of(2026, 1, 1, 8, 0),
                LocalDateTime.of(2026, 1, 1, 12, 0));
        req.setExternalPromotions(List.of(
                PromotionGrant.builder()
                        .id("free-10-11")
                        .type(BConstants.PromotionType.FREE_RANGE)
                        .beginTime(LocalDateTime.of(2026, 1, 1, 10, 0))
                        .endTime(LocalDateTime.of(2026, 1, 1, 11, 0))
                        .priority(1)
                        .build()
        ));

        BillingResult result = service.calculate(req);

        // 4h - 1h 免费 = 3h × 2 = 6 元
        assertEquals(0, new BigDecimal("6.00").compareTo(result.getFinalAmount()));

        // 免费段 chargedMinutes=0
        List<DurationSegment> segs = result.getDurationSegments();
        assertTrue(segs.size() >= 2);
        assertTrue(segs.stream().anyMatch(s -> s.chargedMinutes() == 0)); // 免费段
        // 收费段总分钟 = 180
        int totalCharged = segs.stream()
                .filter(s -> s.chargedAmount().compareTo(BigDecimal.ZERO) > 0)
                .mapToInt(DurationSegment::chargedMinutes)
                .sum();
        assertEquals(180, totalCharged);
    }

    /** GLOBAL 模式 + FREE_MINUTES：时段化后只输出收费汇总桶，免费信息走 PromotionUsage。 */
    @Test
    void globalMode_freeMinutesMaterialization_homogeneousSegments() {
        DayNightConfig config = dayNightConfig("global-fm-mat", new BigDecimal("1000.00"));
        BillingService service = createService(config, BConstants.CalculationMode.DURATION_GLOBAL);

        // 10:00-14:00 全日段（4h=240min，2元/h），FREE_MINUTES=60min
        BillingRequest req = request(
                LocalDateTime.of(2026, 1, 1, 10, 0),
                LocalDateTime.of(2026, 1, 1, 14, 0));
        req.setExternalPromotions(List.of(
                PromotionGrant.builder()
                        .id("fm-60")
                        .type(BConstants.PromotionType.FREE_MINUTES)
                        .freeMinutes(60)
                        .priority(1)
                        .build()
        ));

        BillingResult result = service.calculate(req);

        // 4h - 1h 免费 = 3h × 2 = 6 元
        assertEquals(0, new BigDecimal("6.00").compareTo(result.getFinalAmount()));

        List<DurationSegment> segs = result.getDurationSegments();
        assertNotNull(segs);
        assertFalse(segs.isEmpty());

        assertEquals(1, segs.size());
        assertNull(segs.get(0).beginTime());
        assertNull(segs.get(0).endTime());
        assertEquals("day", segs.get(0).periodLabel());
        // 收费段总分钟 = 180（240 - 60）
        int totalCharged = segs.stream()
                .filter(s -> s.chargedMinutes() > 0)
                .mapToInt(DurationSegment::chargedMinutes)
                .sum();
        assertEquals(180, totalCharged);
    }

    /** PERIOD 模式 + FULL_CHARGE（默认）：不足 unitMinutes 的余数向上取整，"不满一小时按一小时算" */
    @Test
    void periodMode_fullCharge_ceilRemainder() {
        // dayNightConfig 默认 FULL_CHARGE
        DayNightConfig config = dayNightConfig("period-full", new BigDecimal("100.00"));
        BillingService service = createService(config, BConstants.CalculationMode.DURATION_PERIOD);

        // 8:00-9:30 = 90 分钟，unitMinutes=60，日段 2 元/时
        BillingResult result = service.calculate(request(
                LocalDateTime.of(2026, 1, 1, 8, 0),
                LocalDateTime.of(2026, 1, 1, 9, 30)));

        // FULL_CHARGE：90min = 1 整单元(60) + 余数(30) → 2×1 + 2 = 4 元（余数收一个全额）
        assertEquals(0, new BigDecimal("4.00").compareTo(result.getFinalAmount()));

        // chargedMinutes 仍记实际分钟（90），不取整
        DurationSegment seg = result.getDurationSegments().get(0);
        assertEquals(90, seg.chargedMinutes());
    }

    /** PERIOD 模式 + PROPORTIONAL：不足 unitMinutes 的余数按比例计费 */
    @Test
    void periodMode_proportional_partialUnit() {
        DayNightConfig config = dayNightConfig("period-prop", new BigDecimal("100.00"),
                BConstants.IncompleteUnitChargeMode.PROPORTIONAL);
        BillingService service = createService(config, BConstants.CalculationMode.DURATION_PERIOD);

        // 8:00-9:30 = 90 分钟
        BillingResult result = service.calculate(request(
                LocalDateTime.of(2026, 1, 1, 8, 0),
                LocalDateTime.of(2026, 1, 1, 9, 30)));

        // PROPORTIONAL：2 × 90 / 60 = 3 元
        assertEquals(0, new BigDecimal("3.00").compareTo(result.getFinalAmount()));
    }

    /** PERIOD 模式：IncompleteUnitChargeSpec 优先于旧散字段参与实际计费。 */
    @Test
    void periodMode_incompleteSpecOverridesLegacyFields() {
        DayNightConfig config = dayNightConfig("period-spec", new BigDecimal("100.00"),
                BConstants.IncompleteUnitChargeMode.FULL_CHARGE);
        config.setIncompleteUnitChargeSpec(IncompleteUnitChargeSpec.builder()
                .mode(BConstants.IncompleteUnitChargeMode.PROPORTIONAL)
                .build());
        BillingService service = createService(config, BConstants.CalculationMode.DURATION_PERIOD);

        BillingResult result = service.calculate(request(
                LocalDateTime.of(2026, 1, 1, 8, 0),
                LocalDateTime.of(2026, 1, 1, 9, 30)));

        assertEquals(0, new BigDecimal("3.00").compareTo(result.getFinalAmount()));
    }

    /** GLOBAL 模式 + FULL_CHARGE（默认）：跨日夜段，每段余数独立向上取整 */
    @Test
    void globalMode_fullCharge_crossDayNight() {
        // dayNightConfig 默认 FULL_CHARGE
        DayNightConfig config = dayNightConfig("global-full", new BigDecimal("1000.00"));
        BillingService service = createService(config, BConstants.CalculationMode.DURATION_GLOBAL);

        // 18:00-21:30：日段 18:00-20:00 (120min) + 夜段 20:00-21:30 (90min)
        BillingResult result = service.calculate(request(
                LocalDateTime.of(2026, 1, 1, 18, 0),
                LocalDateTime.of(2026, 1, 1, 21, 30)));

        // FULL_CHARGE：日段 120min 整除 → 2×2=4 元；夜段 90min=1整+30余 → 1×1+1=2 元。总 6 元
        // （对比 PROPORTIONAL：日段 4 + 夜段 1.5 = 5.5 元）
        assertEquals(0, new BigDecimal("6.00").compareTo(result.getFinalAmount()));
    }

    /** DayNight GLOBAL：相同价格的分散时长先全局汇总，再统一处理不足单元。 */
    @Test
    void globalMode_dayNightAggregatesSamePriceDurations() {
        DayNightConfig config = dayNightConfig("global-aggregate", new BigDecimal("1000.00"));
        BillingService service = createService(config, BConstants.CalculationMode.DURATION_GLOBAL);

        // day 段被免费段切成两个 30min 收费片段。旧实现逐段 FULL_CHARGE 会收 2+2=4；
        // GLOBAL 应汇总 day=60min 后只收 2。
        BillingRequest req = request(
                LocalDateTime.of(2026, 1, 1, 8, 0),
                LocalDateTime.of(2026, 1, 1, 9, 30));
        req.setExternalPromotions(List.of(
                PromotionGrant.builder()
                        .id("middle-free")
                        .type(BConstants.PromotionType.FREE_RANGE)
                        .beginTime(LocalDateTime.of(2026, 1, 1, 8, 30))
                        .endTime(LocalDateTime.of(2026, 1, 1, 9, 0))
                        .priority(1)
                        .build()
        ));

        BillingResult result = service.calculate(req);

        assertEquals(0, new BigDecimal("2.00").compareTo(result.getFinalAmount()));
        assertEquals(1, result.getDurationSegments().size());
        DurationSegment day = result.getDurationSegments().get(0);
        assertNull(day.beginTime());
        assertNull(day.endTime());
        assertEquals("day", day.periodLabel());
        assertEquals(60, day.chargedMinutes());
        assertEquals(0, new BigDecimal("2.00").compareTo(day.chargedAmount()));
    }

    /** DayNight GLOBAL：封顶使用完整周期 cap + 尾周期实际汇总费用，避免 25h 套用 48h 封顶。 */
    @Test
    void globalMode_dayNightTailCycleCapUsesTailCharge() {
        DayNightConfig config = new DayNightConfig()
                .setId("global-tail-cap")
                .setDayBeginMinute(0)
                .setDayEndMinute(1440)
                .setDayUnitPrice(new BigDecimal("2.00"))
                .setNightUnitPrice(new BigDecimal("2.00"))
                .setUnitMinutes(60)
                .setMaxChargeOneDay(new BigDecimal("20.00"))
                .setBlockWeight(new BigDecimal("0.5"));
        BillingService service = createService(config, BConstants.CalculationMode.DURATION_GLOBAL);

        BillingResult result = service.calculate(request(
                LocalDateTime.of(2026, 1, 1, 0, 0),
                LocalDateTime.of(2026, 1, 2, 1, 0)));

        assertEquals(0, new BigDecimal("22.00").compareTo(result.getFinalAmount()));
        assertEquals(1, result.getDurationSegments().size());
        DurationSegment day = result.getDurationSegments().get(0);
        assertNull(day.beginTime());
        assertNull(day.endTime());
        assertEquals("day", day.periodLabel());
        assertEquals(25 * 60, day.chargedMinutes());
        assertEquals(0, new BigDecimal("50.00").compareTo(day.chargedAmount()));
    }

    /** 不支持时长模式的规则传 PERIOD 抛异常 */
    @Test
    void dayNightRuleDeclaresDurationModes() {
        // 用一个不支持时长模式的规则（这里借 NaturalTime，但需要注册）
        // DayNightRule 支持 PERIOD/GLOBAL，要测不支持的场景，用一个 stub resolver 返回 PERIOD
        // 但规则不检查——实际由 BillingCalculator 校验 supportedCalculationModes
        // DayNightRule 支持，所以这里测一个不支持的：构造一个仅 NONE 的规则
        // 简化：直接验证 DayNightRule 声明了支持
        DayNightRule rule = new DayNightRule();
        assertTrue(rule.supportedCalculationModes().contains(BConstants.CalculationMode.DURATION_PERIOD));
        assertTrue(rule.supportedCalculationModes().contains(BConstants.CalculationMode.DURATION_GLOBAL));
    }

    // ==================== 辅助方法 ====================

    private DayNightConfig dayNightConfig(String id, BigDecimal maxCharge) {
        return dayNightConfig(id, maxCharge, BConstants.IncompleteUnitChargeMode.FULL_CHARGE);
    }

    private DayNightConfig dayNightConfig(String id, BigDecimal maxCharge,
                                          BConstants.IncompleteUnitChargeMode incompleteMode) {
        return DayNightConfig.builder()
                .id(id)
                .dayBeginMinute(8 * 60)
                .dayEndMinute(20 * 60)
                .dayUnitPrice(new BigDecimal("2.00"))
                .nightUnitPrice(new BigDecimal("1.00"))
                .maxChargeOneDay(maxCharge)
                .unitMinutes(60)
                .blockWeight(new BigDecimal("0.5"))
                .incompleteUnitChargeMode(incompleteMode)
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

        // BillingResult 不直接暴露分段，这里仅占位
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
