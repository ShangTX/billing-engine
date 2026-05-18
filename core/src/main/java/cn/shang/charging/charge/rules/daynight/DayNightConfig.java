package cn.shang.charging.charge.rules.daynight;

import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.RuleConfig;
import cn.shang.charging.charge.rules.compositetime.CrossPeriodMode;
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
     * 白天黑夜比例阈值（仅 BLOCK_WEIGHT 模式使用）
     */
    BigDecimal blockWeight;

    /**
     * 跨时段处理模式
     */
    @Builder.Default
    CrossPeriodMode crossPeriodMode = CrossPeriodMode.BLOCK_WEIGHT;

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

    /**
     * 是否支持简化计算，null 表示默认支持
     */
    Boolean simplifiedSupported;

    /**
     * 不完整计费单元收费模式
     * 默认 FULL_CHARGE（完整收费）
     */
    @Builder.Default
    BConstants.IncompleteUnitChargeMode incompleteUnitChargeMode = BConstants.IncompleteUnitChargeMode.FULL_CHARGE;

    /**
     * 分钟阈值（仅 THRESHOLD_MINUTES 模式使用）
     * 不完整单元时长超过此值后全额收费
     */
    Integer thresholdMinutes;

    /**
     * 比例阈值（仅 THRESHOLD_RATIO 模式使用）
     * 如 0.5 表示超过50%后全额收费
     */
    BigDecimal thresholdRatio;

}
