package cn.shang.charging.wrapper;

import cn.shang.charging.billing.pojo.BillingResult;
import cn.shang.charging.billing.pojo.BillingUnit;
import cn.shang.charging.promotion.pojo.PromotionUsage;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 计费结果视图处理器
 * 负责按查询时间截取结果
 */
public class BillingResultViewer {

    /**
     * 创建查询摘要（轻量级，用索引代替复制）
     *
     * @param result    完整计算结果
     * @param queryTime 查询时间点
     * @return 查询摘要
     * @throws IllegalArgumentException 当 queryTime <= units[0].beginTime
     */
    public QuerySummary createQuerySummary(BillingResult result, LocalDateTime queryTime) {
        if (result == null || queryTime == null) {
            throw new IllegalArgumentException("result 和 queryTime 不能为 null");
        }

        // 应用条件免费校验：如果 queryTime 超出条件免费窗口，恢复原价
        result = applyQueryTimeValidation(result, queryTime);

        List<BillingUnit> units = result.getUnits();
        if (units == null || units.isEmpty()) {
            return QuerySummary.builder()
                .unitIndex(-1)
                .amount(BigDecimal.ZERO)
                .queryTime(queryTime)
                .promotionUsages(List.of())
                .build();
        }

        // 边界检查：queryTime <= 第一个单元的 beginTime
        LocalDateTime firstBeginTime = units.get(0).getBeginTime();
        if (queryTime.compareTo(firstBeginTime) <= 0) {
            throw new IllegalArgumentException(
                "查询时间过早，无对应计费单元。queryTime=" + queryTime +
                ", firstUnitBeginTime=" + firstBeginTime);
        }

        // 查找单元：beginTime < queryTime <= endTime
        int unitIndex = findUnitIndex(units, queryTime);

        // 获取金额
        BigDecimal amount = unitIndex >= 0
            ? units.get(unitIndex).getAccumulatedAmount()
            : BigDecimal.ZERO;

        // 截取优惠使用记录
        List<PromotionUsage> filteredUsages = filterUsages(result.getPromotionUsages(), queryTime);

        return QuerySummary.builder()
            .unitIndex(unitIndex)
            .amount(amount)
            .effectiveFrom(units.get(0).getBeginTime())
            .effectiveTo(unitIndex >= 0 ? units.get(unitIndex).getEndTime() : null)
            .queryTime(queryTime)
            .promotionUsages(filteredUsages)
            .build();
    }

    /**
     * 查找满足 beginTime < queryTime <= endTime 的单元索引
     */
    private int findUnitIndex(List<BillingUnit> units, LocalDateTime queryTime) {
        for (int i = 0; i < units.size(); i++) {
            BillingUnit unit = units.get(i);
            LocalDateTime beginTime = unit.getBeginTime();
            LocalDateTime endTime = unit.getEndTime();

            if (beginTime != null && endTime != null &&
                beginTime.isBefore(queryTime) && !queryTime.isAfter(endTime)) {
                return i;
            }
        }

        // queryTime > 最后一个单元的 endTime，返回最后一个索引
        return units.size() - 1;
    }

    /**
     * 返回指定时间点的视图
     *
     * @param result    完整计算结果
     * @param queryTime 查询时间点
     * @return 截取后的结果
     */
    public BillingResult viewAtTime(BillingResult result, LocalDateTime queryTime) {
        if (queryTime == null || result == null) {
            return result;
        }

        // 应用条件免费校验：如果 queryTime 超出条件免费窗口，恢复原价
        result = applyQueryTimeValidation(result, queryTime);

        // 1. 截取计费单元
        List<BillingUnit> filteredUnits = filterUnits(result.getUnits(), queryTime);

        // 2. 截取优惠使用情况
        List<PromotionUsage> filteredUsages = filterUsages(result.getPromotionUsages(), queryTime);

        // 3. 重算金额
        BigDecimal filteredAmount = calculateAmount(filteredUnits);

        // 4. 计算查询结果的有效时间窗口
        LocalDateTime effectiveFrom = calculateEffectiveFrom(filteredUnits);
        LocalDateTime effectiveTo = calculateEffectiveTo(filteredUnits);

        // 5. 计算查询结果的 calculationEndTime
        // 取 queryTime 和原始 calculationEndTime 的较小值
        LocalDateTime queryCalcEndTime = result.getCalculationEndTime();
        if (queryCalcEndTime == null || queryTime.isBefore(queryCalcEndTime)) {
            queryCalcEndTime = queryTime;
        }

        // 6. 构建结果（保留原始 carryOver，用于 CONTINUE）
        return BillingResult.builder()
            .units(filteredUnits)
            .promotionUsages(filteredUsages)
            .finalAmount(filteredAmount)
            .effectiveFrom(effectiveFrom)
            .effectiveTo(effectiveTo)
            .carryOver(result.getCarryOver())
            .calculationEndTime(queryCalcEndTime)
            .build();
    }

