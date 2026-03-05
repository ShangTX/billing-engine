package cn.shang.charging.promotion.rules.minutes;

import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.PromotionRuleConfig;
import lombok.*;
import lombok.experimental.Accessors;

@Accessors(chain = true)
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Data
public class FreeMinutesPromotionConfig implements PromotionRuleConfig {
    String id;
    String type = BConstants.PromotionRuleType.FREE_MINUTES;
    Integer priority;
    int minutes;

}
