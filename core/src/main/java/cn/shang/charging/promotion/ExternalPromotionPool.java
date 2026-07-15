package cn.shang.charging.promotion;

import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.promotion.pojo.FreeTimeRange;
import cn.shang.charging.promotion.pojo.PromotionGrant;
import cn.shang.charging.promotion.pojo.PromotionUsage;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 外部优惠跨段共享可用量池（TODO-20260702-003）。
 * <p>
 * 外部优惠（{@code externalPromotions}，跟请求走）整笔停车享一次，多分段不重复。
 * 每段从池取剩余量给 {@link PromotionEngine}，段后从 {@link PromotionUsage} 回写扣减。
 * 与 CONTINUE carryOver 正交（carryOver 续算专用，池分段专用）。
 * <p>
 * 处理范围：
 * <ul>
 *   <li>FREE_MINUTES / SMART_FREE_MINUTES：按分钟扣减剩余量</li>
 *   <li>FREE_RANGE：按时段扣减（已用时段从剩余时段减去，可能分裂）</li>
 * </ul>
 * <p>
 * TODO-20260706-002 阶段5：SMART_FREE_MINUTES 跨段共享，与 FREE_MINUTES 同样按分钟扣减。
 */
public final class ExternalPromotionPool {

    // id -> 原始 grant（保留 priority/source/rangeType 等元数据）
    private final Map<String, PromotionGrant> originalGrants = new LinkedHashMap<>();
    // id -> 剩余 FREE_MINUTES / SMART_FREE_MINUTES 分钟
    private final Map<String, Long> remainingMinutes = new HashMap<>();
    // id -> 剩余 FREE_MINUTES / SMART_FREE_MINUTES 的原始类型（回写时按类型匹配 usage）
    private final Map<String, BConstants.PromotionType> minutesType = new HashMap<>();
    // id -> 剩余 FREE_RANGE 时段
    private final Map<String, List<FreeTimeRange>> remainingRanges = new LinkedHashMap<>();

    /**
     * 用外部优惠初始化池。
     */
    public void init(List<PromotionGrant> externalPromotions) {
        if (externalPromotions == null) {
            return;
        }
        for (PromotionGrant grant : externalPromotions) {
            if (grant.getId() == null || grant.getType() == null) {
                continue;
            }
            originalGrants.put(grant.getId(), grant);
            switch (grant.getType()) {
                case FREE_MINUTES, SMART_FREE_MINUTES -> {
                    long mins = grant.getFreeMinutes() != null ? grant.getFreeMinutes() : 0;
                    remainingMinutes.put(grant.getId(), mins);
                    minutesType.put(grant.getId(), grant.getType());
                }
                case FREE_RANGE -> {
                    FreeTimeRange range = toFreeTimeRange(grant);
                    remainingRanges.put(grant.getId(), new ArrayList<>(List.of(range)));
                }
                default -> { /* 未知类型，忽略 */ }
            }
        }
    }

    /**
     * 重置池到初始状态并重新用外部优惠初始化（TODO-20260706-002 阶段6）。
     * <p>
     * 分步重算语义：{@code prepareContexts} 一次初始化，{@code calculateWithContexts}
     * 每次重算前调用本方法重置，避免上一次消去法迭代残留的剩余量污染本次计算。
     */
    public void reset(List<PromotionGrant> externalPromotions) {
        originalGrants.clear();
        remainingMinutes.clear();
        minutesType.clear();
        remainingRanges.clear();
        init(externalPromotions);
    }

    /**
     * 构造剩余外部优惠 PromotionGrant 列表（给本段 PromotionEngine）。
     * <p>
     * FREE_MINUTES/FREE_RANGE 产出剩余量。
     */
    public List<PromotionGrant> remaining() {
        List<PromotionGrant> result = new ArrayList<>();
        // FREE_MINUTES / SMART_FREE_MINUTES 剩余（按原始类型透传）
        for (Map.Entry<String, Long> entry : remainingMinutes.entrySet()) {
            long mins = entry.getValue();
            if (mins <= 0) {
                continue;
            }
            PromotionGrant orig = originalGrants.get(entry.getKey());
            BConstants.PromotionType type = minutesType.getOrDefault(entry.getKey(),
                    BConstants.PromotionType.FREE_MINUTES);
            result.add(PromotionGrant.builder()
                    .id(entry.getKey())
                    .type(type)
                    .source(orig.getSource())
                    .freeMinutes((int) Math.min(mins, Integer.MAX_VALUE))
                    .priority(orig.getPriority())
                    .activationMode(orig.getActivationMode())
                    .build());
        }
        // FREE_RANGE 剩余时段
        for (Map.Entry<String, List<FreeTimeRange>> entry : remainingRanges.entrySet()) {
            PromotionGrant orig = originalGrants.get(entry.getKey());
            for (FreeTimeRange r : entry.getValue()) {
                result.add(toPromotionGrant(r, orig));
            }
        }
        return result;
    }

