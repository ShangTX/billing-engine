package cn.shang.charging.billing.pojo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 计费单元 - 最小计费单位，通用结构
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
public class BillingUnit {

    /**
     * 单元开始时间
     */
    private LocalDateTime beginTime;

    /**
     * 单元结束时间
     */
    private LocalDateTime endTime;

    /**
     * 单元时长（分钟）
     */
    private int durationMinutes;

    /**
     * 单元单价（由具体规则解释）
     */
    private BigDecimal unitPrice;

    /**
     * 原始金额（应用优惠前）
     */
    private BigDecimal originalAmount;

    /**
     * 是否免费（被优惠完全覆盖）
     */
    private boolean free;

    /**
     * 是否被 calcEndTime 截断
     * 用于触发不足单元计费（IncompleteUnitChargeMode）
     */
    private Boolean isTruncated;

    /**
     * 免费原因（优惠ID等）
     */
    private String freePromotionId;

    /**
     * 实际金额（应用优惠后）
     */
    private BigDecimal chargedAmount;

    /**
     * 段内累计金额：从当前分段起点到本单元的累计 chargedAmount。
     * 不跨分段累计。
     */
    private BigDecimal accumulatedAmount;

    /**
     * 规则扩展数据，由具体规则使用
     */
    private Object ruleData;

    /**
     * 是否为 compact 单元（合并了 N 个连续相同子单元）。
     * <p>
     * 当 compact=true 时，{@link #count} 表示子单元数量，
     * {@link #durationMinutes} = count * 子单元时长。
     * compact 单元由边界驱动循环自然产出，截断单元（isTruncated=true）永不 compact。
     */
    private boolean compact;

    /**
     * compact 单元代表的子单元数量。非 compact 单元始终为 1。
     */
    @Builder.Default
    private int count = 1;
}
