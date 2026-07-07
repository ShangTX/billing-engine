package cn.shang.charging.promotion.pojo;

import cn.shang.charging.billing.pojo.BConstants;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

/**
 * 免费分钟数描述（未时段化形式）。
 * <p>
 * 由 {@code PromotionEngine} 从优惠规则/外部优惠聚合产出，
 * 最终由策略侧 {@code FreeMinuteAllocator} 时段化为具体的 {@code FreeTimeRange}。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Accessors(chain = true)
public class FreeMinutes {

    /** 优惠ID（对应 PromotionGrant.id） */
    private String id;

    /** 免费分钟数 */
    private Integer minutes;

    /** 优先级（数字越小优先级越高，控制多免费分钟数叠加时的分配顺序） */
    private Integer priority;

    /** 优惠来源：RULE（方案内规则）/ COUPON（外部优惠券，从 PromotionGrant 透传） */
    private BConstants.PromotionSource source;

}
