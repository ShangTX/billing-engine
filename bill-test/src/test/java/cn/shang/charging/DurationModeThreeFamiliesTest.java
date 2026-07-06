package cn.shang.charging;

import cn.shang.charging.billing.BillingSegment;
import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.BillingContext;
import cn.shang.charging.billing.pojo.BillingSegmentResult;
import cn.shang.charging.billing.pojo.CalculationWindow;
import cn.shang.charging.billing.pojo.DurationSegment;
import cn.shang.charging.charge.rules.compositetime.CompositePeriod;
import cn.shang.charging.charge.rules.compositetime.CompositeTimeConfig;
import cn.shang.charging.charge.rules.compositetime.CompositeTimeRule;
import cn.shang.charging.charge.rules.compositetime.CrossPeriodMode;
import cn.shang.charging.charge.rules.compositetime.NaturalPeriod;
import cn.shang.charging.charge.rules.naturaltime.NaturalTimeConfig;
import cn.shang.charging.charge.rules.naturaltime.NaturalTimeRule;
import cn.shang.charging.charge.rules.relativetime.RelativeTimeConfig;
import cn.shang.charging.charge.rules.relativetime.RelativeTimePeriod;
import cn.shang.charging.charge.rules.relativetime.RelativeTimeRule;
import cn.shang.charging.promotion.pojo.PromotionAggregate;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 3 规则族（relativeTime / naturalTime / compositeTime）时长模式（PERIOD/GLOBAL）冒烟测试。
 * <p>
 * 阶段 4 新能力：3 规则族声明 supportedCalculationModes 含 DURATION_PERIOD/DURATION_GLOBAL，
 * 自动获得时长计费能力（规则族语义由各自 Semantics 注入通用 DurationPeriod/GlobalStrategy）。
 * 本测试验证：声明即支持、计算不报错、金额合理、DurationSegment 产出正确。
 * <p>
 * TODO-20260706-002 阶段4。
 */
class DurationModeThreeFamiliesTest {

    // ==================== 声明支持 ====================

    @Test
    void allThreeFamilies_declareDurationModes() {
        assertTrue(new RelativeTimeRule().supportedCalculationModes()
                .contains(BConstants.CalculationMode.DURATION_PERIOD));
        assertTrue(new RelativeTimeRule().supportedCalculationModes()
                .contains(BConstants.CalculationMode.DURATION_GLOBAL));
        assertTrue(new NaturalTimeRule().supportedCalculationModes()
                .contains(BConstants.CalculationMode.DURATION_PERIOD));
        assertTrue(new NaturalTimeRule().supportedCalculationModes()
                .contains(BConstants.CalculationMode.DURATION_GLOBAL));
        assertTrue(new CompositeTimeRule().supportedCalculationModes()
                .contains(BConstants.CalculationMode.DURATION_PERIOD));
        assertTrue(new CompositeTimeRule().supportedCalculationModes()
                .contains(BConstants.CalculationMode.DURATION_GLOBAL));
    }

    // ==================== RelativeTime ====================

    /** PERIOD：单 period 2 元/h，3h = 6 元 */
    @Test
    void relativeTime_period_basic() {
        RelativeTimeConfig config = relativeTimeConfig(new BigDecimal("100.00"));
        BillingSegmentResult result = new RelativeTimeRule().calculate(
                ctx(LocalDateTime.of(2026, 1, 1, 8, 0),
                    LocalDateTime.of(2026, 1, 1, 11, 0),
                    BConstants.CalculationMode.DURATION_PERIOD),
                config, PromotionAggregate.builder().build());

        assertEquals(0, new BigDecimal("6.00").compareTo(result.getChargedAmount()));
        assertEquals(BConstants.CalculationMode.DURATION_PERIOD, result.getCalculationMode());
        assertNotNull(result.getDurationSegments());
        assertFalse(result.getDurationSegments().isEmpty());
    }

    /** PERIOD：跨周期，每周期独立封顶。封顶 5 元/周期，2 周期 = 10 元 */
    @Test
    void relativeTime_period_multiCycleCap() {
        RelativeTimeConfig config = relativeTimeConfig(new BigDecimal("5.00"));
        LocalDateTime begin = LocalDateTime.of(2026, 1, 1, 8, 0);
        BillingSegmentResult result = new RelativeTimeRule().calculate(
                ctx(begin, begin.plusHours(48), BConstants.CalculationMode.DURATION_PERIOD),
                config, PromotionAggregate.builder().build());

        // 2 周期各封顶 5 元 = 10 元
        assertEquals(0, new BigDecimal("10.00").compareTo(result.getChargedAmount()));
    }

