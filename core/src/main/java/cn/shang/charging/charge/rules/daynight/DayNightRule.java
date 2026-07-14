package cn.shang.charging.charge.rules.daynight;

import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.BillingContext;
import cn.shang.charging.billing.pojo.BillingSegmentResult;
import cn.shang.charging.charge.rules.BillingRule;
import cn.shang.charging.charge.rules.DurationPeriodStrategy;
import cn.shang.charging.promotion.pojo.PromotionAggregate;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.Set;

/**
 * 日夜分时段计费规则门面。
 * <p>
 * 按 {@link BConstants.CalculationMode} 分派到独立策略实现，自身只分派不扛计费逻辑：
 * <ul>
 *   <li>DURATION_PERIOD → {@link DurationPeriodStrategy}（通用时长 PERIOD 策略）</li>
 *   <li>DURATION_GLOBAL → {@link DurationGlobalStrategy}（通用时长 GLOBAL 策略）</li>
 *   <li>UNIT_BASED → {@link DayNightUnitBasedStrategy}（固定单元对齐）</li>
 *   <li>CONTINUOUS → {@link DayNightContinuousStrategy}（边界驱动切断）</li>
 * </ul>
 * 一个 {@code dayNight} type 注册本门面，支持四种计算模式，不需覆盖注册。
 * <p>
 * TODO-20260702-002 阶段4：CONTINUOUS 逻辑下沉到 ContinuousStrategy，门面回归纯分派。
 * TODO-20260706-002 阶段4：时长策略通用化（DayNightDurationStrategy 拆为 DurationPeriod/GlobalStrategy），
 * 规则族语义（day/night 标签、日夜边界、价格）由 {@link DayNightSemantics} 注入。
 */
public class DayNightRule implements BillingRule<DayNightConfig> {

    private final DayNightSemantics dayNightSemantics = new DayNightSemantics();
    private final DayNightUnitBasedStrategy unitBasedStrategy = new DayNightUnitBasedStrategy();
    private final DayNightContinuousStrategy continuousStrategy = new DayNightContinuousStrategy();

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
            case DURATION_PERIOD -> {
                validateConfig(config);
                yield DurationPeriodStrategy.calculate(dayNightSemantics, context, config, promotionAggregate);
            }
            case DURATION_GLOBAL -> {
                validateConfig(config);
                yield DayNightDurationGlobalStrategy.calculate(context, config, promotionAggregate);
            }
            case UNIT_BASED -> unitBasedStrategy.calculate(context, config, promotionAggregate);
            case CONTINUOUS -> continuousStrategy.calculate(context, config, promotionAggregate);
        };
    }

    /**
     * 时长模式配置校验（从原 DayNightDurationStrategy.validateConfig 搬来）。
     */
    private void validateConfig(DayNightConfig config) {
        if (config.getMaxChargeOneDay() == null || config.getMaxChargeOneDay().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("maxChargeOneDay must be positive");
        }
        if (config.getUnitMinutes() == null || config.getUnitMinutes() <= 0) {
            throw new IllegalArgumentException("unitMinutes must be positive");
        }
        if (config.getDayUnitPrice() == null || config.getDayUnitPrice().compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("dayUnitPrice must be non-negative");
        }
        if (config.getNightUnitPrice() == null || config.getNightUnitPrice().compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("nightUnitPrice must be non-negative");
        }
        if (config.getDayBeginMinute() == null || config.getDayEndMinute() == null) {
            throw new IllegalArgumentException("dayBeginMinute and dayEndMinute are required");
        }
        if (config.getBlockWeight() == null ||
            config.getBlockWeight().compareTo(BigDecimal.ZERO) < 0 ||
            config.getBlockWeight().compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("blockWeight must be between 0 and 1");
        }
    }
}
