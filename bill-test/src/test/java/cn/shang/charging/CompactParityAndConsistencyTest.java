package cn.shang.charging;

import cn.shang.charging.billing.BillingCalculator;
import cn.shang.charging.billing.BillingConfigResolver;
import cn.shang.charging.billing.BillingService;
import cn.shang.charging.billing.SegmentBuilder;
import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.BillingRequest;
import cn.shang.charging.billing.pojo.BillingResult;
import cn.shang.charging.billing.pojo.BillingUnit;
import cn.shang.charging.billing.pojo.PromotionRuleConfig;
import cn.shang.charging.billing.pojo.RuleConfig;
import cn.shang.charging.charge.rules.BillingRuleRegistry;
import cn.shang.charging.charge.rules.compositetime.CompositePeriod;
import cn.shang.charging.charge.rules.compositetime.CompositeTimeConfig;
import cn.shang.charging.charge.rules.compositetime.CompositeTimeRule;
import cn.shang.charging.charge.rules.compositetime.CrossPeriodMode;
import cn.shang.charging.charge.rules.compositetime.NaturalPeriod;
import cn.shang.charging.charge.rules.daynight.DayNightConfig;
import cn.shang.charging.charge.rules.daynight.DayNightRule;
import cn.shang.charging.charge.rules.naturaltime.NaturalTimeConfig;
import cn.shang.charging.charge.rules.naturaltime.NaturalTimeRule;
import cn.shang.charging.charge.rules.relativetime.RelativeTimeConfig;
import cn.shang.charging.charge.rules.relativetime.RelativeTimePeriod;
import cn.shang.charging.charge.rules.relativetime.RelativeTimeRule;
import cn.shang.charging.promotion.FreeTimeRangeMerger;
import cn.shang.charging.promotion.PromotionEngine;
import cn.shang.charging.promotion.rules.PromotionRuleRegistry;
import cn.shang.charging.settlement.ResultAssembler;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * compact 模式端到端金额回归 + 自洽性校验。
 * <p>
 * 方式 B：对产出 compact 的长窗口场景，用独立手写公式计算期望金额，断言引擎 finalAmount 一致。
 * 方式 A：用 {@link CompactConsistencyAssert} 展开 compact 单元，校验子单元等长、等价、累计连续。
 * <p>
 * 独立公式不依赖引擎实现，仅按"按时段切分、每段 floor(分钟/单元长) × 单价"计算，
 * 用于抓边界驱动切段错误。
 */
class CompactParityAndConsistencyTest {

    @Test
    void dayNight_longDayWindow_compactMatchesIndependentFormula() {
        // 8:00-16:00 纯白天，60min 单元，单价 2.00 → 8 个子单元合并为 1 个 compact
        DayNightConfig config = DayNightConfig.builder()
                .id("dn-compact-parity")
                .dayBeginMinute(8 * 60).dayEndMinute(19 * 60)
                .unitMinutes(60).blockWeight(new BigDecimal("0.5"))
                .dayUnitPrice(new BigDecimal("2.00")).nightUnitPrice(new BigDecimal("1.00"))
                .maxChargeOneDay(new BigDecimal("1000.00")).build();

        LocalDateTime begin = LocalDateTime.of(2026, 4, 20, 8, 0);
        LocalDateTime end = LocalDateTime.of(2026, 4, 20, 16, 0);
        BillingResult result = dayNightService(config).calculate(baseRequest(begin, end));

        BigDecimal expected = new BigDecimal("16.00"); // 8 × 2.00
        assertEquals(0, expected.compareTo(result.getFinalAmount()),
                () -> "dayNight compact 金额不符，expected=" + expected + ", actual=" + result.getFinalAmount());

        CompactConsistencyAssert.assertUnitsConsistent(result.getUnits());
    }

