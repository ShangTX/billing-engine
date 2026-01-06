package cn.shang.charging.promotion.pojo;

import cn.shang.charging.billing.pojo.BConstants;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 优惠使用情况
 */
@Accessors(chain = true)
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Data
public class PromotionUsage {

    // 优惠来源id
    private String promotionId;
    // 优惠类型
    private BConstants.PromotionType type;

    private long grantedMinutes;
    private long usedMinutes;

    private LocalDateTime usedFrom;
    private LocalDateTime usedTo;

}