    /** GLOBAL：长期，封顶 5 元/周期 × ceil(47h/24h)=2 周期 = 10 元 */
    @Test
    void relativeTime_global_cap() {
        RelativeTimeConfig config = relativeTimeConfig(new BigDecimal("5.00"));
        LocalDateTime begin = LocalDateTime.of(2026, 1, 1, 8, 0);
        BillingSegmentResult result = new RelativeTimeRule().calculate(
                ctx(begin, begin.plusHours(47), BConstants.CalculationMode.DURATION_GLOBAL),
                config, PromotionAggregate.builder().build());

        assertTrue(result.getChargedAmount().compareTo(new BigDecimal("10.00")) <= 0);
        assertEquals(BConstants.CalculationMode.DURATION_GLOBAL, result.getCalculationMode());
    }

    // ==================== NaturalTime ====================

    /** PERIOD：日段 8:00-20:00 单价 2 元/h，3h = 6 元 */
    @Test
    void naturalTime_period_basic() {
        NaturalTimeConfig config = naturalTimeConfig(new BigDecimal("100.00"));
        BillingSegmentResult result = new NaturalTimeRule().calculate(
                ctx(LocalDateTime.of(2026, 1, 1, 8, 0),
                    LocalDateTime.of(2026, 1, 1, 11, 0),
                    BConstants.CalculationMode.DURATION_PERIOD),
                config, PromotionAggregate.builder().build());

        assertEquals(0, new BigDecimal("6.00").compareTo(result.getChargedAmount()));
        assertNotNull(result.getDurationSegments());
        assertFalse(result.getDurationSegments().isEmpty());
    }

    /** PERIOD：跨日夜段，验证 periodLabel（naturalTime 用 periodKey 作标签） */
    @Test
    void naturalTime_period_labelSwitch() {
        NaturalTimeConfig config = naturalTimeConfig(new BigDecimal("100.00"));
        // 18:00-22:00：日段(8-20) 2h + 夜段(20-8) 2h
        BillingSegmentResult result = new NaturalTimeRule().calculate(
                ctx(LocalDateTime.of(2026, 1, 1, 18, 0),
                    LocalDateTime.of(2026, 1, 1, 22, 0),
                    BConstants.CalculationMode.DURATION_PERIOD),
                config, PromotionAggregate.builder().build());

        // 2×2 + 2×1 = 6 元
        assertEquals(0, new BigDecimal("6.00").compareTo(result.getChargedAmount()));
        List<DurationSegment> segs = result.getDurationSegments();
        assertTrue(segs.size() >= 2, "应至少 2 段（日/夜）");
    }

    // ==================== CompositeTime ====================

    /** PERIOD：单 period 单价 2 元/h，3h = 6 元 */
    @Test
    void compositeTime_period_basic() {
        CompositeTimeConfig config = compositeTimeConfig(new BigDecimal("100.00"), null);
        BillingSegmentResult result = new CompositeTimeRule().calculate(
                ctx(LocalDateTime.of(2026, 1, 1, 8, 0),
                    LocalDateTime.of(2026, 1, 1, 11, 0),
                    BConstants.CalculationMode.DURATION_PERIOD),
                config, PromotionAggregate.builder().build());

        assertEquals(0, new BigDecimal("6.00").compareTo(result.getChargedAmount()));
        assertNotNull(result.getDurationSegments());
        assertFalse(result.getDurationSegments().isEmpty());
    }