    /**
     * 过滤计费单元
     * 保留 endTime <= queryTime 的单元
     */
    private List<BillingUnit> filterUnits(List<BillingUnit> units, LocalDateTime queryTime) {
        if (units == null) {
            return List.of();
        }
        return units.stream()
            .filter(unit -> unit.getEndTime() != null && !unit.getEndTime().isAfter(queryTime))
            .toList();
    }

    /**
     * 过滤并截取优惠使用情况
     */
    private List<PromotionUsage> filterUsages(List<PromotionUsage> usages, LocalDateTime queryTime) {
        if (usages == null) {
            return List.of();
        }
        return usages.stream()
            .map(usage -> truncateUsage(usage, queryTime))
            .filter(usage -> usage != null && usage.getUsedMinutes() > 0)
            .toList();
    }

    /**
     * 截取单个优惠使用记录
     */
    private PromotionUsage truncateUsage(PromotionUsage usage, LocalDateTime queryTime) {
        if (usage == null || usage.getUsedFrom() == null) {
            return null;
        }

        // 如果使用结束时间超过 queryTime，截取
        if (usage.getUsedTo() != null && usage.getUsedTo().isAfter(queryTime)) {
            long truncatedMinutes = Duration.between(usage.getUsedFrom(), queryTime).toMinutes();

            return PromotionUsage.builder()
                .promotionId(usage.getPromotionId())
                .type(usage.getType())
                .grantedMinutes(usage.getGrantedMinutes())
                .usedMinutes(truncatedMinutes)
                .usedFrom(usage.getUsedFrom())
                .usedTo(queryTime)
                .build();
        }
        return usage;
    }

