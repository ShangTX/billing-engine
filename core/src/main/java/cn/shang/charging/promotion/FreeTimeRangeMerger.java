package cn.shang.charging.promotion;


import cn.shang.charging.promotion.pojo.FreeTimeRange;
import cn.shang.charging.promotion.pojo.TimeRangeMergeResult;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

public class FreeTimeRangeMerger {

    /**
     * 合并时间段并返回舍弃的部分
     */
    public TimeRangeMergeResult merge(List<FreeTimeRange> timeRanges,
                                      LocalDateTime overallStart,
                                      LocalDateTime overallEnd) {
        TimeRangeMergeResult result = new TimeRangeMergeResult();

        if (timeRanges == null || timeRanges.isEmpty() || overallStart.isAfter(overallEnd)) {
            return result;
        }

        // 1. 预处理：截取在整体区间内的部分
        List<FreeTimeRange> processedRanges = preprocessRanges(timeRanges, overallStart, overallEnd, result);
        if (processedRanges.isEmpty()) {
            return result;
        }

        // 2. 按优先级排序（优先级高的在前）
        processedRanges.sort(Comparator.comparingInt(FreeTimeRange::getPriority)
                .thenComparing(FreeTimeRange::getBeginTime));

        // 3. 按优先级分组处理
        Map<Integer, List<FreeTimeRange>> timeRangesByPriority = new TreeMap<>();
        for (FreeTimeRange range : processedRanges) {
            timeRangesByPriority.computeIfAbsent(range.getPriority(), k -> new ArrayList<>()).add(range);
        }

        // 4. 从最高优先级（数字最小）开始处理
        List<FreeTimeRange> currentMerged = new ArrayList<>();

        for (Map.Entry<Integer, List<FreeTimeRange>> entry : timeRangesByPriority.entrySet()) {
            int priority = entry.getKey();
            List<FreeTimeRange> priorityRanges = entry.getValue();

            // 对同优先级的时间段排序
            priorityRanges.sort(Comparator.comparing(FreeTimeRange::getBeginTime));

            // 处理同优先级时间段之间的覆盖（开始早的覆盖开始晚的）
            List<FreeTimeRange> priorityResult = handleSamePriorityCoverage(priorityRanges, result);

            // 将当前优先级的结果与已有的时间段合并
            if (currentMerged.isEmpty()) {
                currentMerged.addAll(priorityResult);
            } else {
                currentMerged = mergeDifferentPriority(currentMerged, priorityResult, priority, result);
            }
        }

        // 5. 最终整理
        List<FreeTimeRange> finalized = finalizeResult(currentMerged);
        result.getMergedRanges().clear();
        result.getMergedRanges().addAll(finalized);

        return result;
    }

    /**
     * 预处理：截取时间段，记录被舍弃的区间外部分
     * 注意：保留从 overallEnd 开始的时间段，用于 extendLastUnit 边界判断
     */
    private List<FreeTimeRange> preprocessRanges(List<FreeTimeRange> timeRanges,
                                                LocalDateTime overallStart,
                                                LocalDateTime overallEnd,
                                                TimeRangeMergeResult result) {
        List<FreeTimeRange> processed = new ArrayList<>();

        for (FreeTimeRange originalRange : timeRanges) {
            if (!originalRange.isValid()) {
                // 记录无效时间段为被舍弃
                result.addDiscardedRange(originalRange.copy());
                continue;
            }

            // 时间段完全在整体区间之前 → 丢弃
            if (originalRange.getEndTime().isBefore(overallStart)) {
                result.addDiscardedRange(originalRange.copy());
                continue;
            }

            // 时间段完全在整体区间之后 → 丢弃
            if (!originalRange.getBeginTime().isBefore(overallEnd)) {
                result.addDiscardedRange(originalRange.copy());
                continue;
            }

            // 仅记录在有效区间之后被舍弃的部分
            if (originalRange.getEndTime().isAfter(overallEnd)) {
                var discarded = new FreeTimeRange()
                        .setId(originalRange.getId())
                        .setBeginTime(overallEnd)
                        .setEndTime(originalRange.getEndTime())
                        .setPriority(originalRange.getPriority())
                        .setPromotionType(originalRange.getPromotionType());
                result.addDiscardedRange(discarded);
            }

            // 截取在整体区间内的部分（必须有正长度）
            LocalDateTime start = originalRange.getBeginTime().isBefore(overallStart) ?
                    overallStart : originalRange.getBeginTime();
            LocalDateTime end = originalRange.getEndTime().isAfter(overallEnd) ?
                    overallEnd : originalRange.getEndTime();

            // 必须有正长度 (start < end)，空时段不进入 mergedRanges
            if (start.isBefore(end)) {
                FreeTimeRange processedRange = new FreeTimeRange()
                        .setId(originalRange.getId())
                        .setBeginTime(start)
                        .setEndTime(end)
                        .setPriority(originalRange.getPriority())
                        .setPromotionType(originalRange.getPromotionType());
                processedRange.setData(originalRange.getData());
                processed.add(processedRange);
            }
        }

        return processed;
    }

