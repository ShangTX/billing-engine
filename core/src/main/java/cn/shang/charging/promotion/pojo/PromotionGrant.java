package cn.shang.charging.promotion.pojo;

import cn.shang.charging.billing.pojo.BConstants;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 可计算的优惠
 */
@Data
@Builder
@Accessors(chain = true)
@AllArgsConstructor
@NoArgsConstructor
public class PromotionGrant {

    String id; // id

    BConstants.PromotionType type; // 优惠类型

    BConstants.PromotionSource source; // 优惠来源 TODO 放到调用端

    LocalDateTime beginTime; // 时间段开始

    LocalDateTime endTime; // 时间段结束

    Integer freeMinutes; // 免费分钟数

    Integer priority; // 优先级

}
