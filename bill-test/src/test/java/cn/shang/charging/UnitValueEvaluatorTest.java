package cn.shang.charging;

import cn.shang.charging.billing.value.FixedValueSpec;
import cn.shang.charging.billing.value.PiecewiseTimeValueSpec;
import cn.shang.charging.billing.value.StepValueSpec;
import cn.shang.charging.billing.value.UnitValueEvaluator;
import cn.shang.charging.billing.value.UnitValueProjection;
import cn.shang.charging.billing.value.UnitValueSpec;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UnitValueEvaluatorTest {

    @Test
    void fixedValueSpec_returnsStableAmountAndUnitEndAsNextChange() {
        LocalDateTime begin = LocalDateTime.of(2026, 1, 1, 9, 0);
        LocalDateTime end = LocalDateTime.of(2026, 1, 1, 10, 0);
        LocalDateTime query = LocalDateTime.of(2026, 1, 1, 9, 25);

        FixedValueSpec spec = new FixedValueSpec(BigDecimal.valueOf(8.5));
        UnitValueProjection projection = UnitValueEvaluator.evaluate(spec, query, begin, end);

        assertEquals(BigDecimal.valueOf(8.5), projection.currentAmount());
        assertEquals(end, projection.nextChangeTime());
    }

    @Test
    void stepValueSpec_switchesAtConfiguredBoundary() {
        LocalDateTime begin = LocalDateTime.of(2026, 1, 1, 9, 0);
        LocalDateTime boundary = LocalDateTime.of(2026, 1, 1, 9, 30);
        LocalDateTime end = LocalDateTime.of(2026, 1, 1, 10, 0);

        StepValueSpec spec = new StepValueSpec(boundary, BigDecimal.valueOf(3), BigDecimal.valueOf(6));

        UnitValueProjection beforeBoundary =
                UnitValueEvaluator.evaluate(spec, LocalDateTime.of(2026, 1, 1, 9, 20), begin, end);
        UnitValueProjection atBoundary =
                UnitValueEvaluator.evaluate(spec, LocalDateTime.of(2026, 1, 1, 9, 30), begin, end);

        assertEquals(BigDecimal.valueOf(3), beforeBoundary.currentAmount());
        assertEquals(boundary, beforeBoundary.nextChangeTime());
        assertEquals(BigDecimal.valueOf(6), atBoundary.currentAmount());
        assertEquals(end, atBoundary.nextChangeTime());
    }

    @Test
    void piecewiseTimeValueSpec_accumulatesBySegment() {
        LocalDateTime begin = LocalDateTime.of(2026, 1, 1, 9, 0);
        LocalDateTime end = LocalDateTime.of(2026, 1, 1, 10, 0);

        PiecewiseTimeValueSpec spec = new PiecewiseTimeValueSpec(List.of(
                PiecewiseTimeValueSpec.Segment.fixed(Duration.ofMinutes(10), BigDecimal.valueOf(3)),
                PiecewiseTimeValueSpec.Segment.linear(Duration.ofMinutes(20), BigDecimal.valueOf(0.5)),
                PiecewiseTimeValueSpec.Segment.fixed(Duration.ofMinutes(30), BigDecimal.valueOf(2))
        ));

        UnitValueProjection at0925 =
                UnitValueEvaluator.evaluate(spec, LocalDateTime.of(2026, 1, 1, 9, 25), begin, end);
        UnitValueProjection at0945 =
                UnitValueEvaluator.evaluate(spec, LocalDateTime.of(2026, 1, 1, 9, 45), begin, end);

        assertEquals(0, BigDecimal.valueOf(10.5).compareTo(at0925.currentAmount()));
        assertEquals(LocalDateTime.of(2026, 1, 1, 9, 30), at0925.nextChangeTime());

        assertEquals(0, BigDecimal.valueOf(15).compareTo(at0945.currentAmount()));
        assertEquals(end, at0945.nextChangeTime());
    }

    @Test
    void evaluator_rejectsProjectionBeyondUnitEnd() {
        LocalDateTime begin = LocalDateTime.of(2026, 1, 1, 9, 0);
        LocalDateTime end = LocalDateTime.of(2026, 1, 1, 10, 0);
        LocalDateTime query = LocalDateTime.of(2026, 1, 1, 9, 10);

        UnitValueSpec badSpec = (q, b, e) -> new UnitValueProjection(BigDecimal.ONE, e.plusMinutes(1));

        assertThrows(IllegalStateException.class, () -> UnitValueEvaluator.evaluate(badSpec, query, begin, end));
    }

    @Test
    void evaluator_rejectsProjectionThatDoesNotAdvanceBeforeUnitEnd() {
        LocalDateTime begin = LocalDateTime.of(2026, 1, 1, 9, 0);
        LocalDateTime end = LocalDateTime.of(2026, 1, 1, 10, 0);
        LocalDateTime query = LocalDateTime.of(2026, 1, 1, 9, 10);

        UnitValueSpec badSpec = (q, b, e) -> new UnitValueProjection(BigDecimal.ONE, q);

        assertThrows(IllegalStateException.class, () -> UnitValueEvaluator.evaluate(badSpec, query, begin, end));
    }

    @Test
    void specs_rejectNegativeValues() {
        assertThrows(IllegalArgumentException.class,
                () -> new FixedValueSpec(BigDecimal.valueOf(-1)));
        assertThrows(IllegalArgumentException.class,
                () -> new StepValueSpec(LocalDateTime.of(2026, 1, 1, 9, 30), BigDecimal.valueOf(-1), BigDecimal.ONE));
        assertThrows(IllegalArgumentException.class,
                () -> new StepValueSpec(LocalDateTime.of(2026, 1, 1, 9, 30), BigDecimal.ONE, BigDecimal.valueOf(-1)));
        assertThrows(IllegalArgumentException.class,
                () -> PiecewiseTimeValueSpec.Segment.fixed(Duration.ofMinutes(10), BigDecimal.valueOf(-1)));
        assertThrows(IllegalArgumentException.class,
                () -> PiecewiseTimeValueSpec.Segment.linear(Duration.ofMinutes(10), BigDecimal.valueOf(-0.1)));
    }

    @Test
    void piecewiseTimeValueSpec_semanticsAreExplicit() {
        LocalDateTime begin = LocalDateTime.of(2026, 1, 1, 9, 0);
        LocalDateTime end = LocalDateTime.of(2026, 1, 1, 9, 20);

        PiecewiseTimeValueSpec spec = new PiecewiseTimeValueSpec(List.of(
                PiecewiseTimeValueSpec.Segment.entryLumpSum(Duration.ofMinutes(10), BigDecimal.valueOf(5)),
                PiecewiseTimeValueSpec.Segment.perMinuteRate(Duration.ofMinutes(10), BigDecimal.valueOf(0.5))
        ));

        UnitValueProjection insideFirstSegment =
                UnitValueEvaluator.evaluate(spec, LocalDateTime.of(2026, 1, 1, 9, 4), begin, end);
        UnitValueProjection insideSecondSegment =
                UnitValueEvaluator.evaluate(spec, LocalDateTime.of(2026, 1, 1, 9, 12), begin, end);

        assertEquals(0, BigDecimal.valueOf(5).compareTo(insideFirstSegment.currentAmount()));
        assertEquals(LocalDateTime.of(2026, 1, 1, 9, 10), insideFirstSegment.nextChangeTime());

        assertEquals(0, BigDecimal.valueOf(6).compareTo(insideSecondSegment.currentAmount()));
        assertEquals(end, insideSecondSegment.nextChangeTime());
    }
}
