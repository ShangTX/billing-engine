package cn.shang.charging.charge.rules.naturaltime;

import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.BillingContext;
import cn.shang.charging.billing.pojo.BillingSegmentResult;
import cn.shang.charging.charge.rules.BillingRule;
import cn.shang.charging.charge.rules.DurationGlobalStrategy;
import cn.shang.charging.charge.rules.DurationPeriodStrategy;
import cn.shang.charging.promotion.pojo.PromotionAggregate;

import java.util.EnumSet;
import java.util.Set;

/**
 * 多自然时段计费规则门面。
 * <p>
 * 核心逻辑：
 * 1. 24 小时自然周期，按自然时段划分
 * 2. 每个时段有独立价格，统一单元时长
 * 3. 自然时段边界统一切断
 * 4. 支持每日封顶
 * <p>
 * 按 {@link BConstants.CalculationMode} 分派到独立策略实现，自身只分派不扛计费逻辑：
 * <ul>
 *   <li>CONTINUOUS → {@link NaturalTimeContinuousStrategy}（边界驱动切断）</li>
 *   <li>DURATION_PERIOD → {@link DurationPeriodStrategy}（通用时长 PERIOD 策略）</li>
 *   <li>DURATION_GLOBAL → {@link DurationGlobalStrategy}（通用时长 GLOBAL 策略）</li>
 * </ul>
 * 一个 {@code naturalTime} type 注册本门面。
 * <p>
 * TODO-20260706-002 阶段2b：CONTINUOUS 逻辑下沉到 NaturalTimeContinuousStrategy，门面回归纯分派。
 * TODO-20260706-002 阶段4：声明支持时长模式，规则族语义由 {@link NaturalTimeSemantics} 注入。
 */
public class NaturalTimeRule implements BillingRule<NaturalTimeConfig> {

    private final NaturalTimeContinuousStrategy continuousStrategy = new NaturalTimeContinuousStrategy();
    private final NaturalTimeSemantics naturalTimeSemantics = new NaturalTimeSemantics();

    @Override
    public Class<NaturalTimeConfig> configClass() {
        return NaturalTimeConfig.class;
    }

    @Override
    public Set<BConstants.CalculationMode> supportedCalculationModes() {
        return EnumSet.of(BConstants.CalculationMode.CONTINUOUS,
                BConstants.CalculationMode.DURATION_PERIOD, BConstants.CalculationMode.DURATION_GLOBAL);
    }

    @Override
    public BillingSegmentResult calculate(BillingContext context,
                                          NaturalTimeConfig config,
                                          PromotionAggregate promotionAggregate) {
        BConstants.CalculationMode mode = context.getCalculationMode();
        if (mode == null) mode = BConstants.CalculationMode.CONTINUOUS;
        return switch (mode) {
            case DURATION_PERIOD -> {
                validateConfig(config);
                yield DurationPeriodStrategy.calculate(naturalTimeSemantics, context, config, promotionAggregate);
            }
            case DURATION_GLOBAL -> {
                validateConfig(config);
                yield DurationGlobalStrategy.calculate(naturalTimeSemantics, context, config, promotionAggregate);
            }
            default -> continuousStrategy.calculate(context, config, promotionAggregate);
        };
    }

    /**
     * 时长模式配置校验（与 {@link NaturalTimeContinuousStrategy} 一致）。
     */
    private void validateConfig(NaturalTimeConfig config) {
        if (config.getPeriods() == null || config.getPeriods().isEmpty()) {
            throw new IllegalArgumentException("periods 不能为空");
        }
        if (config.getUnitMinutes() <= 0) {
            throw new IllegalArgumentException("unitMinutes 必须大于 0");
        }
        NaturalPeriodSupport.validateFullDayCoverage(config.getPeriods());
    }
}
