package cn.shang.charging.promotion.rules.startfree;

import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.PromotionRuleConfig;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

/**
 * 前N分钟免费优惠配置
 */
@Data
@Builder
@Accessors(chain = true)
@AllArgsConstructor
@NoArgsConstructor
public class StartFreePromotionConfig implements PromotionRuleConfig {

    String id;

    @Builder.Default
    String type = BConstants.PromotionRuleType.START_FREE;

    Integer priority;

    int minutes;
}