    @Test
    void relativeTime_longSinglePeriod_compactMatchesIndependentFormula() {
        // 单时段 30min 1.50，8h = 16 个子单元 → 1 个 compact
        RelativeTimeConfig config = RelativeTimeConfig.builder()
                .id("rel-compact-parity")
                .periods(List.of(RelativeTimePeriod.builder()
                        .beginMinute(0).endMinute(1440).unitMinutes(30)
                        .unitPrice(new BigDecimal("1.50")).build()))
                .maxChargeOneCycle(new BigDecimal("1000.00")).build();

        LocalDateTime begin = LocalDateTime.of(2026, 4, 20, 8, 0);
        LocalDateTime end = LocalDateTime.of(2026, 4, 20, 16, 0);
        BillingResult result = relativeService(config).calculate(baseRequest(begin, end));

        BigDecimal expected = new BigDecimal("24.00"); // 16 × 1.50
        assertEquals(0, expected.compareTo(result.getFinalAmount()),
                () -> "relativeTime compact 金额不符，expected=" + expected + ", actual=" + result.getFinalAmount());

        CompactConsistencyAssert.assertUnitsConsistent(result.getUnits());
    }

    @Test
    void naturalTime_crossNaturalPeriod_compactMatchesIndependentFormula() {
        // 4 自然时段，8:00-16:00 跨 360-720(2.00) 与 720-1080(1.50)，各产出 1 个 compact
        NaturalTimeConfig config = NaturalTimeConfig.builder()
                .id("nat-compact-parity")
                .unitMinutes(60)
                .crossPeriodMode(CrossPeriodMode.BEGIN_TIME_TRUNCATE)
                .periods(List.of(
                        NaturalPeriod.builder().beginMinute(0).endMinute(360).unitPrice(new BigDecimal("1.00")).build(),
                        NaturalPeriod.builder().beginMinute(360).endMinute(720).unitPrice(new BigDecimal("2.00")).build(),
                        NaturalPeriod.builder().beginMinute(720).endMinute(1080).unitPrice(new BigDecimal("1.50")).build(),
                        NaturalPeriod.builder().beginMinute(1080).endMinute(1440).unitPrice(new BigDecimal("1.00")).build()
                ))
                .maxChargeOneDay(new BigDecimal("1000.00")).build();

        LocalDateTime begin = LocalDateTime.of(2026, 4, 20, 8, 0);
        LocalDateTime end = LocalDateTime.of(2026, 4, 20, 16, 0);
        BillingResult result = naturalService(config).calculate(baseRequest(begin, end));

        // 独立公式：8:00-12:00 在 360-720 时段（2.00），12:00-16:00 在 720-1080 时段（1.50）
        BigDecimal expected = new BigDecimal("14.00"); // 4×2.00 + 4×1.50
        assertEquals(0, expected.compareTo(result.getFinalAmount()),
                () -> "naturalTime compact 金额不符，expected=" + expected + ", actual=" + result.getFinalAmount());

        CompactConsistencyAssert.assertUnitsConsistent(result.getUnits());
    }

    @Test
    void compositeTime_longWindow_compactMatchesIndependentFormula() {
        // 单 relative period 0-1440 60min，naturalPeriods 480-1200(2.00)，8:00-16:00 全在 2.00 时段
        CompositeTimeConfig config = CompositeTimeConfig.builder()
                .id("comp-compact-parity")
                .maxChargeOneCycle(new BigDecimal("1000.00"))
                .periods(List.of(CompositePeriod.builder()
                        .beginMinute(0).endMinute(1440).unitMinutes(60)
                        .crossPeriodMode(CrossPeriodMode.BEGIN_TIME_TRUNCATE)
                        .naturalPeriods(List.of(
                                NaturalPeriod.builder().beginMinute(0).endMinute(480).unitPrice(new BigDecimal("1.00")).build(),
                                NaturalPeriod.builder().beginMinute(480).endMinute(1200).unitPrice(new BigDecimal("2.00")).build(),
                                NaturalPeriod.builder().beginMinute(1200).endMinute(1440).unitPrice(new BigDecimal("1.00")).build()
                        ))
                        .build()))
                .build();

        LocalDateTime begin = LocalDateTime.of(2026, 4, 20, 8, 0);
        LocalDateTime end = LocalDateTime.of(2026, 4, 20, 16, 0);
        BillingResult result = compositeService(config).calculate(baseRequest(begin, end));

        BigDecimal expected = new BigDecimal("16.00"); // 8 × 2.00
        assertEquals(0, expected.compareTo(result.getFinalAmount()),
                () -> "compositeTime compact 金额不符，expected=" + expected + ", actual=" + result.getFinalAmount());

        CompactConsistencyAssert.assertUnitsConsistent(result.getUnits());
    }

