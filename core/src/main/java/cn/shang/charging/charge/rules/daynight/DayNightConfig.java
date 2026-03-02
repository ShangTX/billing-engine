package cn.shang.charging.charge.rules.daynight;

import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.RuleConfig;
import lombok.*;
import lombok.experimental.Accessors;

import java.math.BigDecimal;

@Data
@Builder
@Accessors(chain = true)
@AllArgsConstructor
@NoArgsConstructor
public class DayNightConfig implements RuleConfig {

    String id;

    @Builder.Default
    String type = BConstants.ChargeRuleType.DAY_NIGHT;
    /**
     * 白天时间开始分钟 0点为0
     */
    Integer dayBeginMinute;

    /**
     * 白天时间结束分钟数
     */
    Integer dayEndMinute;

    /**
     * 单位时间长度
     */
    Integer unitMinutes;

    /**
     * 白天黑夜比例
     */
    float blockWeight;

    /**
     * 白天价格
     */
    BigDecimal dayUnitPrice;

    /**
     * 夜晚价格
     */
    BigDecimal nightUnitPrice;

    /**
     * 每日限额
     */
    BigDecimal maxChargeOneDay;

}
