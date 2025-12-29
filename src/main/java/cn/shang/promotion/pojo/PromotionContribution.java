package cn.shang.promotion.pojo;

import cn.shang.billing.pojo.BConstants;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 可计算的优惠
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class PromotionContribution {

    Long id; // id

    BConstants.PromotionType type; // 优惠类型

    BConstants.PromotionSource source; // 优惠来源

    LocalDateTime beginTime; // 时间段开始

    LocalDateTime endTime; // 时间段结束

    Integer freeMinutes; // 免费分钟数

    Integer priority; // 优先级

}
