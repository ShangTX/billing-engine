package cn.shang.charging.charge.rules.daynight;

import cn.shang.charging.billing.value.FixedValueSpec;
import cn.shang.charging.billing.value.StepValueSpec;
import cn.shang.charging.billing.value.UnitValueProjection;
import cn.shang.charging.billing.value.UnitValueSpec;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * `dayNight` 规则的 valueSpec 生成工厂。
 */
final class DayNightValueSpecFactory {

    private final DayNightPriceResolver priceResolver = new DayNightPriceResolver();

    UnitValueSpec createFreeSpec() {
        return new FixedValueSpec(BigDecimal.ZERO);
    }

    UnitValueSpec createConditionalFreeSpec(LocalDateTime conditionalUntil, BigDecimal finalAmount) {
        return new StepValueSpec(conditionalUntil, BigDecimal.ZERO, finalAmount);
    }

    UnitValueSpec createRegularSpec(DayNightPeriodType periodType,
                                    LocalDateTime beginTime,
                                    LocalDateTime endTime,
                                    DayNightConfig config,
                                    BigDecimal finalAmount) {
        if (periodType == DayNightPeriodType.MIXED) {
            return new MixedUnitValueSpec(beginTime, endTime, config, priceResolver);
        }
        return new FixedValueSpec(finalAmount);
    }

    UnitValueSpec createCappedSpec(UnitValueSpec delegate,
                                   LocalDateTime unitBeginTime,
                                   LocalDateTime unitEndTime,
                                   BigDecimal capAmount) {
        return new CappedValueSpec(delegate, unitBeginTime, unitEndTime, capAmount);
    }

    private static class MixedUnitValueSpec implements UnitValueSpec {
        private final LocalDateTime unitBeginTime;
        private final LocalDateTime unitEndTime;
        private final DayNightConfig config;
        private final DayNightPriceResolver priceResolver;

        MixedUnitValueSpec(LocalDateTime unitBeginTime,
                           LocalDateTime unitEndTime,
                           DayNightConfig config,
                           DayNightPriceResolver priceResolver) {
            this.unitBeginTime = unitBeginTime;
            this.unitEndTime = unitEndTime;
            this.config = config;
            this.priceResolver = priceResolver;
        }

        @Override
        public UnitValueProjection project(LocalDateTime queryTime, LocalDateTime unitBeginTime, LocalDateTime unitEndTime) {
            BigDecimal currentAmount = determineAmount(queryTime);
            if (!queryTime.isBefore(this.unitEndTime)) {
                return new UnitValueProjection(currentAmount, this.unitEndTime);
            }

            LocalDateTime nextChangeTime = this.unitEndTime;
            BigDecimal current = currentAmount;
            LocalDateTime candidate = queryTime.plusMinutes(1);
            while (!candidate.isAfter(this.unitEndTime)) {
                BigDecimal candidateAmount = determineAmount(candidate);
                if (candidateAmount.compareTo(current) != 0) {
                    nextChangeTime = candidate;
                    break;
                }
                candidate = candidate.plusMinutes(1);
            }

            return new UnitValueProjection(currentAmount, nextChangeTime);
        }

        private BigDecimal determineAmount(LocalDateTime queryTime) {
            int duration = (int) Duration.between(unitBeginTime, queryTime).toMinutes();
            if (duration <= 0) {
                return config.getDayUnitPrice();
            }
            DayNightPeriodType currentType = priceResolver.determinePeriodType(unitBeginTime, queryTime, config);
            if (currentType == DayNightPeriodType.DAY) {
                return config.getDayUnitPrice();
            }
            if (currentType == DayNightPeriodType.NIGHT) {
                return config.getNightUnitPrice();
            }
            int[] mins = priceResolver.calculateDayNightMinutes(unitBeginTime, queryTime, config);
            BigDecimal ratio = BigDecimal.valueOf(mins[0])
                    .divide(BigDecimal.valueOf(duration), 4, RoundingMode.HALF_UP);
            return ratio.compareTo(config.getBlockWeight()) >= 0
                    ? config.getDayUnitPrice()
                    : config.getNightUnitPrice();
        }
    }

    private static class CappedValueSpec implements UnitValueSpec {
        private final UnitValueSpec delegate;
        private final LocalDateTime unitBeginTime;
        private final LocalDateTime unitEndTime;
        private final BigDecimal capAmount;

        CappedValueSpec(UnitValueSpec delegate,
                        LocalDateTime unitBeginTime,
                        LocalDateTime unitEndTime,
                        BigDecimal capAmount) {
            this.delegate = delegate;
            this.unitBeginTime = unitBeginTime;
            this.unitEndTime = unitEndTime;
            this.capAmount = capAmount;
        }

        @Override
        public UnitValueProjection project(LocalDateTime queryTime, LocalDateTime unitBeginTime, LocalDateTime unitEndTime) {
            UnitValueProjection base = delegate.project(queryTime, this.unitBeginTime, this.unitEndTime);
            BigDecimal currentAmount = base.currentAmount().min(capAmount);
            if (!queryTime.isBefore(this.unitEndTime)) {
                return new UnitValueProjection(currentAmount, this.unitEndTime);
            }

            LocalDateTime nextChangeTime = this.unitEndTime;
            LocalDateTime candidate = queryTime.plusMinutes(1);
            while (!candidate.isAfter(this.unitEndTime)) {
                BigDecimal candidateAmount = delegate.project(candidate, this.unitBeginTime, this.unitEndTime)
                        .currentAmount()
                        .min(capAmount);
                if (candidateAmount.compareTo(currentAmount) != 0) {
                    nextChangeTime = candidate;
                    break;
                }
                candidate = candidate.plusMinutes(1);
            }

            return new UnitValueProjection(currentAmount, nextChangeTime);
        }
    }
}
