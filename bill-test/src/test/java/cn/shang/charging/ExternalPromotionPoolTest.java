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
import cn.shang.charging.billing.pojo.SchemeChange;
import cn.shang.charging.charge.rules.BillingRuleRegistry;
import cn.shang.charging.charge.rules.daynight.DayNightConfig;
import cn.shang.charging.charge.rules.daynight.DayNightRule;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 外部优惠跨段共享可用量池测试（TODO-20260702-003）。
 * <p>
 * 验证外部优惠（FREE_MINUTES/FREE_RANGE）整笔停车享一次，多分段不重复。
 * 场景：8:00-16:00 切 2 段（12:00 切换），8 单元 × 2.00 = 16.00。
 */
class ExternalPromotionPoolTest {

    private static final LocalDateTime BEGIN = LocalDateTime.of(2026, 4, 20, 8, 0);
    private static final LocalDateTime END = LocalDateTime.of(2026, 4, 20, 16, 0);
    private static final LocalDateTime SWITCH = LocalDateTime.of(2026, 4, 20, 12, 0);

    @Test
    void freeMinutesAcrossSegmentsNotDuplicated() {
        BillingService service = createService();
        BillingRequest request = baseRequest();
        request.setExternalPromotions(List.of(
                PromotionGrant.builder()
                        .id("free-min-60")
                        .type(BConstants.PromotionType.FREE_MINUTES)
                        .priority(1)
                        .source(BConstants.PromotionSource.COUPON)
                        .freeMinutes(60)
                        .build()
        ));
        BillingResult result = service.calculate(request);

        // 8 单元 × 2.00 = 16.00，FREE_MINUTES 60 分钟免 1 单元（2.00），跨 2 段不重复
        // 无池 bug：段1+段2 各免 1 单元 = 12.00；有池正确：14.00
        assertEquals(0, new BigDecimal("14.00").compareTo(result.getFinalAmount()),
                "FREE_MINUTES 跨段不应重复，expected=14.00, actual=" + result.getFinalAmount());

        long totalUsedMinutes = result.getPromotionUsages().stream()
                .filter(u -> u.getType() == BConstants.PromotionType.FREE_MINUTES)
                .mapToLong(PromotionUsage::getUsedMinutes).sum();
        assertEquals(60, totalUsedMinutes, "FREE_MINUTES 总使用应 60 分钟（不重复）");
    }

    @Test
    void freeRangeAcrossSegmentBoundarySplit() {
        BillingService service = createService();
        BillingRequest request = baseRequest();
        // FREE_RANGE 11:00-13:00 跨段边界（段1 11:00-12:00 + 段2 12:00-13:00）
        request.setExternalPromotions(List.of(
                PromotionGrant.builder()
                        .id("free-range-cross")
                        .type(BConstants.PromotionType.FREE_RANGE)
                        .priority(1)
                        .source(BConstants.PromotionSource.COUPON)
                        .beginTime(LocalDateTime.of(2026, 4, 20, 11, 0))
                        .endTime(LocalDateTime.of(2026, 4, 20, 13, 0))
                        .build()
        ));
        BillingResult result = service.calculate(request);

        // 2 单元免费（11:00-12:00 段1 + 12:00-13:00 段2）= 4.00；finalAmount = 16.00 - 4.00 = 12.00
        assertEquals(0, new BigDecimal("12.00").compareTo(result.getFinalAmount()),
                "FREE_RANGE 跨段边界应正确切分，expected=12.00, actual=" + result.getFinalAmount());

        // 段1 usage 11:00-12:00，段2 usage 12:00-13:00（池回写分裂后剩余给段2）
        long totalUsedMinutes = result.getPromotionUsages().stream()
                .filter(u -> u.getType() == BConstants.PromotionType.FREE_RANGE)
                .mapToLong(PromotionUsage::getUsedMinutes).sum();
        assertEquals(120, totalUsedMinutes, "FREE_RANGE 跨段总覆盖应 120 分钟（11:00-13:00）");

        // 不应出现重复 usage（同一时段被记两次）
        long rangeUsageCount = result.getPromotionUsages().stream()
                .filter(u -> u.getType() == BConstants.PromotionType.FREE_RANGE).count();
        assertTrue(rangeUsageCount >= 1, "应产出 FREE_RANGE usage");
    }

    private BillingRequest baseRequest() {
        BillingRequest request = new BillingRequest();
        request.setId("external-pool-test");
        request.setBeginTime(BEGIN);
        request.setEndTime(END);
        SchemeChange change = new SchemeChange();
        change.setLastSchemeId("scheme-1");
        change.setNextSchemeId("scheme-2");
        change.setChangeTime(SWITCH);
        request.setSchemeChanges(List.of(change));
        request.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        request.setSchemeId("scheme-1");
        return request;
    }

    private static BillingService createService() {
        BillingConfigResolver resolver = new BillingConfigResolver() {
            @Override
            public BConstants.BillingMode resolveBillingMode(String schemeId, Map<String, Object> context) {
                return BConstants.BillingMode.CONTINUOUS;
            }

            @Override
            public RuleConfig resolveChargingRule(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd, Map<String, Object> context) {
                return new DayNightConfig()
                        .setId("daynight-pool")
                        .setBlockWeight(new BigDecimal("0.5"))
                        .setDayBeginMinute(480)
                        .setDayEndMinute(1200)
                        .setDayUnitPrice(new BigDecimal("2"))
                        .setNightUnitPrice(new BigDecimal("1"))
                        .setMaxChargeOneDay(new BigDecimal("1000"))
                        .setUnitMinutes(60);
            }

            @Override
            public List<PromotionRuleConfig> resolvePromotionRules(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd, Map<String, Object> context) {
                return List.of();
            }
        };
        PromotionRuleRegistry promotionRegistry = new PromotionRuleRegistry();
        PromotionEngine promotionEngine = new PromotionEngine(
                resolver,
                new FreeTimeRangeMerger(),
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
}
