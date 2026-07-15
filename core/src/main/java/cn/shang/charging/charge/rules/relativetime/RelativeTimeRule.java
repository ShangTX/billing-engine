package cn.shang.charging.charge.rules.relativetime;

import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.BillingContext;
import cn.shang.charging.billing.pojo.BillingSegmentResult;
import cn.shang.charging.charge.rules.BillingRule;
import cn.shang.charging.charge.rules.DurationGlobalStrategy;
import cn.shang.charging.charge.rules.DurationPeriodStrategy;
import cn.shang.charging.promotion.pojo.PromotionAggregate;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 按相对时间段计费规则门面。
 * <p>
 * 核心逻辑：
 * 1. 从计费起点开始，按 24 小时划分周期
 * 2. 每个周期内按配置的时间段划分，每个时间段可有不同的单元长度和单价
 * 3. 计费单元在时间段边界会被截断，不足一个单元长度的部分收全额
 * 4. 每个周期独立封顶，超出时从最后一个单元开始削减
 * 5. 免费时段完全覆盖计费单元才免费
 * <p>
 * 按 {@link BConstants.CalculationMode} 分派到独立策略实现，自身只分派不扛计费逻辑：
 * <ul>
 *   <li>CONTINUOUS → {@link RelativeTimeContinuousStrategy}（边界驱动切断）</li>
 *   <li>DURATION_PERIOD → {@link DurationPeriodStrategy}（通用时长 PERIOD 策略）</li>
 *   <li>DURATION_GLOBAL → {@link DurationGlobalStrategy}（通用时长 GLOBAL 策略）</li>
 * </ul>
 * 一个 {@code relativeTime} type 注册本门面。
 * <p>
 * TODO-20260706-002 阶段2a：CONTINUOUS 逻辑下沉到 RelativeTimeContinuousStrategy，门面回归纯分派。
 * TODO-20260706-002 阶段4：声明支持时长模式，规则族语义由 {@link RelativeTimeSemantics} 注入。
 */
public class RelativeTimeRule implements BillingRule<RelativeTimeConfig> {

    private static final int MINUTES_PER_CYCLE = 1440;

    private final RelativeTimeContinuousStrategy continuousStrategy = new RelativeTimeContinuousStrategy();
    private final RelativeTimeSemantics relativeTimeSemantics = new RelativeTimeSemantics();

    @Override
    public Class<RelativeTimeConfig> configClass() {
        return RelativeTimeConfig.class;
    }

    @Override
    public Set<BConstants.CalculationMode> supportedCalculationModes() {
        return EnumSet.of(BConstants.CalculationMode.CONTINUOUS,
                BConstants.CalculationMode.DURATION_PERIOD, BConstants.CalculationMode.DURATION_GLOBAL);
    }

    @Override
    public BillingSegmentResult calculate(BillingContext context, RelativeTimeConfig config, PromotionAggregate promotionAggregate) {
        BConstants.CalculationMode mode = context.getCalculationMode();
        if (mode == null) mode = BConstants.CalculationMode.CONTINUOUS;
        return switch (mode) {
            case DURATION_PERIOD -> {
                validateConfig(config);
                yield DurationPeriodStrategy.calculate(relativeTimeSemantics, context, config, promotionAggregate);
            }
            case DURATION_GLOBAL -> {
                validateConfig(config);
                yield DurationGlobalStrategy.calculate(relativeTimeSemantics, context, config, promotionAggregate);
            }
            default -> continuousStrategy.calculate(context, config, promotionAggregate);
        };
    }

    /**
     * 时长模式配置校验（与 {@link RelativeTimeContinuousStrategy#validateConfig} 一致）。
     */
    private void validateConfig(RelativeTimeConfig config) {
        if (config.getMaxChargeOneCycle() == null) {
            throw new IllegalArgumentException("maxChargeOneCycle is required");
        }
        if (config.getMaxChargeOneCycle().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("maxChargeOneCycle must be positive");
        }

        List<RelativeTimePeriod> periods = config.getPeriods();
        if (periods == null || periods.isEmpty()) {
            throw new IllegalArgumentException("Periods cannot be empty");
        }
        if (periods.get(0).getBeginMinute() != 0) {
            throw new IllegalArgumentException("First period must start at minute 0");
        }
        if (periods.get(periods.size() - 1).getEndMinute() != MINUTES_PER_CYCLE) {
            throw new IllegalArgumentException("Last period must end at minute 1440");
        }
        for (int i = 0; i < periods.size() - 1; i++) {
            if (periods.get(i).getEndMinute() != periods.get(i + 1).getBeginMinute()) {
                throw new IllegalArgumentException("Periods must be contiguous: period " + i + " ends at " +
                        periods.get(i).getEndMinute() + " but period " + (i + 1) + " starts at " +
                        periods.get(i + 1).getBeginMinute());
            }
        }
        for (int i = 0; i < periods.size(); i++) {
            RelativeTimePeriod period = periods.get(i);
            if (period.getBeginMinute() >= period.getEndMinute()) {
                throw new IllegalArgumentException("Invalid period " + i + ": beginMinute must be less than endMinute");
            }
            if (period.getUnitMinutes() <= 0) {
                throw new IllegalArgumentException("Invalid unitMinutes in period " + i);
            }
            if (period.getUnitPrice() == null || period.getUnitPrice().compareTo(BigDecimal.ZERO) < 0) {
                throw new IllegalArgumentException("Invalid unitPrice in period " + i);
            }
        }
    }
}
