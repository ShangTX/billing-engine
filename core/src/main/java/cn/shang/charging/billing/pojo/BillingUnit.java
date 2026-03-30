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
     * 用于 CONTINUE 模式恢复截断单元
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
     * 规则扩展数据，由具体规则使用
     */
    private Object ruleData;

    /**
     * 此单元是否是通过 CONTINUE 模式合并生成的
     * 如果为 true，此单元的开始时间在上次计算的截断单元位置
     */
    private Boolean mergedFromPrevious;
}
