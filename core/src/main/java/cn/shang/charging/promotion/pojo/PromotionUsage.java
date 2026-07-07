package cn.shang.charging.promotion.pojo;

import cn.shang.charging.billing.pojo.BConstants;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 优惠使用情况记录。
 * <p>
 * 记录某个优惠在实际计费中的使用明细：授予分钟数、实际使用分钟数、使用区间、等效金额。
 * 最终汇总到 {@code BillingResult.promotionUsages}，供调用方查询和分析。
 */
@Accessors(chain = true)
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Data
public class PromotionUsage {

    /** 优惠来源ID（对应 PromotionGrant.id 或 PromotionRuleConfig.id） */
    private String promotionId;
    /** 优惠类型：FREE_RANGE / FREE_MINUTES / SMART_FREE_MINUTES / AMOUNT / DISCOUNT */
    private BConstants.PromotionType type;
    /** 优惠来源：RULE（方案内规则）/ COUPON（外部优惠券） */
    private BConstants.PromotionSource source;

    /** 授予的总分钟数（优惠配置值） */
    private long grantedMinutes;
    /** 实际使用的分钟数（可能小于 granted，窗口用尽时） */
    private long usedMinutes;

    /** 实际使用区间的起点（计算窗口内的起始位置） */
    private LocalDateTime usedFrom;
    /** 实际使用区间的终点（计算窗口内的结束位置） */
    private LocalDateTime usedTo;

    /** 等效优惠金额：该优惠使最终金额减少的金额（精确值需开启等效金额计算） */
    private BigDecimal equivalentAmount;

}