    /**
     * 从本段 usage 回写扣减剩余量（只扣 FREE_MINUTES/FREE_RANGE，按 id 命中外部优惠）。
     */
    public void writeBack(List<PromotionUsage> usages) {
        if (usages == null) {
            return;
        }
        for (PromotionUsage usage : usages) {
            String id = usage.getPromotionId();
            if (id == null || usage.getType() == null) {
                continue;
            }
            if ((usage.getType() == BConstants.PromotionType.FREE_MINUTES
                    || usage.getType() == BConstants.PromotionType.SMART_FREE_MINUTES)
                    && remainingMinutes.containsKey(id)) {
                long rem = remainingMinutes.get(id);
                remainingMinutes.put(id, Math.max(0, rem - usage.getUsedMinutes()));
            } else if (usage.getType() == BConstants.PromotionType.FREE_RANGE && remainingRanges.containsKey(id)) {
                List<FreeTimeRange> ranges = remainingRanges.get(id);
                remainingRanges.put(id, subtractRange(ranges, usage.getUsedFrom(), usage.getUsedTo()));
            }
        }
    }

    /**
     * 从时段列表减去 [usedFrom, usedTo]（可能分裂为多段）。
     */
    private static List<FreeTimeRange> subtractRange(List<FreeTimeRange> ranges,
                                                     LocalDateTime usedFrom,
                                                     LocalDateTime usedTo) {
        if (usedFrom == null || usedTo == null) {
            return ranges;
        }
        List<FreeTimeRange> result = new ArrayList<>();
        for (FreeTimeRange r : ranges) {
            // 无交集，保留
            if (!r.getBeginTime().isBefore(usedTo) || !r.getEndTime().isAfter(usedFrom)) {
                result.add(r);
                continue;
            }
            // 有交集，分割
            if (r.getBeginTime().isBefore(usedFrom)) {
                result.add(FreeTimeRange.builder()
                        .id(r.getId())
                        .beginTime(r.getBeginTime())
                        .endTime(usedFrom)
                        .priority(r.getPriority())
                        .promotionType(r.getPromotionType())
                        .rangeType(r.getRangeType())
                        .source(r.getSource())
                        .activationMode(r.getActivationMode())
                        .build());
            }
            if (r.getEndTime().isAfter(usedTo)) {
                result.add(FreeTimeRange.builder()
                        .id(r.getId())
                        .beginTime(usedTo)
                        .endTime(r.getEndTime())
                        .priority(r.getPriority())
                        .promotionType(r.getPromotionType())
                        .rangeType(r.getRangeType())
                        .source(r.getSource())
                        .activationMode(r.getActivationMode())
                        .build());
            }
        }
        return result;
    }

    private static FreeTimeRange toFreeTimeRange(PromotionGrant grant) {
        return FreeTimeRange.builder()
                .id(grant.getId())
                .beginTime(grant.getBeginTime())
                .endTime(grant.getEndTime())
                .priority(grant.getPriority())
                .promotionType(grant.getType())
                .rangeType(grant.getRangeType())
                .source(grant.getSource())
                .activationMode(grant.getActivationMode())
                .build();
    }

    private static PromotionGrant toPromotionGrant(FreeTimeRange range, PromotionGrant orig) {
        return PromotionGrant.builder()
                .id(orig.getId())
                .type(BConstants.PromotionType.FREE_RANGE)
                .source(orig.getSource())
                .beginTime(range.getBeginTime())
                .endTime(range.getEndTime())
                .priority(orig.getPriority())
                .rangeType(orig.getRangeType())
                .activationMode(orig.getActivationMode())
                .build();
    }
}
