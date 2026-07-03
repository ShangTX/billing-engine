package cn.shang.charging.charge.rules.daynight;

import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.BillingContext;
import cn.shang.charging.billing.pojo.BillingSegmentResult;
import cn.shang.charging.billing.pojo.BillingUnit;
import cn.shang.charging.billing.value.FixedValueSpec;
import cn.shang.charging.billing.value.StepValueSpec;
import cn.shang.charging.billing.value.UnitValueSpec;
import cn.shang.charging.charge.rules.AbstractTimeBasedRule;
import cn.shang.charging.charge.rules.BoundaryProvider;
import cn.shang.charging.charge.rules.BoundaryProviders;
import cn.shang.charging.charge.rules.HomogeneousSegment;
import cn.shang.charging.promotion.PromotionAggregateUtil;
import cn.shang.charging.promotion.pojo.FreeMinuteAllocationResult;
import cn.shang.charging.promotion.pojo.FreeTimeRange;
import cn.shang.charging.promotion.pojo.PromotionAggregate;
import cn.shang.charging.promotion.pojo.PromotionUsage;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * `dayNight` 规则在 CONTINUOUS 模式下的策略实现。
 * <p>
 * 承载 CONTINUOUS 语义：边界驱动切断 + 24h 周期封顶 + 简化计算 + CONTINUE 续算 + 条件免费 + valueSpec 查询投影。
 * 继承 {@link AbstractTimeBasedRule}（CONTINUOUS 策略基类），复用时间轴切分、周期组织、简化单元、
 * 状态恢复、不足单元计费等公共基础设施。
 * <p>
 * 由 {@link DayNightRule} 门面按 BillingMode=CONTINUOUS 分派调用，不独立注册。
 * 从 {@code DayNightRule} 的 CONTINUOUS 逻辑迁移而来（TODO-20260702-002 阶段4）。
 */
final class ContinuousStrategy extends AbstractTimeBasedRule<DayNightConfig> {

    // 规则类型标识
    private static final String RULE_TYPE = "dayNight";

    private final DayNightPriceResolver priceResolver = new DayNightPriceResolver();
    private final DayNightValueSpecFactory valueSpecFactory = new DayNightValueSpecFactory();
    private final DayNightCycleStateManager cycleStateManager = new DayNightCycleStateManager();

    @Override
    protected String getRuleType() {
        return RULE_TYPE;
    }

    @Override
    protected boolean hasComplexFeatures(DayNightConfig config) {
        // DayNightRule 无时间段封顶等复杂特性
        return false;
    }

    @Override
    protected boolean isSimplifiedSupported(DayNightConfig config) {
        // DayNightRule 支持简化计算
        return true;
    }

    @Override
    protected BigDecimal getCycleCapAmount(DayNightConfig config) {
        return config.getMaxChargeOneDay();
    }

    @Override
    public Class<DayNightConfig> configClass() {
        return DayNightConfig.class;
    }

    /**
     * 本策略仅承载 CONTINUOUS 模式；门面 {@link DayNightRule} 声明完整的 supportedModes。
     * 此方法为接口契约所需，不被 Calculator 直接调用（Calculator 校验门面的 supportedModes）。
     */
    @Override
    public Set<BConstants.BillingMode> supportedModes() {
        return EnumSet.of(BConstants.BillingMode.CONTINUOUS);
    }

