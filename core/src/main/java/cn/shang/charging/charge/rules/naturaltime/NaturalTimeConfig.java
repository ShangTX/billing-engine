package cn.shang.charging.charge.rules.naturaltime;

import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.IncompleteUnitChargeSpec;
import cn.shang.charging.billing.pojo.RuleConfig;
import cn.shang.charging.charge.rules.compositetime.NaturalPeriod;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.util.List;

/**
 * 多自然时段计费配置
 * <p>
 * 核心逻辑：
 * 1. 24 小时自然周期，按自然时段划分
 * 2. 每个时段有独立价格，统一单元时长
 * 3. 支持跨时段处理模式配置
 * 4. 支持每日封顶
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
public class NaturalTimeConfig implements RuleConfig {

    private String id;

    @Builder.Default
    private String type = BConstants.ChargeRuleType.NATURAL_TIME;

    /**
     * 自然时段列表
     * 必须满足：
     * - beginMinute / endMinute 为一天内分钟点
     * - endMinute 小于 beginMinute 表示跨到次日
     * - 00:00-00:00（0-0）表示全天，规则内部按 0-1440 处理
     * - 相邻时段按配置顺序首尾相连，并在环形 24 小时时间轴上刚好覆盖全天
     * - 兼容旧配置 endMinute = 1440，但新接入推荐用 0 表示 00:00
     */
    private List<NaturalPeriod> periods;

    /**
     * 计费单元长度（分钟），统一时长
     */
    private int unitMinutes;

    /**
     * 每日封顶金额（可选）
     */
    private BigDecimal maxChargeOneDay;

    /**
     * 是否支持简化计算，null 表示默认支持
     */
    private Boolean simplifiedSupported;

    /**
     * 不足单元计费配置对象。优先于旧散字段读取。
     */
    private IncompleteUnitChargeSpec incompleteUnitChargeSpec;

    /**
     * 不完整计费单元收费模式
     * 默认 FULL_CHARGE（完整收费）
     */
    @Builder.Default
    private BConstants.IncompleteUnitChargeMode incompleteUnitChargeMode = BConstants.IncompleteUnitChargeMode.FULL_CHARGE;

    /**
     * 分钟阈值（仅 THRESHOLD_MINUTES 模式使用）
     */
    private Integer thresholdMinutes;

    /**
     * 比例阈值（仅 THRESHOLD_RATIO 模式使用）
     */
    private BigDecimal thresholdRatio;

    @Override
    public BConstants.IncompleteUnitChargeMode getIncompleteUnitChargeMode() {
        if (incompleteUnitChargeSpec != null && incompleteUnitChargeSpec.getMode() != null) {
            return incompleteUnitChargeSpec.getMode();
        }
        return incompleteUnitChargeMode != null
                ? incompleteUnitChargeMode
                : BConstants.IncompleteUnitChargeMode.FULL_CHARGE;
    }

    @Override
    public Integer getThresholdMinutes() {
        if (incompleteUnitChargeSpec != null && incompleteUnitChargeSpec.getThresholdMinutes() != null) {
            return incompleteUnitChargeSpec.getThresholdMinutes();
        }
        return thresholdMinutes;
    }

    @Override
    public BigDecimal getThresholdRatio() {
        if (incompleteUnitChargeSpec != null && incompleteUnitChargeSpec.getThresholdRatio() != null) {
            return incompleteUnitChargeSpec.getThresholdRatio();
        }
        return thresholdRatio;
    }
}
