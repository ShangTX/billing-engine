package cn.shang.charging;

import cn.shang.charging.billing.BillingCalculator;
import cn.shang.charging.billing.BillingConfigResolver;
import cn.shang.charging.billing.BillingService;
import cn.shang.charging.billing.SegmentBuilder;
import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.BillingRequest;
import cn.shang.charging.billing.pojo.BillingResult;
import cn.shang.charging.billing.pojo.PromotionRuleConfig;
import cn.shang.charging.billing.pojo.RuleConfig;
import cn.shang.charging.charge.rules.BillingRuleRegistry;
import cn.shang.charging.charge.rules.daynight.DayNightConfig;
import cn.shang.charging.charge.rules.daynight.DayNightRule;
import cn.shang.charging.promotion.FreeMinuteAllocator;
import cn.shang.charging.promotion.FreeTimeRangeMerger;
import cn.shang.charging.promotion.PromotionEngine;
import cn.shang.charging.promotion.pojo.PromotionGrant;
import cn.shang.charging.promotion.pojo.PromotionUsage;
import cn.shang.charging.promotion.rules.PromotionRuleRegistry;
import cn.shang.charging.settlement.ResultAssembler;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * FREE_RANGE 免费段产出 PromotionUsage 测试（TODO-20260701-001）。
 * <p>
 * 验证 4 模式（CONTINUOUS/UNIT_BASED/PERIOD/GLOBAL）下 FREE_RANGE 产出 usage，
 * 含 promotionId / usedFrom / usedTo / usedMinutes / equivalentAmount。
 * <p>
 * 场景：8:00-16:00（白天 8:00-20:00，dayUnitPrice 2.00），FREE_RANGE 11:00-12:00（1 小时）。
 * equivalentAmount = 1h × 2.00 = 2.00。
 */
class FreeRangePromotionUsageTest {

    private static final LocalDateTime BEGIN = LocalDateTime.of(2026, 4, 20, 8, 0);
    private static final LocalDateTime END = LocalDateTime.of(2026, 4, 20, 16, 0);
    private static final LocalDateTime FREE_FROM = LocalDateTime.of(2026, 4, 20, 11, 0);
    private static final LocalDateTime FREE_TO = LocalDateTime.of(2026, 4, 20, 12, 0);
    private static final BigDecimal EXPECTED_EQUIV = new BigDecimal("2.00");

    @Test
    void continuous_freeRangeProducesUsage() {
        PromotionUsage usage = findFreeRangeUsage(calculate(BConstants.BillingMode.CONTINUOUS, BConstants.DurationMode.NONE));
        assertUsage(usage);
    }

    @Test
    void unitBased_freeRangeProducesUsage() {
        PromotionUsage usage = findFreeRangeUsage(calculate(BConstants.BillingMode.UNIT_BASED, BConstants.DurationMode.NONE));
        assertUsage(usage);
    }

    @Test
    void period_freeRangeProducesUsage() {
        PromotionUsage usage = findFreeRangeUsage(calculate(BConstants.BillingMode.CONTINUOUS, BConstants.DurationMode.PERIOD));
        assertUsage(usage);
    }

    @Test
    void global_freeRangeProducesUsage() {
        PromotionUsage usage = findFreeRangeUsage(calculate(BConstants.BillingMode.CONTINUOUS, BConstants.DurationMode.GLOBAL));
        assertUsage(usage);
    }

    private void assertUsage(PromotionUsage usage) {
        assertNotNull(usage, "应产出 FREE_RANGE usage");
        assertEquals("free-range-1", usage.getPromotionId());
        assertEquals(FREE_FROM, usage.getUsedFrom());
        assertEquals(FREE_TO, usage.getUsedTo());
        assertEquals(60, usage.getUsedMinutes());
        assertEquals(0, EXPECTED_EQUIV.compareTo(usage.getEquivalentAmount()),
                "equivalentAmount 应为 " + EXPECTED_EQUIV + "，实际 " + usage.getEquivalentAmount());
    }

    private PromotionUsage findFreeRangeUsage(BillingResult result) {
        return result.getPromotionUsages().stream()
                .filter(u -> u.getType() == BConstants.PromotionType.FREE_RANGE)
                .findFirst().orElse(null);
    }

    private BillingResult calculate(BConstants.BillingMode billingMode, BConstants.DurationMode durationMode) {
        BillingService service = createService(billingMode, durationMode);
        BillingRequest request = new BillingRequest();
        request.setId("free-range-usage-test");
        request.setBeginTime(BEGIN);
        request.setEndTime(END);
        request.setSchemeChanges(List.of());
        request.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        request.setSchemeId("scheme-1");
        request.setExternalPromotions(List.of(
                PromotionGrant.builder()
                        .id("free-range-1")
                        .type(BConstants.PromotionType.FREE_RANGE)
                        .priority(1)
                        .source(BConstants.PromotionSource.COUPON)
                        .beginTime(FREE_FROM)
                        .endTime(FREE_TO)
                        .build()
        ));
        return service.calculate(request);
    }

    private static BillingService createService(BConstants.BillingMode billingMode, BConstants.DurationMode durationMode) {
        BillingConfigResolver resolver = createResolver(billingMode, durationMode);
        PromotionRuleRegistry promotionRegistry = new PromotionRuleRegistry();
        PromotionEngine promotionEngine = new PromotionEngine(
                resolver,
                new FreeTimeRangeMerger(),
                new FreeMinuteAllocator(),
                promotionRegistry
        );
        BillingRuleRegistry ruleRegistry = new BillingRuleRegistry();
        ruleRegistry.register(BConstants.ChargeRuleType.DAY_NIGHT, new DayNightRule());
        return new BillingService(
                new SegmentBuilder(),
                resolver,
                promotionEngine,
                new BillingCalculator(ruleRegistry),
                new ResultAssembler()
        );
    }

    private static BillingConfigResolver createResolver(BConstants.BillingMode billingMode, BConstants.DurationMode durationMode) {
        return new BillingConfigResolver() {
            @Override
            public BConstants.BillingMode resolveBillingMode(String schemeId, Map<String, Object> context) {
                return billingMode;
            }

            @Override
            public BConstants.DurationMode resolveDurationMode(String schemeId, Map<String, Object> context) {
                return durationMode;
            }

            @Override
            public RuleConfig resolveChargingRule(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd, Map<String, Object> context) {
                return new DayNightConfig()
                        .setId("daynight-1")
                        .setBlockWeight(new BigDecimal("0.5"))
                        .setDayBeginMinute(480)
                        .setDayEndMinute(1200)
                        .setDayUnitPrice(new BigDecimal("2"))
                        .setNightUnitPrice(new BigDecimal("1"))
                        .setMaxChargeOneDay(new BigDecimal("50"))
                        .setUnitMinutes(60);
            }

            @Override
            public List<PromotionRuleConfig> resolvePromotionRules(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd, Map<String, Object> context) {
                return List.of();
            }
        };
    }
}
