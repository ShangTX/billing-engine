package cn.shang.charging.billing.value;

import java.time.LocalDateTime;
import java.util.Objects;

public final class UnitValueEvaluator {

    private UnitValueEvaluator() {
    }

    public static UnitValueProjection evaluate(
            UnitValueSpec spec,
            LocalDateTime queryTime,
            LocalDateTime unitBeginTime,
            LocalDateTime unitEndTime) {

        Objects.requireNonNull(spec, "spec cannot be null");
        Objects.requireNonNull(queryTime, "queryTime cannot be null");
        Objects.requireNonNull(unitBeginTime, "unitBeginTime cannot be null");
        Objects.requireNonNull(unitEndTime, "unitEndTime cannot be null");

        if (unitEndTime.isBefore(unitBeginTime)) {
            throw new IllegalArgumentException("unitEndTime must not be before unitBeginTime");
        }
        if (queryTime.isBefore(unitBeginTime) || queryTime.isAfter(unitEndTime)) {
            throw new IllegalArgumentException("queryTime must be within [unitBeginTime, unitEndTime]");
        }

        UnitValueProjection projection = spec.project(queryTime, unitBeginTime, unitEndTime);
        if (projection.nextChangeTime().isBefore(queryTime)) {
            throw new IllegalStateException("nextChangeTime must be >= queryTime");
        }
        return projection;
    }
}
