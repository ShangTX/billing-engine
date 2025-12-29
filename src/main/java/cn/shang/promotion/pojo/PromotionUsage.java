package cn.shang.promotion.pojo;

import cn.shang.billing.pojo.BConstants;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 优惠使用情况
 */
@Data
public class PromotionUsage {

    // 优惠来源id
    private final String promotionId;
    // 优惠类型
    private final BConstants.PromotionType type;

    private final long grantedMinutes;
    private final long usedMinutes;

    private final LocalDateTime usedFrom;
    private final LocalDateTime usedTo;

}
