package cn.shang.charging.billing.pojo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.math.BigDecimal;

/**
 * 不足单元计费配置。
 * <p>
 * 用于统一描述不满一个 {@code unitMinutes} 的余数如何收费。旧的
 * {@code incompleteUnitChargeMode} / {@code thresholdMinutes} / {@code thresholdRatio}
 * 散字段仍然兼容，内置规则会优先读取本对象。
 */
@Data
@Builder
@Accessors(chain = true)
@NoArgsConstructor
@AllArgsConstructor
public class IncompleteUnitChargeSpec {

    /**
     * 不足单元收费模式。
     */
    @Builder.Default
    private BConstants.IncompleteUnitChargeMode mode = BConstants.IncompleteUnitChargeMode.FULL_CHARGE;

    /**
     * THRESHOLD_MINUTES 模式阈值（分钟）。
     */
    private Integer thresholdMinutes;

    /**
     * THRESHOLD_RATIO 模式阈值比例。
     */
    private BigDecimal thresholdRatio;
}
