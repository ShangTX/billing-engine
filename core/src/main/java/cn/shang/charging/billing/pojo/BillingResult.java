package cn.shang.charging.billing.pojo;


import cn.shang.charging.promotion.pojo.PromotionUsage;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Accessors(chain=true)
@Builder(toBuilder = true)
public class BillingResult {
    private List<BillingUnit> units; // 计费细节（CONTINUOUS/UNIT_BASED）
    private List<DurationSegment> durationSegments; // 时长计费模式下的计费段（DURATION_PERIOD/DURATION_GLOBAL）
    private List<PromotionUsage> promotionUsages; // 优惠使用情况（含 source 来源 + equivalentAmount 等效金额）
    private BigDecimal finalAmount;

    /**
     * 等效优惠金额汇总（TODO-20260706-003）。
     * <p>
     * 仅在 {@link BillingRequest#getEquivalentAmountSpec()} 非 {@code null} 时计算，
     * 为所有命中优惠的等效金额之和；未计算时为 {@code null}。
     */
    private BigDecimal totalEquivalentAmount;

    /**
     * 计算窗口结束时间（= {@code window.calculationEnd}，汇总最后分段）。
     * <p>
     * 各 Strategy 设置为 {@code window.getCalculationEnd()}，{@link ResultAssembler} 取最后一个分段的值。
     * 多分段时为最后分段的计算结束时间（通常 = {@code request.endTime}）。
     */
    private LocalDateTime calculationEndTime;
}