    /** PERIOD：periodCap 触发，时段封顶 3 元，3h×2元=6 元 → 削减到 3 元 */
    @Test
    void compositeTime_period_periodCap() {
        CompositeTimeConfig config = compositeTimeConfig(new BigDecimal("100.00"), new BigDecimal("3.00"));
        BillingSegmentResult result = new CompositeTimeRule().calculate(
                ctx(LocalDateTime.of(2026, 1, 1, 8, 0),
                    LocalDateTime.of(2026, 1, 1, 11, 0),
                    BConstants.CalculationMode.DURATION_PERIOD),
                config, PromotionAggregate.builder().build());

        // 时段封顶 3 元（period 内累计削减，落盘到 chargedAmount）
        // DurationSegment.chargedAmount 之和 = 3（封顶后），cycleCap 不触发
        BigDecimal segSum = result.getDurationSegments().stream()
                .map(DurationSegment::chargedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, new BigDecimal("3.00").compareTo(segSum));
        // 实收 = segSum（cycleCap 100 未触发）
        assertEquals(0, new BigDecimal("3.00").compareTo(result.getChargedAmount()));
        // periodCap 落盘到 DurationSegment
        assertTrue(result.getDurationSegments().stream()
                .anyMatch(s -> s.periodCap() != null && s.periodCap().compareTo(new BigDecimal("3.00")) == 0));
    }

    /** GLOBAL：跨周期，periodCap × 周期数 */
    @Test
    void compositeTime_global_periodCapMultiplied() {
        CompositeTimeConfig config = compositeTimeConfig(new BigDecimal("1000.00"), new BigDecimal("5.00"));
        LocalDateTime begin = LocalDateTime.of(2026, 1, 1, 8, 0);
        BillingSegmentResult result = new CompositeTimeRule().calculate(
                ctx(begin, begin.plusHours(48), BConstants.CalculationMode.DURATION_GLOBAL),
                config, PromotionAggregate.builder().build());

        // 48h = 2 周期，periodCap 5 × 2 = 10 元（单 period 全天，封顶 × 周期数）
        assertEquals(0, new BigDecimal("10.00").compareTo(result.getChargedAmount()));
    }

    // ==================== 辅助方法 ====================

    private static RelativeTimeConfig relativeTimeConfig(BigDecimal maxChargeOneCycle) {
        return RelativeTimeConfig.builder()
                .id("rel-duration")
                .maxChargeOneCycle(maxChargeOneCycle)
                .periods(List.of(RelativeTimePeriod.builder()
                        .beginMinute(0)
                        .endMinute(1440)
                        .unitMinutes(60)
                        .unitPrice(new BigDecimal("2.00"))
                        .build()))
                .build();
    }

    private static NaturalTimeConfig naturalTimeConfig(BigDecimal maxChargeOneDay) {
        return NaturalTimeConfig.builder()
                .id("nat-duration")
                .maxChargeOneDay(maxChargeOneDay)
                .unitMinutes(60)
                .crossPeriodMode(CrossPeriodMode.BEGIN_TIME_TRUNCATE)
                .periods(List.of(
                        NaturalPeriod.builder().beginMinute(8 * 60).endMinute(20 * 60)
                                .unitPrice(new BigDecimal("2.00")).build(),
                        NaturalPeriod.builder().beginMinute(20 * 60).endMinute(8 * 60)
                                .unitPrice(new BigDecimal("1.00")).build()
                ))
                .build();
    }

    private static CompositeTimeConfig compositeTimeConfig(BigDecimal maxChargeOneCycle, BigDecimal periodCap) {
        return CompositeTimeConfig.builder()
                .id("comp-duration")
                .maxChargeOneCycle(maxChargeOneCycle)
                .periods(List.of(CompositePeriod.builder()
                        .beginMinute(0)
                        .endMinute(1440)
                        .unitMinutes(60)
                        .maxCharge(periodCap)
                        .crossPeriodMode(CrossPeriodMode.BEGIN_TIME_TRUNCATE)
                        .naturalPeriods(List.of(NaturalPeriod.builder()
                                .beginMinute(0)
                                .endMinute(1440)
                                .unitPrice(new BigDecimal("2.00"))
                                .build()))
                        .build()))
                .build();
    }

    private static BillingContext ctx(LocalDateTime begin, LocalDateTime end, BConstants.CalculationMode mode) {
        CalculationWindow window = new CalculationWindow();
        window.setCalculationBegin(begin);
        window.setCalculationEnd(end);
        BillingSegment segment = BillingSegment.builder().beginTime(begin).build();
        return BillingContext.builder()
                .beginTime(begin)
                .endTime(end)
                .calculationMode(mode)
                .segment(segment)
                .window(window)
                .build();
    }
}
