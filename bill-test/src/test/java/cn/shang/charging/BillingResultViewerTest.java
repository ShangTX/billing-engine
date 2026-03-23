package cn.shang.charging;

import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.BillingResult;
import cn.shang.charging.billing.pojo.BillingUnit;
import cn.shang.charging.promotion.pojo.PromotionUsage;
import cn.shang.charging.wrapper.BillingResultViewer;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BillingResultViewerTest {

    private final BillingResultViewer viewer = new BillingResultViewer();

    @Test
    void testViewAtTime_nullQueryTime_returnsOriginal() {
        BillingResult result = BillingResult.builder()
            .finalAmount(BigDecimal.TEN)
            .build();

        BillingResult view = viewer.viewAtTime(result, null);

        assertSame(result, view);
    }

    @Test
    void testViewAtTime_nullResult_returnsNull() {
        BillingResult view = viewer.viewAtTime(null, LocalDateTime.now());
        assertNull(view);
    }

    @Test
    void testViewAtTime_filtersUnits() {
        LocalDateTime t8 = LocalDateTime.of(2024, 1, 1, 8, 0);
        LocalDateTime t9 = LocalDateTime.of(2024, 1, 1, 9, 0);
        LocalDateTime t10 = LocalDateTime.of(2024, 1, 1, 10, 0);
        LocalDateTime t11 = LocalDateTime.of(2024, 1, 1, 11, 0);

        BillingUnit unit1 = BillingUnit.builder()
            .beginTime(t8).endTime(t9).chargedAmount(BigDecimal.ONE).build();
        BillingUnit unit2 = BillingUnit.builder()
            .beginTime(t9).endTime(t10).chargedAmount(BigDecimal.ONE).build();
        BillingUnit unit3 = BillingUnit.builder()
            .beginTime(t10).endTime(t11).chargedAmount(BigDecimal.ONE).build();

        BillingResult result = BillingResult.builder()
            .units(List.of(unit1, unit2, unit3))
            .finalAmount(BigDecimal.valueOf(3))
            .build();

        // queryTime = 10:00，保留 endTime <= 10:00 的单元
        BillingResult view = viewer.viewAtTime(result, t10);

        assertEquals(2, view.getUnits().size());
        assertEquals(BigDecimal.valueOf(2), view.getFinalAmount());
    }

    @Test
    void testViewAtTime_truncatesPromotionUsage() {
        LocalDateTime t8 = LocalDateTime.of(2024, 1, 1, 8, 0);
        LocalDateTime t9 = LocalDateTime.of(2024, 1, 1, 9, 0);
        LocalDateTime t10 = LocalDateTime.of(2024, 1, 1, 10, 0);

        PromotionUsage usage = PromotionUsage.builder()
            .promotionId("promo1")
            .type(BConstants.PromotionType.FREE_RANGE)
            .usedFrom(t8)
            .usedTo(t10)
            .usedMinutes(120)
            .build();

        BillingResult result = BillingResult.builder()
            .units(List.of())
            .promotionUsages(List.of(usage))
            .finalAmount(BigDecimal.ZERO)
            .build();

        // queryTime = 9:00，截取 usage
        BillingResult view = viewer.viewAtTime(result, t9);

        assertEquals(1, view.getPromotionUsages().size());
        PromotionUsage truncated = view.getPromotionUsages().get(0);
        assertEquals(t9, truncated.getUsedTo());
        assertEquals(60, truncated.getUsedMinutes());
    }

    @Test
    void testViewAtTime_preservesCarryOver() {
        BillingResult result = BillingResult.builder()
            .units(List.of())
            .finalAmount(BigDecimal.ZERO)
            .calculationEndTime(LocalDateTime.of(2024, 1, 1, 10, 0))
            .build();

        BillingResult view = viewer.viewAtTime(result, LocalDateTime.of(2024, 1, 1, 9, 0));

        // carryOver 应该保留
        assertNull(view.getCarryOver()); // 原始为 null，所以也为 null
        // calculationEndTime 应该是 queryTime
        assertEquals(LocalDateTime.of(2024, 1, 1, 9, 0), view.getCalculationEndTime());
    }
}