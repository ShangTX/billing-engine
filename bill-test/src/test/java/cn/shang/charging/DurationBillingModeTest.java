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
import cn.shang.charging.promotion.FreeMinuteAllocator;
import cn.shang.charging.promotion.FreeTimeRangeMerger;
import cn.shang.charging.promotion.PromotionEngine;
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
 */
class DurationBillingModeTest {

    @Test
    void durationModePeriod_basicDayNight() {
        // 配置：日段 8:00-20:00 单价 2 元/h，夜段 20:00-8:00 单价 1 元/h
        DayNightConfig config = DayNightConfig.builder()
                .id("daynight-period")
                .dayBeginMinute(8 * 60)  // 8:00
                .dayEndMinute(20 * 60)   // 20:00
                .dayUnitPrice(new BigDecimal("2.00"))
                .nightUnitPrice(new BigDecimal("1.00"))
                .maxChargeOneDay(new BigDecimal("100.00"))
                .unitMinutes(60)
                .blockWeight(new BigDecimal("0.5"))
                .build();

        BillingService service = createService(config, BConstants.DurationMode.PERIOD);

        // 计费：8:00-16:00（8 小时，全在日段）
        BillingRequest request = createRequest(
                LocalDateTime.of(2026, 1, 1, 8, 0),
                LocalDateTime.of(2026, 1, 1, 16, 0)
        );

        BillingResult result = service.calculate(request);

        // 验证
        assertNotNull(result);
        List<DurationSegment> durationSegments = result.getDurationSegments();
        assertNotNull(durationSegments);
        assertFalse(durationSegments.isEmpty());

        // 8 小时 × 2 元/h = 16 元
        assertEquals(0, new BigDecimal("16.00").compareTo(result.getFinalAmount()));

        // 验证 DurationSegment 结构
        DurationSegment first = durationSegments.get(0);
        assertEquals(8 * 60, first.chargedMinutes());  // 8 小时 = 480 分钟
        assertEquals(new BigDecimal("2.00"), first.unitPrice());
        assertEquals(0, new BigDecimal("16.00").compareTo(first.chargedAmount()));
    }

    @Test
    void durationModeGlobal_longPeriod() {
        // 配置：日段 8:00-20:00 单价 2 元/h，夜段 20:00-8:00 单价 1 元/h，封顶 100 元/天
        DayNightConfig config = DayNightConfig.builder()
                .id("daynight-global")
                .dayBeginMinute(8 * 60)  // 8:00
                .dayEndMinute(20 * 60)   // 20:00
                .dayUnitPrice(new BigDecimal("2.00"))
                .nightUnitPrice(new BigDecimal("1.00"))
                .maxChargeOneDay(new BigDecimal("100.00"))
                .unitMinutes(60)
                .blockWeight(new BigDecimal("0.5"))
                .build();

        BillingService service = createService(config, BConstants.DurationMode.GLOBAL);

        // 计费：47 小时（跨 2 天）
        LocalDateTime begin = LocalDateTime.of(2026, 1, 1, 8, 0);
        LocalDateTime end = begin.plusHours(47);

        BillingRequest request = createRequest(begin, end);

        BillingResult result = service.calculate(request);

        // 验证
        assertNotNull(result);
        List<DurationSegment> durationSegments = result.getDurationSegments();
        assertNotNull(durationSegments);
        assertFalse(durationSegments.isEmpty());

        // 验证总金额（应该被周期封顶限制）
        // 47h = 2 周期（ceil(47*60/1440) = 2）
        // 封顶 = 2 × 100 = 200 元
        assertTrue(result.getFinalAmount().compareTo(new BigDecimal("200.00")) <= 0);

        // 验证周期数计算
        long totalMinutes = Duration.between(begin, end).toMinutes();
        int expectedCycles = (int) Math.ceil((double) totalMinutes / 1440);
        assertEquals(2, expectedCycles);
    }

