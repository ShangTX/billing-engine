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

/**
 * 计费结果。
 * <p>
 * 计费引擎最终输出，包含计费明细（单元或时长段）、优惠使用情况、最终金额等。
 * CONTINUOUS/UNIT_BASED 模式产出 {@code units}，DURATION_PERIOD/DURATION_GLOBAL 模式产出 {@code durationSegments}。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Accessors(chain=true)
@Builder(toBuilder = true)
public class BillingResult {
    /** 计费单元明细列表（CONTINUOUS/UNIT_BASED 模式产出，时长模式为空） */
    private List<BillingUnit> units;
    /** 时长计费段明细（DURATION_PERIOD/DURATION_GLOBAL 模式产出，单元模式为 null） */
    private List<DurationSegment> durationSegments;
    /** 优惠使用情况列表（含来源 source + 等效金额 equivalentAmount） */
    private List<PromotionUsage> promotionUsages;
    /** 最终应收金额（各分段 chargedAmount 之和，单元模式与时长模式统一） */
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