    private BigDecimal calculateAmount(List<BillingUnit> units) {
        if (units == null || units.isEmpty()) {
            return BigDecimal.ZERO;
        }
        return units.stream()
            .map(unit -> unit.getChargedAmount() != null ? unit.getChargedAmount() : BigDecimal.ZERO)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private LocalDateTime calculateEffectiveFrom(List<BillingUnit> units) {
        if (units == null || units.isEmpty()) {
            return null;
        }
        return units.get(0).getBeginTime();
    }

    private LocalDateTime calculateEffectiveTo(List<BillingUnit> units) {
        if (units == null || units.isEmpty()) {
            return null;
        }
        return units.get(units.size() - 1).getEndTime();
    }

    /**
     * 应用查询时间校验：处理条件免费单元
     * <p>
     * 对于标记为条件免费的单元，如果 queryTime 超出激活窗口，恢复原价：
     * - 取消免费状态
     * - 设置 chargedAmount = originalAmount
     * - 清除 freePromotionId
     * <p>
     * 该方法不修改原始结果，而是返回一个新的 BillingResult（深拷贝单元）。
     */
    private BillingResult applyQueryTimeValidation(BillingResult result, LocalDateTime queryTime) {
        List<BillingUnit> originalUnits = result.getUnits();
        if (originalUnits == null || originalUnits.isEmpty()) {
            return result;
        }

        // 检查是否有条件免费单元
        boolean hasConditionalFree = originalUnits.stream().anyMatch(BillingUnit::isConditionalFree);
        if (!hasConditionalFree) {
            return result;
        }

        // 深拷贝单元并应用校验
        List<BillingUnit> validatedUnits = new java.util.ArrayList<>();

        // 计算前一个单元的累计金额基数
        BigDecimal previousAccumulatedBase = BigDecimal.ZERO;
        if (!result.getUnits().isEmpty()) {
            BillingUnit firstUnit = result.getUnits().get(0);
            BigDecimal firstCharged = firstUnit.getChargedAmount() != null ? firstUnit.getChargedAmount() : BigDecimal.ZERO;
            BigDecimal firstAccumulated = firstUnit.getAccumulatedAmount() != null ? firstUnit.getAccumulatedAmount() : firstCharged;
            previousAccumulatedBase = firstAccumulated.subtract(firstCharged);
        }

        BigDecimal runningTotal = previousAccumulatedBase;

        for (BillingUnit unit : originalUnits) {
            BillingUnit validatedUnit = cloneUnit(unit);

            // 条件免费校验
            if (validatedUnit.isConditionalFree()
                    && validatedUnit.getConditionalFreeUntil() != null
                    && queryTime.isAfter(validatedUnit.getConditionalFreeUntil())) {
                // 超出激活窗口，恢复原价
                validatedUnit.setConditionalFree(false);
                validatedUnit.setConditionalFreeUntil(null);
                validatedUnit.setFree(false);
                validatedUnit.setFreePromotionId(null);
                validatedUnit.setChargedAmount(
                        validatedUnit.getOriginalAmount() != null
                                ? validatedUnit.getOriginalAmount()
                                : BigDecimal.ZERO);
            }

            // 处理 ruleData 中的条件免费部分覆盖
            BigDecimal finalCharged = resolveUnitCharge(validatedUnit, queryTime);
            if (finalCharged.compareTo(validatedUnit.getChargedAmount() != null ? validatedUnit.getChargedAmount() : BigDecimal.ZERO) != 0) {
                validatedUnit.setChargedAmount(finalCharged);
            }

            // 重新计算累计金额
            BigDecimal charged = validatedUnit.getChargedAmount() != null ? validatedUnit.getChargedAmount() : BigDecimal.ZERO;
            runningTotal = runningTotal.add(charged);
            validatedUnit.setAccumulatedAmount(runningTotal);

            validatedUnits.add(validatedUnit);
        }

        // 重新计算最终金额
        BigDecimal finalAmount = validatedUnits.stream()
                .map(u -> u.getChargedAmount() != null ? u.getChargedAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 截取受影响的优惠使用情况
        List<PromotionUsage> filteredUsages = filterUsages(result.getPromotionUsages(), queryTime);

        return BillingResult.builder()
                .units(validatedUnits)
                .promotionUsages(filteredUsages)
                .finalAmount(finalAmount)
                .effectiveFrom(result.getEffectiveFrom())
                .effectiveTo(result.getEffectiveTo())
                .carryOver(result.getCarryOver())
                .calculationEndTime(result.getCalculationEndTime())
                .build();
    }

    /**
     * 深拷贝 BillingUnit
     */
    private BillingUnit cloneUnit(BillingUnit unit) {
        return BillingUnit.builder()
                .beginTime(unit.getBeginTime())
                .endTime(unit.getEndTime())
                .durationMinutes(unit.getDurationMinutes())
                .unitPrice(unit.getUnitPrice())
                .originalAmount(unit.getOriginalAmount())
                .free(unit.isFree())
                .isTruncated(unit.getIsTruncated())
                .freePromotionId(unit.getFreePromotionId())
                .chargedAmount(unit.getChargedAmount())
                .accumulatedAmount(unit.getAccumulatedAmount())
                .conditionalFree(unit.isConditionalFree())
                .conditionalFreeUntil(unit.getConditionalFreeUntil())
                .ruleData(unit.getRuleData())
                .build();
    }

    /**
     * 解析计费单元的实际应收金额
     * 处理 ruleData 中的条件免费部分覆盖标记
     */
    private BigDecimal resolveUnitCharge(BillingUnit unit, LocalDateTime queryTime) {
        if (unit.getRuleData() == null) {
            return unit.getChargedAmount() != null ? unit.getChargedAmount() : BigDecimal.ZERO;
        }

        // 检查 ruleData 中的不确定性标记
        if (unit.getRuleData() instanceof java.util.Map) {
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> data = (java.util.Map<String, Object>) unit.getRuleData();
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> uncertainty = (java.util.Map<String, Object>) data.get("uncertainty");
            if (uncertainty != null && "CONDITIONAL_PARTIAL".equals(uncertainty.get("reason"))) {
                Object validUntilObj = uncertainty.get("validUntil");
                if (validUntilObj instanceof LocalDateTime validUntil) {
                    if (queryTime.isAfter(validUntil)) {
                        // 超出条件窗口，不减免
                        return unit.getOriginalAmount() != null ? unit.getOriginalAmount() : BigDecimal.ZERO;
                    }
                    // 在条件窗口内，按覆盖比例减免
                    long totalMinutes = unit.getDurationMinutes();
                    long coveredMinutes = calculatePartialCoverageMinutes(unit);
                    if (totalMinutes > 0 && coveredMinutes > 0) {
                        BigDecimal original = unit.getOriginalAmount() != null ? unit.getOriginalAmount() : BigDecimal.ZERO;
                        BigDecimal freeRatio = BigDecimal.valueOf(coveredMinutes)
                                .divide(BigDecimal.valueOf(totalMinutes), 6, java.math.RoundingMode.HALF_UP);
                        BigDecimal freeAmount = original.multiply(freeRatio);
                        return original.subtract(freeAmount).max(BigDecimal.ZERO);
                    }
                }
            }
        }

        return unit.getChargedAmount() != null ? unit.getChargedAmount() : BigDecimal.ZERO;
    }

    /**
     * 计算条件免费部分覆盖的分钟数
     * 从单元的 beginTime/endTime 与条件免费时段的交集推导
     */
    private long calculatePartialCoverageMinutes(BillingUnit unit) {
        // 简化处理：部分覆盖的单元，覆盖分钟数 = 单元 duration
        // 因为单元没有被免费时段完全覆盖，但查询层无法知道具体覆盖了多少
        // 精确计算需要在 ruleData 中存储 coveredMinutes
        return unit.getDurationMinutes();
    }
}