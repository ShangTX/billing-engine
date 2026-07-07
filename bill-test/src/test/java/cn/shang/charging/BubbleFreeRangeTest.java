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
import cn.shang.charging.charge.rules.daynight.DayNightRule;
import cn.shang.charging.promotion.FreeTimeRangeMerger;
import cn.shang.charging.promotion.PromotionEngine;
import cn.shang.charging.promotion.pojo.FreeTimeRangeType;
import cn.shang.charging.promotion.pojo.PromotionGrant;
import cn.shang.charging.promotion.rules.PromotionRuleRegistry;
import cn.shang.charging.settlement.ResultAssembler;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * bubble 免费时段测试：bubble 段不占用周期时长。
 * <p>
 * 场景：1日17:00–3日18:00（49h），bubble 免费段 1日17:00–24:00（7h），
 * 单价 3 元/时（日夜统一），24h 周期封顶 50。
 * <p>
 * 收费段 2日00:00–3日18:00（42h），totalAmount = 42×3 = 126。
 */
class BubbleFreeRangeTest {

    /** GLOBAL + bubble：bubble 时长不计入周期数，cycleCount 从 3 减到 2，封顶 100，finalAmount 100。 */
    @Test
    void globalMode_bubble_reducesCycleCount() {
        BillingService service = createService(config(new BigDecimal("50")), BConstants.CalculationMode.DURATION_GLOBAL);

        BillingRequest req = request(
                LocalDateTime.of(2026, 1, 1, 17, 0),
                LocalDateTime.of(2026, 1, 3, 18, 0));
        req.setExternalPromotions(List.of(freeRange("bubble",
                LocalDateTime.of(2026, 1, 1, 17, 0),
                LocalDateTime.of(2026, 1, 2, 0, 0),
                FreeTimeRangeType.BUBBLE)));

        BillingResult result = service.calculate(req);

        // bubble 7h 不计入周期：cycleCount = ceil((49-7)/24) = 2，封顶 = 50×2 = 100
        // totalAmount = 126 > 100 → finalAmount = 100
        assertEquals(0, new BigDecimal("100").compareTo(result.getFinalAmount()));
    }

    /** GLOBAL + NORMAL（对比）：NORMAL 占用周期，cycleCount 不减（3），封顶 150，finalAmount 126。 */
    @Test
    void globalMode_normal_doesNotReduceCycleCount() {
        BillingService service = createService(config(new BigDecimal("50")), BConstants.CalculationMode.DURATION_GLOBAL);

        BillingRequest req = request(
                LocalDateTime.of(2026, 1, 1, 17, 0),
                LocalDateTime.of(2026, 1, 3, 18, 0));
        req.setExternalPromotions(List.of(freeRange("normal",
                LocalDateTime.of(2026, 1, 1, 17, 0),
                LocalDateTime.of(2026, 1, 2, 0, 0),
                FreeTimeRangeType.NORMAL)));

        BillingResult result = service.calculate(req);

        // NORMAL 7h 占用周期：cycleCount = ceil(49/24) = 3，封顶 = 50×3 = 150
        // totalAmount = 126 < 150 → finalAmount = 126
        assertEquals(0, new BigDecimal("126").compareTo(result.getFinalAmount()));
    }

    // ==================== 辅助方法 ====================

    private DayNightConfig config(BigDecimal maxChargeOneDay) {
        return new DayNightConfig()
                .setId("dn-bubble")
                .setDayBeginMinute(8 * 60)
                .setDayEndMinute(20 * 60)
                .setDayUnitPrice(new BigDecimal("3"))
                .setNightUnitPrice(new BigDecimal("3"))
                .setUnitMinutes(60)
                .setMaxChargeOneDay(maxChargeOneDay)
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

    private BillingService createService(DayNightConfig config, BConstants.CalculationMode mode) {
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
        return new BillingService(
                new SegmentBuilder(), resolver, promotionEngine,
                new BillingCalculator(ruleRegistry), new ResultAssembler());
    }
}
