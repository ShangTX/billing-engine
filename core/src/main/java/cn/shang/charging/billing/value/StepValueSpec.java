package cn.shang.charging.billing.value;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

public class StepValueSpec implements UnitValueSpec {

    private final LocalDateTime switchTime;
    private final BigDecimal beforeAmount;
    private final BigDecimal afterAmount;

    public StepValueSpec(LocalDateTime switchTime, BigDecimal beforeAmount, BigDecimal afterAmount) {
        this.switchTime = Objects.requireNonNull(switchTime, "switchTime cannot be null");
        this.beforeAmount = Objects.requireNonNull(beforeAmount, "beforeAmount cannot be null");
        this.afterAmount = Objects.requireNonNull(afterAmount, "afterAmount cannot be null");
        if (beforeAmount.signum() < 0) {
            throw new IllegalArgumentException("beforeAmount must be >= 0");
        }
        if (afterAmount.signum() < 0) {
            throw new IllegalArgumentException("afterAmount must be >= 0");
        }
    }

    @Override
    public UnitValueProjection project(LocalDateTime queryTime, LocalDateTime unitBeginTime, LocalDateTime unitEndTime) {
        Objects.requireNonNull(queryTime, "queryTime cannot be null");
        Objects.requireNonNull(unitBeginTime, "unitBeginTime cannot be null");
        Objects.requireNonNull(unitEndTime, "unitEndTime cannot be null");

        if (queryTime.isBefore(switchTime)) {
            LocalDateTime next = switchTime.isBefore(unitEndTime) ? switchTime : unitEndTime;
            return new UnitValueProjection(beforeAmount, next);
        }
        return new UnitValueProjection(afterAmount, unitEndTime);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof StepValueSpec that)) return false;
        return switchTime.equals(that.switchTime)
                && beforeAmount.compareTo(that.beforeAmount) == 0
                && afterAmount.compareTo(that.afterAmount) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(switchTime,
                beforeAmount.stripTrailingZeros(),
                afterAmount.stripTrailingZeros());
    }
}
