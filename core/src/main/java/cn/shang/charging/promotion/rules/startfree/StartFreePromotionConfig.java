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

    /**
     * 是否启用查询时间校验
     * 当为 true 时，queryTime 必须落在免费时间段内才生效，
     * 否则该规则完全失效（不产生任何免费时段）
     */
    Boolean validateQueryTime;
}
