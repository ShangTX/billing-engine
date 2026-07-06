package cn.shang.charging.charge.rules.daynight;

import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.BillingContext;
import cn.shang.charging.billing.pojo.BillingSegmentResult;
import cn.shang.charging.charge.rules.BillingRule;
import cn.shang.charging.promotion.pojo.PromotionAggregate;

import java.util.EnumSet;
import java.util.Set;

/**
 * 日夜分时段计费规则门面。
 * <p>
 * 按 {@link BConstants.CalculationMode} 分派到独立策略实现，自身只分派不扛计费逻辑：
 * <ul>
 *   <li>DURATION_PERIOD / DURATION_GLOBAL → {@link DayNightDurationStrategy}（时长计费类）</li>
 *   <li>UNIT_BASED → {@link DayNightUnitBasedStrategy}（固定单元对齐）</li>
 *   <li>CONTINUOUS → {@link ContinuousStrategy}（边界驱动切断）</li>
 * </ul>
 * 一个 {@code dayNight} type 注册本门面，支持四种计算模式，不需覆盖注册。
 * <p>
 * TODO-20260702-002 阶段4：CONTINUOUS 逻辑下沉到 ContinuousStrategy，门面回归纯分派。
 */
public class DayNightRule implements BillingRule<DayNightConfig> {

    private final DayNightDurationStrategy durationStrategy = new DayNightDurationStrategy();
    private final DayNightUnitBasedStrategy unitBasedStrategy = new DayNightUnitBasedStrategy();
    private final ContinuousStrategy continuousStrategy = new ContinuousStrategy();

    @Override
    public Class<DayNightConfig> configClass() {
        return DayNightConfig.class;
    }

    @Override
    public Set<BConstants.CalculationMode> supportedCalculationModes() {
        return EnumSet.of(BConstants.CalculationMode.CONTINUOUS, BConstants.CalculationMode.UNIT_BASED,
                BConstants.CalculationMode.DURATION_PERIOD, BConstants.CalculationMode.DURATION_GLOBAL);
    }

    @Override
    public BillingSegmentResult calculate(BillingContext context, DayNightConfig config, PromotionAggregate promotionAggregate) {
        BConstants.CalculationMode mode = context.getCalculationMode();
        if (mode == null) mode = BConstants.CalculationMode.CONTINUOUS;
        return switch (mode) {
            case DURATION_PERIOD, DURATION_GLOBAL ->
                    durationStrategy.calculate(context, config, promotionAggregate, mode);
            case UNIT_BASED -> unitBasedStrategy.calculate(context, config, promotionAggregate);
            case CONTINUOUS -> continuousStrategy.calculate(context, config, promotionAggregate);
        };
    }
}
