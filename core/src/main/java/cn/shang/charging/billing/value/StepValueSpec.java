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
}
