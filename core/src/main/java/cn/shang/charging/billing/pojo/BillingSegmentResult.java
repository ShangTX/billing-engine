package cn.shang.charging.billing.pojo;

import cn.shang.charging.promotion.pojo.PromotionAggregate;
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
 * 分段计费结果
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Accessors(chain=true)
@Builder
public class BillingSegmentResult {

    /* ========== 一、分段与时间语义 ========== */

    /** 分段ID（与 BillingSegment 对应） */
    private String segmentId;

    /** 本分段逻辑起止时间（方案层） */
    private LocalDateTime segmentStartTime;
    private LocalDateTime segmentEndTime;

    /** 本次实际参与计算的时间范围（考虑截取） */
    private LocalDateTime calculationStartTime;
    private LocalDateTime calculationEndTime;

    /* ========== 二、计费结果（核心数值） ========== */

    /** 本分段最终应收金额 */
    private BigDecimal chargedAmount;

    /** 本分段实际计费时长 分钟数（不含免费） */
    private Integer chargedDuration;

    /* ========== 三、优惠结果 ========== */

    /** 本分段内的优惠聚合结果 */
    private PromotionAggregate promotionAggregate;

    /* ========== 四、计费过程明细 ========== */

    /** 按最小计费单元拆分的明细 */
    private List<BillingUnit> billingUnits;

    /** 时长计费段明细（仅在时长计费模式下填充） */
    private List<DurationSegment> durationSegments;

    /** 计算模式标记 */
    private BConstants.CalculationMode calculationMode;

    /** 周期封顶金额（时长模式下填充，null=无封顶或未配置） */
    private BigDecimal cycleCapApplied;

    private List<PromotionUsage> promotionUsages;

    /* ========== 五、金额折扣优惠结果 ========== */

    /** 折扣前金额（规则计算结果） */
    private BigDecimal originalAmount;

    /** 折扣优惠金额（折扣减免） */
    private BigDecimal discountSavedAmount;

    /** 金额减免总额 */
    private BigDecimal amountDiscount;

    /** 最终实收金额（折扣和减免后） */
    private BigDecimal finalAmount;
}
