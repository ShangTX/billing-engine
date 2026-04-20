package cn.shang.charging.billing.value;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public class PiecewiseTimeValueSpec implements UnitValueSpec {

    private final List<Segment> segments;

    public PiecewiseTimeValueSpec(List<Segment> segments) {
        Objects.requireNonNull(segments, "segments cannot be null");
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("segments cannot be empty");
        }
        this.segments = Collections.unmodifiableList(new ArrayList<>(segments));
    }

    @Override
    public UnitValueProjection project(LocalDateTime queryTime, LocalDateTime unitBeginTime, LocalDateTime unitEndTime) {
        Objects.requireNonNull(queryTime, "queryTime cannot be null");
        Objects.requireNonNull(unitBeginTime, "unitBeginTime cannot be null");
        Objects.requireNonNull(unitEndTime, "unitEndTime cannot be null");

        if (queryTime.isBefore(unitBeginTime) || queryTime.isAfter(unitEndTime)) {
            throw new IllegalArgumentException("queryTime must be within [unitBeginTime, unitEndTime]");
        }

        long elapsedMinutes = Duration.between(unitBeginTime, queryTime).toMinutes();
        BigDecimal amount = BigDecimal.ZERO;
        long consumed = 0;

        for (int i = 0; i < segments.size(); i++) {
            Segment segment = segments.get(i);
            long lengthMinutes = segment.duration().toMinutes();
            if (lengthMinutes <= 0) {
                throw new IllegalArgumentException("segment duration must be positive");
            }

            long segmentStart = consumed;
            long segmentEnd = consumed + lengthMinutes;
            if (elapsedMinutes < segmentStart) {
                LocalDateTime nextChange = unitBeginTime.plusMinutes(segmentStart);
                return new UnitValueProjection(amount, min(nextChange, unitEndTime));
            }

            if (segment.kind() == SegmentKind.FIXED) {
                amount = amount.add(segment.value());
            } else {
                long covered = Math.min(Math.max(elapsedMinutes - segmentStart, 0), lengthMinutes);
                amount = amount.add(segment.value().multiply(BigDecimal.valueOf(covered)));
            }

            if (elapsedMinutes < segmentEnd) {
                LocalDateTime nextChange = unitBeginTime.plusMinutes(segmentEnd);
                return new UnitValueProjection(normalize(amount), min(nextChange, unitEndTime));
            }

            consumed = segmentEnd;
        }

        return new UnitValueProjection(normalize(amount), unitEndTime);
    }

    private static BigDecimal normalize(BigDecimal value) {
        return value.stripTrailingZeros().scale() < 0
                ? value.setScale(0, RoundingMode.UNNECESSARY)
                : value.stripTrailingZeros();
    }

    private static LocalDateTime min(LocalDateTime a, LocalDateTime b) {
        return a.isBefore(b) ? a : b;
    }

    public record Segment(Duration duration, SegmentKind kind, BigDecimal value) {

        public Segment {
            Objects.requireNonNull(duration, "duration cannot be null");
            Objects.requireNonNull(kind, "kind cannot be null");
            Objects.requireNonNull(value, "value cannot be null");
            if (duration.isNegative() || duration.isZero()) {
                throw new IllegalArgumentException("duration must be positive");
            }
        }

        public static Segment fixed(Duration duration, BigDecimal amount) {
            return new Segment(duration, SegmentKind.FIXED, amount);
        }

        public static Segment linear(Duration duration, BigDecimal perMinute) {
            return new Segment(duration, SegmentKind.LINEAR, perMinute);
        }
    }

    public enum SegmentKind {
        FIXED,
        LINEAR
    }
}