    /**
     * 处理同优先级时间段之间的覆盖（开始早的覆盖开始晚的）
     */
    private List<FreeTimeRange> handleSamePriorityCoverage(List<FreeTimeRange> timeRanges, TimeRangeMergeResult result) {
        if (timeRanges.size() <= 1) {
            return new ArrayList<>(timeRanges);
        }

        List<FreeTimeRange> coveredRanges = new ArrayList<>(timeRanges);
        List<FreeTimeRange> finalRanges = new ArrayList<>();

        // 按开始时间排序
        coveredRanges.sort(Comparator.comparing(FreeTimeRange::getBeginTime));

        FreeTimeRange current = coveredRanges.getFirst();
        finalRanges.add(current);

        for (int i = 1; i < coveredRanges.size(); i++) {
            FreeTimeRange next = coveredRanges.get(i);

            if (current.overlaps(next)) {
                // 当前时间段覆盖下一个时间段的重叠部分
                FreeTimeRange overlap = current.getOverlap(next);
                if (overlap != null) {
                    // 记录被覆盖的部分
                    result.addDiscardedRange(new FreeTimeRange()
                            .setId(next.getId())
                            .setBeginTime(overlap.getBeginTime())
                            .setEndTime(overlap.getEndTime())
                            .setPriority(next.getPriority())
                            .setPromotionType(next.getPromotionType()));

                    // 分割下一个时间段
                    List<FreeTimeRange> splitParts = splitFreeTimeRange(next, overlap);

                    // 只保留非重叠部分
                    for (FreeTimeRange part : splitParts) {
                        if (!part.getBeginTime().equals(overlap.getBeginTime()) ||
                                !part.getEndTime().equals(overlap.getEndTime())) {
                            // 检查是否与当前时间段相邻
                            if (!current.getEndTime().equals(part.getBeginTime()) ||
                                    !part.overlaps(current)) {
                                finalRanges.add(part);
                            }
                        }
                    }
                }

                // 更新当前时间段为合并后的（如果下一个开始更早，理论上不会发生）
                if (next.getBeginTime().isBefore(current.getBeginTime())) {
                    current = next;
                    finalRanges.addFirst(current);
                }
            } else {
                finalRanges.add(next);
                current = next;
            }
        }

        // 重新排序
        finalRanges.sort(Comparator.comparing(FreeTimeRange::getBeginTime));
        return finalRanges;
    }

    /**
     * 合并不同优先级的时间段
     */
    private List<FreeTimeRange> mergeDifferentPriority(List<FreeTimeRange> higherPriorityRanges,
                                                       List<FreeTimeRange> lowerPriorityRanges,
                                                       int lowerPriority,
                                                       TimeRangeMergeResult result) {
        List<FreeTimeRange> merged = new ArrayList<>(higherPriorityRanges);

        for (FreeTimeRange lowerRange : lowerPriorityRanges) {
            List<FreeTimeRange> remainingParts = new ArrayList<>();
            remainingParts.add(lowerRange.copy());

            // 检查低优先级时间段是否被任何高优先级时间段覆盖
            for (FreeTimeRange higherRange : higherPriorityRanges) {
                List<FreeTimeRange> newRemaining = new ArrayList<>();

                for (FreeTimeRange part : remainingParts) {
                    if (part.overlaps(higherRange)) {
                        FreeTimeRange overlap = part.getOverlap(higherRange);
                        if (overlap != null) {
                            // 记录被覆盖的部分
                            result.addDiscardedRange(new FreeTimeRange()
                                    .setId(part.getId())
                                    .setBeginTime(overlap.getBeginTime())
                                    .setEndTime(overlap.getEndTime())
                                    .setPriority(part.getPriority())
                                    .setPromotionType(part.getPromotionType()));

                            // 分割时间段
                            List<FreeTimeRange> splitParts = splitFreeTimeRange(part, overlap);
                            newRemaining.addAll(splitParts.stream()
                                    .filter(p -> !p.getBeginTime().equals(overlap.getBeginTime()) ||
                                            !p.getEndTime().equals(overlap.getEndTime()))
                                    .toList());
                        } else {
                            newRemaining.add(part);
                        }
                    } else {
                        newRemaining.add(part);
                    }
                }

                remainingParts = newRemaining;
            }

            // 添加剩余的部分
            merged.addAll(remainingParts);
        }

        // 重新排序
        merged.sort(Comparator.comparing(FreeTimeRange::getBeginTime));
        return merged;
    }

    /**
     * 分割时间段（移除重叠部分）
     */
    private List<FreeTimeRange> splitFreeTimeRange(FreeTimeRange original, FreeTimeRange overlapToRemove) {
        List<FreeTimeRange> parts = new ArrayList<>();

        // 重叠部分在开始
        if (original.getBeginTime().isBefore(overlapToRemove.getBeginTime())) {
            parts.add(new FreeTimeRange()
                    .setId(original.getId())
                    .setBeginTime(original.getBeginTime())
                    .setEndTime(overlapToRemove.getBeginTime())
                    .setPriority(original.getPriority())
                    .setPromotionType(original.getPromotionType()));
        }

        // 重叠部分在中间（会产生两个部分）
        // 重叠部分在结束
        if (original.getEndTime().isAfter(overlapToRemove.getEndTime())) {
            parts.add(new FreeTimeRange()
                    .setId(original.getId())
                    .setBeginTime(overlapToRemove.getEndTime())
                    .setEndTime(original.getEndTime())
                    .setPriority(original.getPriority())
                    .setPromotionType(original.getPromotionType()));
        }

        return parts;
    }

