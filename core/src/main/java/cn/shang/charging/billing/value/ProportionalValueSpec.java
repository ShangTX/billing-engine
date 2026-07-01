package cn.shang.charging.billing.value;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 按时长比例投影的单元求值模型。
 * <p>
 * 用于 PROPORTIONAL 模式下的不足单元（截断单元）：单元内查询时点金额按已过时长线性累计，
 * {@code currentAmount = unitPrice × elapsed / unitMinutes}。
 * 与 {@link FixedValueSpec}（固定全额）不同，反映"不足单元按比例计费"的语义。
 */
public class ProportionalValueSpec implements UnitValueSpec {

    private final BigDecimal unitPrice;
    private final int unitMinutes;

    public ProportionalValueSpec(BigDecimal unitPrice, int unitMinutes) {
        this.unitPrice = Objects.requireNonNull(unitPrice, "unitPrice cannot be null");
        if (unitMinutes <= 0) {
            throw new IllegalArgumentException("unitMinutes must be positive");
        }
        if (unitPrice.signum() < 0) {
            throw new IllegalArgumentException("unitPrice must be >= 0");
        }
        this.unitMinutes = unitMinutes;
    }

    @Override
    public UnitValueProjection project(LocalDateTime queryTime, LocalDateTime unitBeginTime, LocalDateTime unitEndTime) {
        Objects.requireNonNull(queryTime, "queryTime cannot be null");
        Objects.requireNonNull(unitBeginTime, "unitBeginTime cannot be null");
        Objects.requireNonNull(unitEndTime, "unitEndTime cannot be null");

        long elapsed = Duration.between(unitBeginTime, queryTime).toMinutes();
        if (elapsed < 0) {
            elapsed = 0;
        }
        if (elapsed > unitMinutes) {
            elapsed = unitMinutes;
        }
        BigDecimal currentAmount = unitPrice.multiply(BigDecimal.valueOf(elapsed))
                .divide(BigDecimal.valueOf(unitMinutes), 2, RoundingMode.HALF_UP);
        return new UnitValueProjection(currentAmount, unitEndTime);
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public int getUnitMinutes() {
        return unitMinutes;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ProportionalValueSpec that)) return false;
        return unitMinutes == that.unitMinutes && Objects.equals(unitPrice, that.unitPrice);
    }

    @Override
    public int hashCode() {
        return Objects.hash(unitPrice, unitMinutes);
    }
}