    @Test
    void dayNight_compactWithDailyCap_matchesIndependentFormula() {
        // 8:00-14:00 纯白天 6 单元 × 2.00 = 12.00，但封顶 6.00 → 触发封顶边界
        DayNightConfig config = DayNightConfig.builder()
                .id("dn-compact-cap")
                .dayBeginMinute(8 * 60).dayEndMinute(19 * 60)
                .unitMinutes(60).blockWeight(new BigDecimal("0.5"))
                .dayUnitPrice(new BigDecimal("2.00")).nightUnitPrice(new BigDecimal("1.00"))
                .maxChargeOneDay(new BigDecimal("6.00")).build();

        LocalDateTime begin = LocalDateTime.of(2026, 4, 20, 8, 0);
        LocalDateTime end = LocalDateTime.of(2026, 4, 20, 14, 0);
        BillingResult result = dayNightService(config).calculate(baseRequest(begin, end));

        // 独立公式：前 3 单元达封顶 6.00，剩余 3 单元免费
        BigDecimal expected = new BigDecimal("6.00");
        assertEquals(0, expected.compareTo(result.getFinalAmount()),
                () -> "dayNight compact+cap 金额不符，expected=" + expected + ", actual=" + result.getFinalAmount());

        CompactConsistencyAssert.assertUnitsConsistent(result.getUnits());
    }

    private BillingRequest baseRequest(LocalDateTime begin, LocalDateTime end) {
        BillingRequest r = new BillingRequest();
        r.setBeginTime(begin);
        r.setEndTime(end);
        r.setSchemeId("scheme-1");
        r.setSchemeChanges(List.of());
        r.setExternalPromotions(List.of());
        r.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        return r;
    }

    private BillingService dayNightService(DayNightConfig config) {
        return service(config, BConstants.ChargeRuleType.DAY_NIGHT, new DayNightRule());
    }

    private BillingService relativeService(RelativeTimeConfig config) {
        return service(config, BConstants.ChargeRuleType.RELATIVE_TIME, new RelativeTimeRule());
    }

    private BillingService naturalService(NaturalTimeConfig config) {
        return service(config, BConstants.ChargeRuleType.NATURAL_TIME, new NaturalTimeRule());
    }

    private BillingService compositeService(CompositeTimeConfig config) {
        return service(config, BConstants.ChargeRuleType.COMPOSITE_TIME, new CompositeTimeRule());
    }

    private BillingService service(RuleConfig config, String ruleType, cn.shang.charging.charge.rules.BillingRule<?> rule) {
        BillingConfigResolver resolver = new BillingConfigResolver() {
            @Override
            public BConstants.BillingMode resolveBillingMode(String schemeId, Map<String, Object> ctx) {
                return BConstants.BillingMode.CONTINUOUS;
            }

            @Override
            public RuleConfig resolveChargingRule(String schemeId, LocalDateTime s, LocalDateTime e, Map<String, Object> ctx) {
                return config;
            }

            @Override
            public List<PromotionRuleConfig> resolvePromotionRules(String schemeId, LocalDateTime s, LocalDateTime e, Map<String, Object> ctx) {
                return List.of();
            }
        };
        BillingRuleRegistry rr = new BillingRuleRegistry();
        rr.register(ruleType, rule);
        return new BillingService(new SegmentBuilder(), resolver,
                new PromotionEngine(resolver, new FreeTimeRangeMerger(), new PromotionRuleRegistry()),
                new BillingCalculator(rr), new ResultAssembler());
    }
}
