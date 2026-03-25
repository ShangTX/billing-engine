package cn.shang.charging;

import cn.shang.charging.billing.BillingService;
import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.BillingRequest;
import cn.shang.charging.billing.pojo.BillingResult;
import cn.shang.charging.billing.pojo.SegmentContext;
import cn.shang.charging.promotion.pojo.PromotionAggregate;
import cn.shang.charging.promotion.pojo.PromotionUsage;
import cn.shang.charging.wrapper.PromotionEquivalentCalculator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PromotionEquivalentCalculatorTest {

    private BillingService billingService;
    private PromotionEquivalentCalculator calculator;

    @BeforeEach
    void setUp() {
        billingService = mock(BillingService.class);
        calculator = new PromotionEquivalentCalculator(billingService);
    }

    @Test
    void testCalculate_noPromotions_returnsEmptyMap() {
        BillingRequest request = new BillingRequest();
        BillingResult result = BillingResult.builder()
            .finalAmount(BigDecimal.ZERO)
            .promotionUsages(List.of())
            .build();

        when(billingService.prepareContexts(request)).thenReturn(List.of());
        when(billingService.calculateWithContexts(any(), eq(request))).thenReturn(result);

        Map<String, BigDecimal> equivalents = calculator.calculate(request);

        assertTrue(equivalents.isEmpty());
    }

    @Test
    void testCalculate_singlePromotion() {
        BillingRequest request = new BillingRequest();

        PromotionUsage usage = PromotionUsage.builder()
            .promotionId("promo1")
            .type(BConstants.PromotionType.FREE_RANGE)
            .usedFrom(LocalDateTime.of(2024, 1, 1, 9, 0))
            .usedTo(LocalDateTime.of(2024, 1, 1, 10, 0))
            .build();

        BillingResult withPromo = BillingResult.builder()
            .finalAmount(BigDecimal.valueOf(10))
            .promotionUsages(List.of(usage))
            .build();

        BillingResult withoutPromo = BillingResult.builder()
            .finalAmount(BigDecimal.valueOf(20))
            .promotionUsages(List.of())
            .build();

        SegmentContext context = SegmentContext.builder()
            .promotionAggregate(mock(PromotionAggregate.class))
            .build();

        when(billingService.prepareContexts(request)).thenReturn(List.of(context));
        when(billingService.calculateWithContexts(any(), eq(request)))
            .thenReturn(withPromo)
            .thenReturn(withoutPromo);

        Map<String, BigDecimal> equivalents = calculator.calculate(request);

        assertEquals(1, equivalents.size());
        assertEquals(BigDecimal.valueOf(10), equivalents.get("promo1"));
    }

    @Test
    void testCalculate_multiplePromotions_sortedByBeginTime() {
        BillingRequest request = new BillingRequest();

        PromotionUsage usage1 = PromotionUsage.builder()
            .promotionId("promo1")
            .type(BConstants.PromotionType.FREE_RANGE)
            .usedFrom(LocalDateTime.of(2024, 1, 1, 8, 0))
            .usedTo(LocalDateTime.of(2024, 1, 1, 9, 0))
            .build();

        PromotionUsage usage2 = PromotionUsage.builder()
            .promotionId("promo2")
            .type(BConstants.PromotionType.FREE_RANGE)
            .usedFrom(LocalDateTime.of(2024, 1, 1, 10, 0))
            .usedTo(LocalDateTime.of(2024, 1, 1, 11, 0))
            .build();

        BillingResult r0 = BillingResult.builder()
            .finalAmount(BigDecimal.valueOf(10))
            .promotionUsages(List.of(usage1, usage2))
            .build();

        BillingResult r1 = BillingResult.builder()
            .finalAmount(BigDecimal.valueOf(15))
            .promotionUsages(List.of(usage2))
            .build();

        BillingResult r2 = BillingResult.builder()
            .finalAmount(BigDecimal.valueOf(20))
            .promotionUsages(List.of())
            .build();

        SegmentContext context = SegmentContext.builder()
            .promotionAggregate(mock(PromotionAggregate.class))
            .build();

        when(billingService.prepareContexts(request)).thenReturn(List.of(context));
        when(billingService.calculateWithContexts(any(), eq(request)))
            .thenReturn(r0)
            .thenReturn(r1)
            .thenReturn(r2);

        Map<String, BigDecimal> equivalents = calculator.calculate(request);

        assertEquals(2, equivalents.size());
        assertEquals(BigDecimal.valueOf(5), equivalents.get("promo1"));
        assertEquals(BigDecimal.valueOf(5), equivalents.get("promo2"));
    }

    @Test
    void testCalculate_negativeEquivalent_clampedToZero() {
        BillingRequest request = new BillingRequest();

        PromotionUsage usage = PromotionUsage.builder()
            .promotionId("promo1")
            .type(BConstants.PromotionType.FREE_RANGE)
            .usedFrom(LocalDateTime.of(2024, 1, 1, 9, 0))
            .usedTo(LocalDateTime.of(2024, 1, 1, 10, 0))
            .build();

        BillingResult withPromo = BillingResult.builder()
            .finalAmount(BigDecimal.valueOf(20))
            .promotionUsages(List.of(usage))
            .build();

        BillingResult withoutPromo = BillingResult.builder()
            .finalAmount(BigDecimal.valueOf(10))
            .promotionUsages(List.of())
            .build();

        SegmentContext context = SegmentContext.builder()
            .promotionAggregate(mock(PromotionAggregate.class))
            .build();

        when(billingService.prepareContexts(request)).thenReturn(List.of(context));
        when(billingService.calculateWithContexts(any(), eq(request)))
            .thenReturn(withPromo)
            .thenReturn(withoutPromo);

        Map<String, BigDecimal> equivalents = calculator.calculate(request);

        assertEquals(BigDecimal.ZERO, equivalents.get("promo1"));
    }
}