    /**
     * CONTINUOUS 模式计算
     * 在免费时段边界切分时间轴，每个片段从片段起点重新按单元划分
     */
    @Override
    public BillingSegmentResult calculate(BillingContext context, DayNightConfig config, PromotionAggregate promotionAggregate) {
        validateConfig(config);

        LocalDateTime calcBegin = context.getWindow().getCalculationBegin();
        LocalDateTime calcEnd = context.getWindow().getCalculationEnd();
        LocalDateTime cycleOriginBegin = context.getBeginTime();
        int unitMinutes = config.getUnitMinutes();

        // 恢复状态
        RuleState state = restoreState(context.getRuleState());
        if (state == null) {
            state = initializeState(calcBegin);
        } else {
            while (state.getCycleBoundary() != null && !calcBegin.isBefore(state.getCycleBoundary())) {
                state.setCycleIndex(state.getCycleIndex() + 1);
                state.setCycleAccumulated(BigDecimal.ZERO);
                state.setCycleBoundary(state.getCycleBoundary().plusMinutes(getCycleMinutes()));
            }
        }

        // 时段化 FREE_MINUTES（TODO-20260702-004：从 PromotionEngine 下放到策略侧）
        FreeMinuteAllocationResult materialized = materializeFreeMinutes(promotionAggregate, context.getWindow());
        final List<FreeTimeRange> freeTimeRanges = materialized.getFinalFreeRanges() != null
                ? materialized.getFinalFreeRanges() : List.of();
        BigDecimal maxCharge = config.getMaxChargeOneDay();

        // 简化计算检查：周期数超过阈值且存在无优惠周期时，走简化路径
        boolean simplificationEnabled = context.getBillingConfigResolver() != null
            && isSimplificationEnabled(config, context.getBillingConfigResolver(), context);
        int threshold = context.getBillingConfigResolver() != null
            ? context.getBillingConfigResolver().getSimplifiedCycleThreshold()
            : 0;
        long totalMinutes = Duration.between(calcBegin, calcEnd).toMinutes();
        int totalCycles = (int) (totalMinutes / getCycleMinutes());

        List<BillingUnit> allUnits;
        boolean usedSimplification = false;

        if (simplificationEnabled && totalCycles > threshold) {
            // 简化路径：按周期组织，无优惠周期合并为简化单元
            List<TimeFragment> fragments = splitTimeAxis(calcBegin, calcEnd, freeTimeRanges);
            List<CycleFragments> cycles = organizeByCycle(calcBegin, calcEnd, fragments, cycleOriginBegin);
            Set<Integer> cyclesWithPromotion = findCyclesWithPromotion(calcBegin, calcEnd, promotionAggregate);
            if (cyclesWithPromotion != null && !cycles.isEmpty()) {
                allUnits = generateSimplifiedUnitsForContinuous(cycles, cyclesWithPromotion,
                    threshold, config, calcBegin, cycleOriginBegin, state);
                BillingUnit lastUnit = allUnits.get(allUnits.size() - 1);
                int bubbleExtension = calculateBubbleExtension(freeTimeRanges, calcBegin, calcEnd);
                if (isSimplifiedUnit(lastUnit)) {
                    cn.shang.charging.billing.pojo.SimplifiedUnitMeta meta =
                            cn.shang.charging.billing.pojo.SimplifiedUnitMeta.from(lastUnit);
                    if (meta != null) {
                        state.setCycleAccumulated(meta.simplifiedCycleAmount());
                        state.setCycleIndex(state.getCycleIndex() + cycles.size() - 1);
                        if (state.getCycleBoundary() != null) {
                            int traversedCycleMinutes = Math.max(0, cycles.size() - 1) * getCycleMinutes();
                            state.setCycleBoundary(state.getCycleBoundary().plusMinutes(traversedCycleMinutes + bubbleExtension));
                        } else {
                            state.setCycleBoundary(cycles.get(cycles.size() - 1).cycleStart.plusHours(24).plusMinutes(bubbleExtension));
                        }
                    }
                } else {
                    LocalDateTime lastCycleEnd = cycles.get(cycles.size() - 1).cycleEnd;
                    BigDecimal lastCycleAmount = allUnits.stream()
                            .filter(u -> !u.isFree() && u.getEndTime().compareTo(lastCycleEnd) <= 0
                                    && u.getEndTime().compareTo(cycles.get(cycles.size() - 1).cycleStart) > 0)
                            .map(BillingUnit::getChargedAmount)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    state.setCycleAccumulated(lastCycleAmount);
                    state.setCycleIndex(state.getCycleIndex() + cycles.size() - 1);
                    if (state.getCycleBoundary() != null) {
                        state.setCycleBoundary(state.getCycleBoundary().plusMinutes(bubbleExtension));
                    } else {
                        state.setCycleBoundary(cycles.get(cycles.size() - 1).cycleStart.plusHours(24).plusMinutes(bubbleExtension));
                    }
                }
                usedSimplification = true;
            } else {
                allUnits = new ArrayList<>();
            }
        } else {
            allUnits = new ArrayList<>();
        }

        if (!usedSimplification) {
            // 边界驱动路径
            int dayBeginMin = config.getDayBeginMinute();
            int dayEndMin = config.getDayEndMinute();

            // 边界来源：周期结束 + 日夜时段边界 + 免费时段起止 + 条件免费窗口 + 单元对齐 + calcEnd
            List<BoundaryProvider> providers = new ArrayList<>();
            providers.add(BoundaryProviders.cycleEnd(cycleOriginBegin, getCycleMinutes()));
            // 日夜时段边界：每天 dayBeginMinute / dayEndMinute 翻转
            providers.add((current, end) -> {
                List<LocalDateTime> result = new ArrayList<>();
                LocalDateTime day = current.toLocalDate().atStartOfDay();
                LocalDateTime candidateBegin = day.plusMinutes(dayBeginMin);
                if (!candidateBegin.isAfter(current)) {
                    candidateBegin = candidateBegin.plusDays(1);
                }
                while (!candidateBegin.isAfter(end)) {
                    LocalDateTime candidateEnd;
                    if (dayBeginMin < dayEndMin) {
                        candidateEnd = candidateBegin.toLocalDate().atStartOfDay().plusMinutes(dayEndMin);
                    } else {
                        candidateEnd = candidateBegin.toLocalDate().plusDays(1).atStartOfDay().plusMinutes(dayEndMin);
                    }
                    if (candidateBegin.isAfter(current) && !candidateBegin.isAfter(end)) {
                        result.add(candidateBegin);
                    }
                    if (candidateEnd.isAfter(current) && !candidateEnd.isAfter(end)) {
                        result.add(candidateEnd);
                    }
                    candidateBegin = candidateBegin.plusDays(1);
                }
                return result;
            });
            providers.add(BoundaryProviders.freeRangeEdges(freeTimeRanges));
            // 条件免费窗口结束边界（conditionalUntil）：条件免费段在此处切换计费语义
            providers.add((current, end) -> {
                List<LocalDateTime> result = new ArrayList<>();
                for (FreeTimeRange range : freeTimeRanges) {
                    if (range.isConditional() && range.getConditionalUntil() != null) {
                        LocalDateTime cu = range.getConditionalUntil();
                        if (cu.isAfter(current) && !cu.isAfter(end)) {
                            result.add(cu);
                        }
                    }
                }
                return result;
            });
            // 单元对齐
            providers.add((current, end) -> {
                List<LocalDateTime> result = new ArrayList<>();
                LocalDateTime next = current.plusMinutes(unitMinutes);
                while (!next.isAfter(end)) {
                    result.add(next);
                    next = next.plusMinutes(unitMinutes);
                }
                return result;
            });
            providers.add(BoundaryProviders.calcEnd(calcEnd));

            // 边界驱动循环
            List<HomogeneousSegment> segments = runBoundaryDrivenLoop(calcBegin, calcEnd, providers,
                    (current, next) -> buildSegmentForDayNight(current, next, config, freeTimeRanges, calcEnd));

            // 应用封顶 + 累计 + 截断标记
            allUnits = applyCapAndAccumulate(segments, maxCharge, context, unitMinutes, config);

            // 边界驱动状态更新
            int bubbleExtension = calculateBubbleExtension(freeTimeRanges, calcBegin, calcEnd);
            if (state.getCycleBoundary() != null) {
                state.setCycleBoundary(state.getCycleBoundary().plusMinutes(bubbleExtension));
            } else {
                long offsetFromOrigin = Duration.between(cycleOriginBegin, calcEnd).toMinutes();
                long cycles = offsetFromOrigin / getCycleMinutes() + 1;
                state.setCycleBoundary(cycleOriginBegin.plusMinutes(cycles * getCycleMinutes()).plusMinutes(bubbleExtension));
            }
        }

        BigDecimal totalAmount = allUnits.stream()
                .map(BillingUnit::getChargedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        LocalDateTime feeEffectiveStart = calculateEffectiveFrom(allUnits);
        LocalDateTime feeEffectiveEnd = calculateEffectiveTo(allUnits, freeTimeRanges, calcBegin, calcEnd);

        // 标记最后一个单元是否被截断
        if (!allUnits.isEmpty()) {
            BillingUnit lastUnit = allUnits.get(allUnits.size() - 1);
            if (lastUnit.getDurationMinutes() < unitMinutes && lastUnit.getEndTime().equals(calcEnd)) {
                lastUnit.setIsTruncated(true);
            }
        }

        // 计算累计金额
        BigDecimal accumulatedAmount = context.getPreviousAccumulatedAmount();
        if (accumulatedAmount == null) {
            accumulatedAmount = BigDecimal.ZERO;
        }
        BigDecimal truncatedUnitChargedAmount = context.getTruncatedUnitChargedAmount();
        if (truncatedUnitChargedAmount != null && !allUnits.isEmpty()) {
            accumulatedAmount = accumulatedAmount.subtract(truncatedUnitChargedAmount);
            if (accumulatedAmount.compareTo(BigDecimal.ZERO) < 0) {
                accumulatedAmount = BigDecimal.ZERO;
            }
        }
        for (BillingUnit unit : allUnits) {
            accumulatedAmount = accumulatedAmount.add(unit.getChargedAmount());
            unit.setAccumulatedAmount(accumulatedAmount);
        }

        Map<String, Object> ruleOutputState = buildRuleOutputState(state);

        // 产出 FREE_RANGE 的 PromotionUsage
        // CONTINUOUS 免费单元 originalAmount=0（HomogeneousSegment 免费段不存原价），
        // equivalentAmount 用 priceResolver 按免费单元时长算规则原价
        final List<BillingUnit> finalAllUnits = allUnits;
        List<PromotionUsage> freeRangeUsages = PromotionAggregateUtil.buildFreeRangeUsages(
                freeTimeRanges, calcBegin, calcEnd,
                rangeId -> finalAllUnits.stream()
                        .filter(u -> u.isFree() && rangeId.equals(u.getFreePromotionId()))
                        .map(u -> priceResolver.determineUnitPriceForContinuous(u.getBeginTime(), u.getEndTime(), config)
                                .multiply(BigDecimal.valueOf(u.getDurationMinutes()))
                                .divide(BigDecimal.valueOf(unitMinutes), 2, RoundingMode.HALF_UP))
                        .reduce(BigDecimal.ZERO, BigDecimal::add));
        List<PromotionUsage> allUsages = new ArrayList<>(freeRangeUsages);
        if (materialized.getPromotionUsages() != null) {
            allUsages.addAll(materialized.getPromotionUsages());
        }
        // 写回 PromotionCarryOver（TODO-20260702-004：carryOver 构建从 PromotionEngine 迁移到策略侧）
        promotionAggregate.setPromotionCarryOver(
                PromotionAggregateUtil.buildCarryOver(materialized.getPromotionUsages(), freeTimeRanges, calcEnd));

        return BillingSegmentResult.builder()
                .segmentId(context.getSegment().getId())
                .segmentStartTime(context.getSegment().getBeginTime())
                .segmentEndTime(context.getSegment().getEndTime())
                .calculationStartTime(calcBegin)
                .calculationEndTime(calcEnd)
                .chargedAmount(totalAmount)
                .billingUnits(allUnits)
                .promotionUsages(allUsages)
                .promotionAggregate(promotionAggregate)
                .feeEffectiveStart(feeEffectiveStart)
                .feeEffectiveEnd(feeEffectiveEnd)
                .ruleOutputState(ruleOutputState)
                .build();
    }

    /**
     * 验证配置有效性
     */
    private void validateConfig(DayNightConfig config) {
        // 检查每日封顶金额（必填）
        if (config.getMaxChargeOneDay() == null) {
            throw new IllegalArgumentException("maxChargeOneDay is required");
        }
        if (config.getMaxChargeOneDay().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("maxChargeOneDay must be positive");
        }

        // 检查单元长度
        if (config.getUnitMinutes() == null || config.getUnitMinutes() <= 0) {
            throw new IllegalArgumentException("unitMinutes must be positive");
        }

        // 检查单价
        if (config.getDayUnitPrice() == null || config.getDayUnitPrice().compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("dayUnitPrice must be non-negative");
        }
        if (config.getNightUnitPrice() == null || config.getNightUnitPrice().compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("nightUnitPrice must be non-negative");
        }

        // 检查日夜时段配置
        if (config.getDayBeginMinute() == null || config.getDayEndMinute() == null) {
            throw new IllegalArgumentException("dayBeginMinute and dayEndMinute are required");
        }

        // 检查 blockWeight
        if (config.getBlockWeight() == null ||
            config.getBlockWeight().compareTo(BigDecimal.ZERO) < 0 ||
            config.getBlockWeight().compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("blockWeight must be between 0 and 1");
        }
    }

    private List<BillingUnit> generateUnitsForSingleCycle(
            int cycleIndex,
            LocalDateTime calcBegin,
            LocalDateTime calcEnd,
            DayNightConfig config,
            List<FreeTimeRange> freeTimeRanges) {

        LocalDateTime cycleStart = getCycleBoundary(cycleIndex, calcBegin);
        LocalDateTime cycleEnd = getCycleBoundary(cycleIndex + 1, calcBegin);

        // 限制在计算窗口内
        if (cycleStart.isBefore(calcBegin)) {
            cycleStart = calcBegin;
        }
        if (cycleEnd.isAfter(calcEnd)) {
            cycleEnd = calcEnd;
        }

        if (!cycleStart.isBefore(cycleEnd)) {
            return List.of();
        }

        // CONTINUOUS 风格：按免费时段切段后用 generateUnitsForCycle 生成
        // 简化路径仅用于无优惠周期（freeTimeRanges 为空），切段后只有一段非免费片段
        List<TimeFragment> fragments = splitTimeAxis(cycleStart, cycleEnd, freeTimeRanges);
        CycleFragments cycle = new CycleFragments(cycleStart, cycleEnd);
        cycle.fragments.addAll(fragments);
        List<BillingUnit> units = generateUnitsForCycle(cycle, config);

        // 为每个单元设置周期索引
        for (BillingUnit unit : units) {
            unit.setRuleData(cycleIndex);
        }

        return units;
    }

    /**
     * 计算费用确定开始时间
     * = 最后一个计费单元的开始时间
     */
    private LocalDateTime calculateEffectiveFrom(List<BillingUnit> billingUnits) {
        if (billingUnits == null || billingUnits.isEmpty()) {
            return null;
        }
        return billingUnits.getLast().getBeginTime();
    }

    /**
     * 计算费用稳定结束时间
     * 取以下因素的最小值：
     * 1. 最后一个计费单元结束时间
     * 2. 如果最后一个单元在免费时段内，延伸到免费时段结束
     * 3. 下一个24小时周期边界
     * 4. 分段结束时间
     */
    private LocalDateTime calculateEffectiveTo(List<BillingUnit> billingUnits,
                                                List<FreeTimeRange> freeTimeRanges,
                                                LocalDateTime calcBegin,
                                                LocalDateTime calcEnd) {
        if (billingUnits == null || billingUnits.isEmpty()) {
            return null;
        }

        BillingUnit lastUnit = billingUnits.get(billingUnits.size() - 1);
        LocalDateTime effectiveTo = lastUnit.getEndTime();

        // 如果最后一个单元在免费时段内，延伸到免费时段结束
        if (lastUnit.isFree() && lastUnit.getFreePromotionId() != null) {
            FreeTimeRange coveringRange = findFreeTimeRangeById(lastUnit.getFreePromotionId(), freeTimeRanges);
            if (coveringRange != null && coveringRange.getEndTime().isAfter(effectiveTo)) {
                effectiveTo = coveringRange.getEndTime();
            }
        }

        // 检查下一个24小时周期边界
        // 周期从 calcBegin 开始，每个周期24小时
        LocalDateTime currentCycleEnd = calcBegin;
        while (currentCycleEnd.isBefore(effectiveTo) || currentCycleEnd.equals(effectiveTo)) {
            LocalDateTime nextCycleEnd = currentCycleEnd.plusHours(24);
            if (nextCycleEnd.isAfter(effectiveTo)) {
                // 找到下一个周期边界
                effectiveTo = nextCycleEnd.isBefore(effectiveTo) ? nextCycleEnd : effectiveTo;
                break;
            }
            currentCycleEnd = nextCycleEnd;
        }

        // 不超过分段结束时间
        if (calcEnd != null && effectiveTo.isAfter(calcEnd)) {
            effectiveTo = calcEnd;
        }

        return effectiveTo;
    }

    /**
     * 根据ID查找免费时段
     */
    private FreeTimeRange findFreeTimeRangeById(String id, List<FreeTimeRange> freeTimeRanges) {
        if (freeTimeRanges == null || id == null) {
            return null;
        }
        for (FreeTimeRange range : freeTimeRanges) {
            if (id.equals(range.getId())) {
                return range;
            }
        }
        return null;
    }

    /**
     * 把同质段列表转换为 BillingUnit 列表，并应用封顶、累计金额、截断标记。
     */
    private List<BillingUnit> applyCapAndAccumulate(List<HomogeneousSegment> segments,
                                                     BigDecimal maxCharge,
                                                     BillingContext context,
                                                     int unitMinutes,
                                                     DayNightConfig config) {
        List<BillingUnit> units = new ArrayList<>();
        if (segments.isEmpty()) return units;

        LocalDateTime calcEnd = context.getWindow().getCalculationEnd();
        BigDecimal dayAccumulated = BigDecimal.ZERO;

        BigDecimal accumulated = context.getPreviousAccumulatedAmount();
        if (accumulated == null) accumulated = BigDecimal.ZERO;
        BigDecimal truncatedDeduction = context.getTruncatedUnitChargedAmount();

        for (int i = 0; i < segments.size(); i++) {
            HomogeneousSegment seg = segments.get(i);
            boolean isLast = (i == segments.size() - 1);
            int segMinutes = seg.durationMinutes();
            int subCount = unitMinutes > 0 ? segMinutes / unitMinutes : 1;
            if (subCount < 1) subCount = 1;

            // 截断判定提前：不足单元按 IncompleteUnitChargeMode 计费
            boolean isTruncated = isLast
                    && unitMinutes > 0
                    && segMinutes < unitMinutes
                    && seg.getEndTime().equals(calcEnd);

            boolean cycleCapped = false;
            if (maxCharge != null && !seg.isFree() && dayAccumulated.compareTo(maxCharge) >= 0) {
                cycleCapped = true;
            }

            BigDecimal originalPerSub = seg.getOriginalAmount() != null
                    ? seg.getOriginalAmount() : BigDecimal.ZERO;
            BigDecimal unitPrice = seg.getUnitPrice() != null ? seg.getUnitPrice() : BigDecimal.ZERO;

            // 不足单元是否按模式免费
            boolean incompleteFree = isTruncated && !seg.isFree() && !cycleCapped
                    && isIncompleteFree(segMinutes, unitMinutes, config.getIncompleteUnitChargeMode(),
                            config.getThresholdMinutes(), config.getThresholdRatio());

            BigDecimal charged;
            if (seg.isFree() || cycleCapped || incompleteFree) {
                charged = BigDecimal.ZERO;
            } else if (isTruncated) {
                // 不足单元按模式计费（非免费的档位）
                charged = computeIncompleteCharge(unitPrice, segMinutes, unitMinutes,
                        config.getIncompleteUnitChargeMode(),
                        config.getThresholdMinutes(), config.getThresholdRatio());
            } else {
                BigDecimal budget = maxCharge != null
                        ? maxCharge.subtract(dayAccumulated)
                        : null;
                if (budget != null && budget.signum() < 0) budget = BigDecimal.ZERO;
                BigDecimal fullTotal = originalPerSub.multiply(BigDecimal.valueOf(subCount));
                if (budget != null && fullTotal.compareTo(budget) > 0) {
                    charged = budget.setScale(2, RoundingMode.HALF_UP);
                } else {
                    charged = fullTotal;
                }
            }

            if (truncatedDeduction != null && i == 0) {
                BigDecimal adjusted = charged.subtract(truncatedDeduction);
                if (adjusted.signum() < 0) adjusted = BigDecimal.ZERO;
                charged = adjusted;
            }

            accumulated = accumulated.add(charged);
            if (!seg.isFree() && !cycleCapped && !incompleteFree) {
                dayAccumulated = dayAccumulated.add(charged);
            }

            boolean isCompact = !isTruncated && subCount > 1;

            // 解析 valueSpec
            UnitValueSpec spec;
            if (isTruncated && !seg.isFree() && !cycleCapped && !incompleteFree) {
                // 不足单元按模式生成 valueSpec（PROPORTIONAL 等反映单元内线性投影）
                spec = computeIncompleteValueSpec(unitPrice, segMinutes, unitMinutes,
                        config.getIncompleteUnitChargeMode(),
                        config.getThresholdMinutes(), config.getThresholdRatio());
            } else if (seg.getValueSpec() instanceof UnitValueSpec us) {
                spec = us;
            } else if (seg.isFree() || incompleteFree) {
                spec = new FixedValueSpec(BigDecimal.ZERO);
            } else {
                spec = new FixedValueSpec(unitPrice);
            }
            // 封顶削减时覆盖 valueSpec：query 投影应返回封顶后金额，而非原价
            boolean cappedOrReduced = cycleCapped
                    || (charged.compareTo(originalPerSub.multiply(BigDecimal.valueOf(subCount))) < 0
                        && !seg.isFree() && !isTruncated);
            if (cappedOrReduced) {
                spec = new FixedValueSpec(charged);
            }

            BillingUnit unit = BillingUnit.builder()
                    .beginTime(seg.getBeginTime())
                    .endTime(seg.getEndTime())
                    .durationMinutes(segMinutes)
                    .unitPrice(unitPrice)
                    .originalAmount(originalPerSub.multiply(BigDecimal.valueOf(subCount)))
                    .free(seg.isFree() || cycleCapped || incompleteFree)
                    .freePromotionId(cycleCapped ? "DAILY_CAP"
                            : (incompleteFree ? "INCOMPLETE_FREE" : seg.getFreePromotionId()))
                    .chargedAmount(charged)
                    .accumulatedAmount(accumulated)
                    .valueSpec(spec)
                    .ruleData(seg.getRuleData())
                    .compact(isCompact)
                    .count(isCompact ? subCount : 1)
                    .isTruncated(isTruncated)
                    .build();
            units.add(unit);

            // 24h 周期切换（按 segment.endTime 落在哪一天切换）
            // DayNight 用自然日：跨午夜就重置
            if (seg.getEndTime().getDayOfYear() != seg.getBeginTime().getDayOfYear()
                    || seg.getEndTime().getYear() != seg.getBeginTime().getYear()) {
                dayAccumulated = BigDecimal.ZERO;
            }
        }
        return units;
    }

    /**
     * 为 CONTINUOUS 模式生成简化单元
     */
    private List<BillingUnit> generateSimplifiedUnitsForContinuous(
            List<CycleFragments> cycles,
            Set<Integer> cyclesWithPromotion,
            int threshold,
            DayNightConfig config,
            LocalDateTime calcBegin,
            LocalDateTime cycleOriginBegin,
            RuleState state) {

        List<BillingUnit> allUnits = new ArrayList<>();
        BigDecimal cycleCapAmount = getCycleCapAmount(config);
        int cycleMinutes = getCycleMinutes();

        int consecutiveSimplified = 0;
        int simplifiedStartIndex = -1;
        BigDecimal carryOverAccumulated = state.getCycleAccumulated();

        for (int cycleIdx = 0; cycleIdx < cycles.size(); cycleIdx++) {
            CycleFragments cycle = cycles.get(cycleIdx);
            // 计算周期索引（基于原始计费起点）
            int cycleIndex = (int) Duration.between(cycleOriginBegin, cycle.cycleStart).toMinutes() / cycleMinutes;

            boolean hasPromotion = cyclesWithPromotion.contains(cycleIndex);

            if (!hasPromotion) {
                // 无优惠周期，累计
                if (consecutiveSimplified == 0) {
                    simplifiedStartIndex = cycleIndex;
                }
                consecutiveSimplified++;
            } else {
                // 处理之前的简化段
                if (consecutiveSimplified > threshold) {
                    BillingUnit simplifiedUnit = buildSimplifiedUnit(
                        simplifiedStartIndex, consecutiveSimplified, cycleCapAmount, calcBegin);
                    allUnits.add(simplifiedUnit);
                    carryOverAccumulated = BigDecimal.ZERO; // 简化后重置
                } else if (consecutiveSimplified > 0) {
                    // 不足阈值，正常生成
                    for (int i = simplifiedStartIndex; i < simplifiedStartIndex + consecutiveSimplified; i++) {
                        List<BillingUnit> cycleUnits = generateUnitsForSingleCycle(i, calcBegin, cycle.cycleEnd, config, List.of());
                        allUnits.addAll(cycleUnits);
                    }
                }
                consecutiveSimplified = 0;

                // 生成当前有优惠周期的详细单元
                List<BillingUnit> cycleUnits = generateUnitsForCycle(cycle, config);
                cycleStateManager.applyDailyCapWithCarryOver(
                        cycleUnits,
                        config,
                        carryOverAccumulated,
                        (beginTime, endTime) -> valueSpecFactory.createCappedSpec(
                                cycleUnits.stream()
                                        .filter(u -> beginTime.equals(u.getBeginTime()) && endTime.equals(u.getEndTime()))
                                        .findFirst()
                                        .map(BillingUnit::getValueSpec)
                                        .orElse(null),
                                beginTime,
                                endTime,
                                cycleUnits.stream()
                                        .filter(u -> beginTime.equals(u.getBeginTime()) && endTime.equals(u.getEndTime()))
                                        .findFirst()
                                        .map(BillingUnit::getChargedAmount)
                                        .orElse(BigDecimal.ZERO)
                        )
                );
                allUnits.addAll(cycleUnits);
                carryOverAccumulated = BigDecimal.ZERO;
            }
        }

        // 处理最后的简化段
        if (consecutiveSimplified > threshold) {
            BillingUnit simplifiedUnit = buildSimplifiedUnit(
                simplifiedStartIndex, consecutiveSimplified, cycleCapAmount, calcBegin);
            allUnits.add(simplifiedUnit);
        } else if (consecutiveSimplified > 0) {
            for (int i = simplifiedStartIndex; i < simplifiedStartIndex + consecutiveSimplified; i++) {
                List<BillingUnit> cycleUnits = generateUnitsForSingleCycle(i, calcBegin, cycles.get(cycles.size() - 1).cycleEnd, config, List.of());
                allUnits.addAll(cycleUnits);
            }
        }

        return allUnits;
    }

    @Override
    protected TimeFragment createFragment(LocalDateTime beginTime, LocalDateTime endTime) {
        return new DayNightTimeFragment(beginTime, endTime);
    }

    /**
     * 为一个周期生成计费单元
     */
    private List<BillingUnit> generateUnitsForCycle(CycleFragments cycle, DayNightConfig config) {
        List<BillingUnit> units = new ArrayList<>();
        int unitMinutes = config.getUnitMinutes();
        for (TimeFragment fragment : cycle.fragments) {
            if (fragment.isFree) {
                if (fragment.isConditional()) {
                    LocalDateTime current = fragment.beginTime;
                    while (current.isBefore(fragment.endTime)) {
                        LocalDateTime pricingEnd = resolvePricingEnd(current, unitMinutes, cycle.cycleEnd);
                        LocalDateTime unitEnd = pricingEnd;
                        if (unitEnd.isAfter(fragment.endTime)) {
                            unitEnd = fragment.endTime;
                        }

                        int duration = (int) Duration.between(current, unitEnd).toMinutes();
                        BigDecimal unitPrice = determineUnitPriceForContinuous(current, pricingEnd, config);
                        BigDecimal originalAmount = unitPrice;

                        BillingUnit unit = BillingUnit.builder()
                                .beginTime(current)
                                .endTime(unitEnd)
                                .durationMinutes(duration)
                                .unitPrice(unitPrice)
                                .originalAmount(originalAmount)
                                .free(false)
                                .freePromotionId(fragment.freePromotionId)
                                .chargedAmount(originalAmount)
                                .valueSpec(new StepValueSpec(fragment.getConditionalUntil(), BigDecimal.ZERO, originalAmount))
                                .build();

                        units.add(unit);
                        current = unitEnd;
                    }
                } else {
                    // 免费片段直接生成一个免费单元
                    BillingUnit unit = BillingUnit.builder()
                            .beginTime(fragment.beginTime)
                            .endTime(fragment.endTime)
                            .durationMinutes((int) Duration.between(fragment.beginTime, fragment.endTime).toMinutes())
                            .unitPrice(BigDecimal.ZERO)
                            .originalAmount(BigDecimal.ZERO)
                            .free(true)
                            .freePromotionId(fragment.freePromotionId)
                            .chargedAmount(BigDecimal.ZERO)
                            .valueSpec(new FixedValueSpec(BigDecimal.ZERO))
                            .build();
                    units.add(unit);
                }
            } else {
                // 收费片段按单元长度划分
                LocalDateTime current = fragment.beginTime;
                while (current.isBefore(fragment.endTime)) {
                    LocalDateTime pricingEnd = resolvePricingEnd(current, unitMinutes, cycle.cycleEnd);
                    LocalDateTime unitEnd = pricingEnd;
                    if (unitEnd.isAfter(fragment.endTime)) {
                        unitEnd = fragment.endTime;
                    }

                    int duration = (int) Duration.between(current, unitEnd).toMinutes();

                    // 确定单价
                    BigDecimal unitPrice = determineUnitPriceForContinuous(current, pricingEnd, config);

                    // 不足单元也收全额
                    BigDecimal originalAmount = unitPrice;
                    DayNightPeriodType periodType = priceResolver.determinePeriodType(current, pricingEnd, config);
                    UnitValueSpec valueSpec = valueSpecFactory.createRegularSpec(
                            periodType,
                            current,
                            pricingEnd,
                            config,
                            originalAmount
                    );

                    BillingUnit unit = BillingUnit.builder()
                            .beginTime(current)
                            .endTime(unitEnd)
                            .durationMinutes(duration)
                            .unitPrice(unitPrice)
                            .originalAmount(originalAmount)
                            .free(false)
                            .chargedAmount(originalAmount)
                            .valueSpec(valueSpec)
                            .build();

                    units.add(unit);
                    current = unitEnd;
                }
            }
        }

        return units;
    }

    private LocalDateTime resolvePricingEnd(LocalDateTime unitBegin, int unitMinutes, LocalDateTime cycleEnd) {
        LocalDateTime pricingEnd = unitBegin.plusMinutes(unitMinutes);
        if (pricingEnd.isAfter(cycleEnd)) {
            return cycleEnd;
        }
        return pricingEnd;
    }

    /**
     * 确定时段单价（CONTINUOUS模式专用）
     */
    private BigDecimal determineUnitPriceForContinuous(LocalDateTime begin, LocalDateTime end, DayNightConfig config) {
        return priceResolver.determineUnitPriceForContinuous(begin, end, config);
    }

    /**
     * DayNight 专用时间片段，在公共 {@link TimeFragment} 基础上携带条件免费信息。
     * <p>
     * {@link #copy(LocalDateTime, LocalDateTime)} 不覆盖，沿用基类实现：周期边界切分产生的
     * beforeBoundary 为基类 TimeFragment（isConditional()=false），与历史行为一致。
     */
    private static class DayNightTimeFragment extends TimeFragment {
        private boolean conditional;
        private LocalDateTime conditionalUntil;

        DayNightTimeFragment(LocalDateTime beginTime, LocalDateTime endTime) {
            super(beginTime, endTime);
        }

        @Override
        public void applyFreeRange(FreeTimeRange range) {
            super.applyFreeRange(range);
            this.conditional = range.isConditional();
            this.conditionalUntil = range.getConditionalUntil();
        }

        @Override
        public boolean isConditional() {
            return conditional;
        }

        @Override
        public LocalDateTime getConditionalUntil() {
            return conditionalUntil;
        }
    }

    /**
     * 边界驱动循环的段构造回调。
     */
    private HomogeneousSegment buildSegmentForDayNight(LocalDateTime current,
                                                       LocalDateTime next,
                                                       DayNightConfig config,
                                                       List<FreeTimeRange> freeTimeRanges,
                                                       LocalDateTime calcEnd) {
        // pricingEnd 取 current + unitMinutes，但截断到 calcEnd（与 HEAD resolvePricingEnd 截断到 cycleEnd 一致）：
        // 不足单元也按完整单元计价（收全额），但被 calcEnd 截断的 truncated 单元按实际时段计价
        LocalDateTime pricingEnd = current.plusMinutes(config.getUnitMinutes());
        if (pricingEnd.isAfter(calcEnd)) {
            pricingEnd = calcEnd;
        }
        for (FreeTimeRange range : freeTimeRanges) {
            if (!range.getBeginTime().isAfter(current) && !range.getEndTime().isBefore(next)) {
                if (range.isConditional()) {
                    // 条件免费段：窗口内免费、窗口外收费，按完整单元计价
                    BigDecimal unitPrice = priceResolver.determineUnitPriceForContinuous(current, pricingEnd, config);
                    return new HomogeneousSegment(current, next, unitPrice, unitPrice,
                            false, range.getId(),
                            new StepValueSpec(range.getConditionalUntil(), BigDecimal.ZERO, unitPrice),
                            null);
                }
                return new HomogeneousSegment(current, next, BigDecimal.ZERO, BigDecimal.ZERO,
                        true, range.getId(), null, null);
            }
        }
        BigDecimal unitPrice = priceResolver.determineUnitPriceForContinuous(current, pricingEnd, config);
        DayNightPeriodType periodType = priceResolver.determinePeriodType(current, pricingEnd, config);
        UnitValueSpec spec = valueSpecFactory.createRegularSpec(periodType, current, pricingEnd, config, unitPrice);
        return new HomogeneousSegment(current, next, unitPrice, unitPrice,
                false, null, spec, null);
    }
}
