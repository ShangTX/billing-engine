package cn.shang.charging.billing.value;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

public class FixedValueSpec implements UnitValueSpec {

    private final BigDecimal amount;

    public FixedValueSpec(BigDecimal amount) {
        this.amount = Objects.requireNonNull(amount, "amount cannot be null");
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("amount must be >= 0");
        }
    }

    @Override
    public UnitValueProjection project(LocalDateTime queryTime, LocalDateTime unitBeginTime, LocalDateTime unitEndTime) {
        Objects.requireNonNull(queryTime, "queryTime cannot be null");
        Objects.requireNonNull(unitBeginTime, "unitBeginTime cannot be null");
        Objects.requireNonNull(unitEndTime, "unitEndTime cannot be null");
        return new UnitValueProjection(amount, unitEndTime);
    }
}
