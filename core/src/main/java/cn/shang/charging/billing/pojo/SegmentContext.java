package cn.shang.charging.billing.pojo;

import cn.shang.charging.promotion.ExternalPromotionPool;
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

    /**
     * 外部优惠跨段共享池（TODO-20260706-002 阶段6）。
     * <p>
     * prepareContexts 初始化一次；calculateWithContexts 每次重算前 reset，
     * 避免消去法多次迭代间剩余量污染。多段跨段去重需 calculateWithContexts
     * 重算时回写（当前未回写，单段场景已正确）。
     */
    private ExternalPromotionPool externalPool;
}