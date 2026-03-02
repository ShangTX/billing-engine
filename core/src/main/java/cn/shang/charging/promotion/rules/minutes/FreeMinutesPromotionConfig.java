package cn.shang.charging.promotion.rules.minutes;

import cn.shang.charging.billing.pojo.PromotionRuleConfig;
import lombok.*;
import lombok.experimental.Accessors;

@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@Builder
@Data
public class FreeMinutesPromotionConfig extends PromotionRuleConfig {

    int minutes;

}