    /**
     * 最终整理：合并相邻的相同优先级时间段
     */
    private List<FreeTimeRange> finalizeResult(List<FreeTimeRange> timeRanges) {
        if (timeRanges.isEmpty()) {
            return timeRanges;
        }

        timeRanges.sort(Comparator
                .comparing(FreeTimeRange::getBeginTime)
                .thenComparing(FreeTimeRange::getPriority));

        List<FreeTimeRange> result = new ArrayList<>();
        FreeTimeRange current = timeRanges.getFirst().copy();

        for (int i = 1; i < timeRanges.size(); i++) {
            FreeTimeRange next = timeRanges.get(i);

            // 如果相邻、优先级相同、且ID相同（来自同一个原始时间段）
            if (current.getEndTime().equals(next.getBeginTime()) &&
                    current.getPriority() == next.getPriority() &&
                    current.getId().equals(next.getId())) {
                current.setEndTime(next.getEndTime());
            } else {
                result.add(current);
                current = next.copy();
            }
        }

        result.add(current);

        // 过滤掉空时间段（但保留 start == end 的边界时间段，用于 extendLastUnit）
        return result.stream()
                .filter(tr -> !tr.getBeginTime().isAfter(tr.getEndTime()))
                .collect(Collectors.toList());
    }

    /**
     * 辅助方法：打印详细结果
     */
    public void printDetailedResult(TimeRangeMergeResult result) {
        System.out.println("=== 合并结果 ===");
        System.out.println("合并后的时间段 (" + result.getMergedRanges().size() + "个):");
        for (FreeTimeRange tr : result.getMergedRanges()) {
            System.out.println("  " + tr);
        }

        System.out.println("\n被舍弃的时间段 (" + result.getDiscardedRanges().size() + "个):");
        for (FreeTimeRange tr : result.getDiscardedRanges()) {
            System.out.println("  " + tr);
        }

        // 按原始时间段分组显示
        System.out.println("\n按原始时间段分组:");
        Set<String> originalIds = new HashSet<>();
        result.getMergedRanges().forEach(s -> originalIds.add(s.getId()));
        result.getDiscardedRanges().forEach(s -> originalIds.add(s.getId()));

        for (String originalId : originalIds) {
            List<FreeTimeRange> remaining = result.getRemainingParts(originalId);
            List<FreeTimeRange> discarded = result.getDiscardedParts(originalId);

            if (!remaining.isEmpty() || !discarded.isEmpty()) {
                System.out.println("\n原始时间段 " + originalId + ":");
                System.out.println("  剩余部分: " + remaining.size() + "段");
                remaining.forEach(s -> System.out.println("    " + s));
                System.out.println("  舍弃部分: " + discarded.size() + "段");
                discarded.forEach(s -> System.out.println("    " + s));

                // 计算保留和舍弃的时长
                // 计算保留和舍弃的时长
                long remainingMinutes = remaining.stream()
                        .mapToLong(r -> Duration.between(r.getBeginTime(), r.getEndTime()).toMinutes())
                        .sum();
                long discardedMinutes = discarded.stream()
                        .mapToLong(d -> Duration.between(d.getBeginTime(), d.getEndTime()).toMinutes())
                        .sum();
                System.out.printf("  保留时长: %d分钟, 舍弃时长: %d分钟\n",
                        remainingMinutes, discardedMinutes);

            }
        }
    }

    static void main() {
        // 创建合并器
        FreeTimeRangeMerger merger = new FreeTimeRangeMerger();

        // 定义时间区间
        LocalDateTime start = LocalDateTime.of(2024, 1, 1, 9, 0);
        LocalDateTime end = LocalDateTime.of(2024, 1, 1, 17, 0);

        // 创建时间段（可以携带业务数据）
        FreeTimeRange meeting1 = new FreeTimeRange()
                .setId("meeting1")
                .setBeginTime(LocalDateTime.of(2024, 1, 1, 10, 0))
                .setEndTime(LocalDateTime.of(2024, 1, 1, 12, 0))
                .setPriority(2);
        meeting1.setData("团队会议");

        FreeTimeRange urgentTask = new FreeTimeRange()
                .setId("urgent")
                .setBeginTime(LocalDateTime.of(2024, 1, 1, 11, 30))
                .setEndTime(LocalDateTime.of(2024, 1, 1, 12, 30))
                .setPriority(1);
        urgentTask.setData("紧急任务");

        List<FreeTimeRange> timeRanges = Arrays.asList(meeting1, urgentTask);


        // 执行合并
        TimeRangeMergeResult result = merger.merge(timeRanges, start, end);


    }
}