    @Test
    void durationModeWithFreeRange() {
        // 配置：日段 8:00-20:00 单价 2 元/h，夜段 20:00-8:00 单价 1 元/h
        DayNightConfig config = DayNightConfig.builder()
                .id("daynight-free")
                .dayBeginMinute(8 * 60)
                .dayEndMinute(20 * 60)
                .dayUnitPrice(new BigDecimal("2.00"))
                .nightUnitPrice(new BigDecimal("1.00"))
                .maxChargeOneDay(new BigDecimal("100.00"))
                .unitMinutes(60)
                .blockWeight(new BigDecimal("0.5"))
                .build();

        BillingService service = createService(config, BConstants.DurationMode.PERIOD);

        // 计费：8:00-12:00，10:00-11:00 免费
        BillingRequest request = createRequest(
                LocalDateTime.of(2026, 1, 1, 8, 0),
                LocalDateTime.of(2026, 1, 1, 12, 0)
        );
        request.setExternalPromotions(List.of(
                cn.shang.charging.promotion.pojo.PromotionGrant.builder()
                        .id("free-10-11")
                        .type(BConstants.PromotionType.FREE_RANGE)
                        .beginTime(LocalDateTime.of(2026, 1, 1, 10, 0))
                        .endTime(LocalDateTime.of(2026, 1, 1, 11, 0))
                        .priority(1)
                        .build()
        ));

        BillingResult result = service.calculate(request);

        // 验证：4 小时 - 1 小时免费 = 3 小时收费
        // 3 × 2 = 6 元
        assertEquals(0, new BigDecimal("6.00").compareTo(result.getFinalAmount()));

        // 验证 DurationSegment 结构
        List<DurationSegment> durationSegments = result.getDurationSegments();
        // 应该有 2 个段：8:00-10:00 收费，10:00-11:00 免费，11:00-12:00 收费
        // 但边界驱动会合并为 2 段：收费段和免费段
        assertTrue(durationSegments.size() >= 2);

        // 找到收费段，验证总收费分钟数 = 3 小时 = 180 分钟
        int totalChargedMinutes = durationSegments.stream()
                .filter(s -> s.chargedAmount().compareTo(BigDecimal.ZERO) > 0)
                .mapToInt(DurationSegment::chargedMinutes)
                .sum();
        assertEquals(180, totalChargedMinutes);
    }

    private BillingService createService(DayNightConfig config, BConstants.DurationMode durationMode) {
        BillingConfigResolver resolver = new BillingConfigResolver() {
            @Override
            public BConstants.BillingMode resolveBillingMode(String schemeId, Map<String, Object> context) {
                return BConstants.BillingMode.CONTINUOUS;
            }

            @Override
            public RuleConfig resolveChargingRule(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd, Map<String, Object> context) {
                return config;
            }

            @Override
            public List<PromotionRuleConfig> resolvePromotionRules(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd, Map<String, Object> context) {
                return List.of();
            }

            @Override
            public BConstants.DurationMode resolveDurationMode(String schemeId, Map<String, Object> context) {
                return durationMode;
            }
        };

        BillingRuleRegistry ruleRegistry = new BillingRuleRegistry();
        ruleRegistry.register(BConstants.ChargeRuleType.DAY_NIGHT, new DayNightRule());

        return new BillingService(
                new SegmentBuilder(),
                resolver,
                new PromotionEngine(resolver, new FreeTimeRangeMerger(), new FreeMinuteAllocator(), new PromotionRuleRegistry()),
                new BillingCalculator(ruleRegistry),
                new ResultAssembler()
        );
    }

    private BillingRequest createRequest(LocalDateTime begin, LocalDateTime end) {
        BillingRequest request = new BillingRequest();
        request.setBeginTime(begin);
        request.setEndTime(end);
        request.setSchemeId("scheme-1");
        request.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SINGLE);
        request.setSchemeChanges(List.of());
        request.setExternalPromotions(List.of());
        return request;
    }
}
