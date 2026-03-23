package cn.shang.charging.billing.pojo;

import cn.shang.charging.promotion.pojo.PromotionAggregate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 分段计算上下文
 * 包含计算所需的所有信息，可独立计算
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SegmentContext {
    /**
     * 分段ID
     */
    private String segmentId;

    /**
     * 计费上下文
     */
    private BillingContext billingContext;

    /**
     * 优惠聚合结果
     */
    private PromotionAggregate promotionAggregate;
}