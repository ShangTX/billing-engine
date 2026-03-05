package cn.shang.charging.billing.pojo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 计费单元 - 最小计费粒度
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
public class BillingUnit {

    /** 计费单元开始时间 */
    private LocalDateTime beginTime;

    /** 计费单元结束时间 */
    private LocalDateTime endTime;

    /** 计费时长（分钟） */
    private Integer durationMinutes;

    /** 计费类型：DAY / NIGHT */
    private String chargeType;

    /** 单价 */
    private BigDecimal unitPrice;

    /** 本单元应收金额 */
    private BigDecimal amount;

    /** 是否免费（被优惠覆盖） */
    private Boolean isFree;

    /** 关联的优惠 ID（如果免费） */
    private String freePromotionId;
}
