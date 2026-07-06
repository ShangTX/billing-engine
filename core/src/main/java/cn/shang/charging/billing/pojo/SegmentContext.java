package cn.shang.charging.billing.pojo;

import cn.shang.charging.promotion.ExternalPromotionPool;
import cn.shang.charging.promotion.pojo.PromotionAggregate;
import cn.shang.charging.promotion.pojo.PromotionGrant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

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

    /**
     * 外部优惠源列表（TODO-20260706-003）。
     * <p>
     * prepareContexts 设为 {@code request.getExternalPromotions()}；
     * {@link cn.shang.charging.billing.PromotionEquivalentCalculator#cloneAndExclude}
     * 在源层排除指定 id 后设为过滤后的列表。calculateWithContexts 用本字段 reset externalPool，
     * 使源层排除在重放 evaluate 时生效。
     */
    private List<PromotionGrant> sourceExternalPromotions;
}