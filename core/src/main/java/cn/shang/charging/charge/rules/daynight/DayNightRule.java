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
 * 按 {@link BConstants.BillingMode}（CONTINUOUS/UNIT_BASED）和 {@link BConstants.DurationMode}
 * （PERIOD/GLOBAL）分派到独立策略实现，自身只分派不扛计费逻辑：
 * <ul>
 *   <li>DurationMode ≠ NONE → {@link DayNightDurationStrategy}（时长计费类）</li>
 *   <li>BillingMode = UNIT_BASED → {@link DayNightUnitBasedStrategy}（固定单元对齐）</li>
 *   <li>BillingMode = CONTINUOUS → {@link ContinuousStrategy}（边界驱动切断）</li>
 * </ul>
 * 一个 {@code dayNight} type 注册本门面，支持 CONTINUOUS/UNIT_BASED/时长多模式，不需覆盖注册。
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
    public Set<BConstants.BillingMode> supportedModes() {
        return EnumSet.of(BConstants.BillingMode.CONTINUOUS, BConstants.BillingMode.UNIT_BASED);
    }

    @Override
    public Set<BConstants.DurationMode> supportedDurationModes() {
        return EnumSet.of(BConstants.DurationMode.PERIOD, BConstants.DurationMode.GLOBAL);
    }

    @Override
    public BillingSegmentResult calculate(BillingContext context, DayNightConfig config, PromotionAggregate promotionAggregate) {
        // 时长计费类：委托 DurationStrategy
        BConstants.DurationMode durationMode = context.getDurationMode();
        if (durationMode != null && durationMode != BConstants.DurationMode.NONE) {
            return durationStrategy.calculate(context, config, promotionAggregate, durationMode);
        }
        // 单元计费类 UNIT_BASED：委托 UnitBasedStrategy
        if (context.getBillingMode() == BConstants.BillingMode.UNIT_BASED) {
            return unitBasedStrategy.calculate(context, config, promotionAggregate);
        }
        // 单元计费类 CONTINUOUS：委托 ContinuousStrategy
        return continuousStrategy.calculate(context, config, promotionAggregate);
    }
}
