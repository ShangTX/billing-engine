package cn.shang.charging.billing.value;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

public record UnitValueProjection(BigDecimal currentAmount, LocalDateTime nextChangeTime) {

    public UnitValueProjection {
        Objects.requireNonNull(currentAmount, "currentAmount cannot be null");
        Objects.requireNonNull(nextChangeTime, "nextChangeTime cannot be null");
    }
}
