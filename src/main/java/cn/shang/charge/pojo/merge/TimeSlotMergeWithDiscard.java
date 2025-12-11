package cn.shang.charge.pojo.merge;

import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 时间段合并处理器（支持返回舍弃时间段）
 */
public class TimeSlotMergeWithDiscard {

    /**
     * 合并时间段并返回舍弃的部分
     */
    public MergeResult mergeTimeSlotsWithDiscard(List<TimeSlot> slots,
                                                 LocalDateTime overallStart,
                                                 LocalDateTime overallEnd) {
        MergeResult result = new MergeResult();

        if (slots == null || slots.isEmpty() || overallStart.isAfter(overallEnd)) {
            return result;
        }

        // 1. 预处理：截取在整体区间内的部分
        List<TimeSlot> processedSlots = preprocessSlots(slots, overallStart, overallEnd, result);
        if (processedSlots.isEmpty()) {
            return result;
        }

        // 2. 按优先级排序（优先级高的在前）
        processedSlots.sort(Comparator
                .comparingInt(TimeSlot::getPriority)
                .thenComparing(TimeSlot::getStart));

        // 3. 按优先级分组处理
        Map<Integer, List<TimeSlot>> slotsByPriority = new TreeMap<>();
        for (TimeSlot slot : processedSlots) {
            slotsByPriority.computeIfAbsent(slot.getPriority(), k -> new ArrayList<>())
                    .add(slot);
        }

        // 4. 从最高优先级（数字最小）开始处理
        List<TimeSlot> currentMerged = new ArrayList<>();

        for (Map.Entry<Integer, List<TimeSlot>> entry : slotsByPriority.entrySet()) {
            int priority = entry.getKey();
            List<TimeSlot> prioritySlots = entry.getValue();

            // 对同优先级的时间段排序
            prioritySlots.sort(Comparator.comparing(TimeSlot::getStart));

            // 处理同优先级时间段之间的覆盖（开始早的覆盖开始晚的）
            List<TimeSlot> priorityResult = handleSamePriorityCoverage(prioritySlots, result);

            // 将当前优先级的结果与已有的时间段合并
            if (currentMerged.isEmpty()) {
                currentMerged.addAll(priorityResult);
            } else {
                currentMerged = mergeDifferentPriority(currentMerged, priorityResult, priority, result);
            }
        }

        // 5. 最终整理
        List<TimeSlot> finalized = finalizeResult(currentMerged);
        result.getMergedSlots().clear();
        result.getMergedSlots().addAll(finalized);

        return result;
    }

    /**
     * 预处理：截取时间段，记录被舍弃的区间外部分
     */
    private List<TimeSlot> preprocessSlots(List<TimeSlot> slots,
                                           LocalDateTime overallStart,
                                           LocalDateTime overallEnd,
                                           MergeResult result) {
        List<TimeSlot> processed = new ArrayList<>();

        for (TimeSlot originalSlot : slots) {
            if (!originalSlot.isValid()) {
                // 记录无效时间段为被舍弃
                result.addDiscardedSlot(originalSlot.copy());
                continue;
            }

            // 时间段完全在整体区间外
            if (originalSlot.getEnd().isBefore(overallStart) ||
                    originalSlot.getStart().isAfter(overallEnd)) {
                result.addDiscardedSlot(originalSlot.copy());
                continue;
            }

            // 时间段部分在区间外，记录被舍弃的部分
            if (originalSlot.getStart().isBefore(overallStart)) {
                TimeSlot discarded = new TimeSlot(
                        originalSlot.getId(),
                        originalSlot.getStart(),
                        overallStart,
                        originalSlot.getPriority()
                );
                result.addDiscardedSlot(discarded);
            }

            if (originalSlot.getEnd().isAfter(overallEnd)) {
                TimeSlot discarded = new TimeSlot(
                        originalSlot.getId(),
                        overallEnd,
                        originalSlot.getEnd(),
                        originalSlot.getPriority()
                );
                result.addDiscardedSlot(discarded);
            }

            // 截取在整体区间内的部分
            LocalDateTime start = originalSlot.getStart().isBefore(overallStart) ?
                    overallStart : originalSlot.getStart();
            LocalDateTime end = originalSlot.getEnd().isAfter(overallEnd) ?
                    overallEnd : originalSlot.getEnd();

            if (start.isBefore(end)) {
                TimeSlot processedSlot = new TimeSlot(
                        originalSlot.getId(),
                        start,
                        end,
                        originalSlot.getPriority()
                );
                processedSlot.setData(originalSlot.getData());
                processed.add(processedSlot);
            }
        }

        return processed;
    }

    /**
     * 处理同优先级时间段之间的覆盖（开始早的覆盖开始晚的）
     */
    private List<TimeSlot> handleSamePriorityCoverage(List<TimeSlot> slots,
                                                      MergeResult result) {
        if (slots.size() <= 1) {
            return new ArrayList<>(slots);
        }

        List<TimeSlot> coveredSlots = new ArrayList<>(slots);
        List<TimeSlot> finalSlots = new ArrayList<>();

        // 按开始时间排序
        coveredSlots.sort(Comparator.comparing(TimeSlot::getStart));

        TimeSlot current = coveredSlots.getFirst();
        finalSlots.add(current);

        for (int i = 1; i < coveredSlots.size(); i++) {
            TimeSlot next = coveredSlots.get(i);

            if (current.overlaps(next)) {
                // 当前时间段覆盖下一个时间段的重叠部分
                TimeSlot overlap = current.getOverlap(next);
                if (overlap != null) {
                    // 记录被覆盖的部分
                    result.addDiscardedSlot(new TimeSlot(
                            next.getId(),
                            overlap.getStart(),
                            overlap.getEnd(),
                            next.getPriority()
                    ));

                    // 分割下一个时间段
                    List<TimeSlot> splitParts = splitTimeSlot(next, overlap);

                    // 只保留非重叠部分
                    for (TimeSlot part : splitParts) {
                        if (!part.getStart().equals(overlap.getStart()) ||
                                !part.getEnd().equals(overlap.getEnd())) {
                            // 检查是否与当前时间段相邻
                            if (!current.getEnd().equals(part.getStart()) ||
                                    !part.overlaps(current)) {
                                finalSlots.add(part);
                            }
                        }
                    }
                }

                // 更新当前时间段为合并后的（如果下一个开始更早，理论上不会发生）
                if (next.getStart().isBefore(current.getStart())) {
                    current = next;
                    finalSlots.addFirst(current);
                }
            } else {
                finalSlots.add(next);
                current = next;
            }
        }

        // 重新排序
        finalSlots.sort(Comparator.comparing(TimeSlot::getStart));
        return finalSlots;
    }

    /**
     * 合并不同优先级的时间段
     */
    private List<TimeSlot> mergeDifferentPriority(List<TimeSlot> higherPrioritySlots,
                                                  List<TimeSlot> lowerPrioritySlots,
                                                  int lowerPriority,
                                                  MergeResult result) {
        List<TimeSlot> merged = new ArrayList<>(higherPrioritySlots);

        for (TimeSlot lowerSlot : lowerPrioritySlots) {
            List<TimeSlot> remainingParts = new ArrayList<>();
            remainingParts.add(lowerSlot.copy());

            // 检查低优先级时间段是否被任何高优先级时间段覆盖
            for (TimeSlot higherSlot : higherPrioritySlots) {
                List<TimeSlot> newRemaining = new ArrayList<>();

                for (TimeSlot part : remainingParts) {
                    if (part.overlaps(higherSlot)) {
                        TimeSlot overlap = part.getOverlap(higherSlot);
                        if (overlap != null) {
                            // 记录被覆盖的部分
                            result.addDiscardedSlot(new TimeSlot(
                                    part.getId(),
                                    overlap.getStart(),
                                    overlap.getEnd(),
                                    part.getPriority()
                            ));

                            // 分割时间段
                            List<TimeSlot> splitParts = splitTimeSlot(part, overlap);
                            newRemaining.addAll(splitParts.stream()
                                    .filter(p -> !p.getStart().equals(overlap.getStart()) ||
                                            !p.getEnd().equals(overlap.getEnd()))
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
        merged.sort(Comparator.comparing(TimeSlot::getStart));
        return merged;
    }

    /**
     * 分割时间段（移除重叠部分）
     */
    private List<TimeSlot> splitTimeSlot(TimeSlot original, TimeSlot overlapToRemove) {
        List<TimeSlot> parts = new ArrayList<>();

        // 重叠部分在开始
        if (original.getStart().isBefore(overlapToRemove.getStart())) {
            parts.add(new TimeSlot(
                    original.getId(),
                    original.getStart(),
                    overlapToRemove.getStart(),
                    original.getPriority()
            ));
        }

        // 重叠部分在中间（会产生两个部分）
        // 重叠部分在结束
        if (original.getEnd().isAfter(overlapToRemove.getEnd())) {
            parts.add(new TimeSlot(
                    original.getId(),
                    overlapToRemove.getEnd(),
                    original.getEnd(),
                    original.getPriority()
            ));
        }

        return parts;
    }

    /**
     * 最终整理：合并相邻的相同优先级时间段
     */
    private List<TimeSlot> finalizeResult(List<TimeSlot> slots) {
        if (slots.isEmpty()) {
            return slots;
        }

        slots.sort(Comparator
                .comparing(TimeSlot::getStart)
                .thenComparing(TimeSlot::getPriority));

        List<TimeSlot> result = new ArrayList<>();
        TimeSlot current = slots.getFirst().copy();

        for (int i = 1; i < slots.size(); i++) {
            TimeSlot next = slots.get(i);

            // 如果相邻、优先级相同、且ID相同（来自同一个原始时间段）
            if (current.getEnd().equals(next.getStart()) &&
                    current.getPriority() == next.getPriority() &&
                    current.getId().equals(next.getId())) {
                current.setEnd(next.getEnd());
            } else {
                result.add(current);
                current = next.copy();
            }
        }

        result.add(current);

        // 过滤掉空时间段
        return result.stream()
                .filter(slot -> slot.getDurationMinutes() > 0)
                .collect(Collectors.toList());
    }

    /**
     * 辅助方法：打印详细结果
     */
    public void printDetailedResult(MergeResult result) {
        System.out.println("=== 合并结果 ===");
        System.out.println("合并后的时间段 (" + result.getMergedSlots().size() + "个):");
        for (TimeSlot slot : result.getMergedSlots()) {
            System.out.println("  " + slot);
        }

        System.out.println("\n被舍弃的时间段 (" + result.getDiscardedSlots().size() + "个):");
        for (TimeSlot slot : result.getDiscardedSlots()) {
            System.out.println("  " + slot);
        }

        // 按原始时间段分组显示
        System.out.println("\n按原始时间段分组:");
        Set<String> originalIds = new HashSet<>();
        result.getMergedSlots().forEach(s -> originalIds.add(s.getId()));
        result.getDiscardedSlots().forEach(s -> originalIds.add(s.getId()));

        for (String originalId : originalIds) {
            List<TimeSlot> remaining = result.getRemainingParts(originalId);
            List<TimeSlot> discarded = result.getDiscardedParts(originalId);

            if (!remaining.isEmpty() || !discarded.isEmpty()) {
                System.out.println("\n原始时间段 " + originalId.substring(0, 8) + ":");
                System.out.println("  剩余部分: " + remaining.size() + "段");
                remaining.forEach(s -> System.out.println("    " + s));
                System.out.println("  舍弃部分: " + discarded.size() + "段");
                discarded.forEach(s -> System.out.println("    " + s));

                // 计算保留和舍弃的时长
                // 计算保留和舍弃的时长
                long remainingMinutes = remaining.stream()
                        .mapToLong(TimeSlot::getDurationMinutes)
                        .sum();
                long discardedMinutes = discarded.stream()
                        .mapToLong(TimeSlot::getDurationMinutes)
                        .sum();
                System.out.printf("  保留时长: %d分钟, 舍弃时长: %d分钟\n",
                        remainingMinutes, discardedMinutes);

            }
        }
    }

    static void main() {
        // 创建合并器
        TimeSlotMergeWithDiscard merger = new TimeSlotMergeWithDiscard();

    // 定义时间区间
        LocalDateTime start = LocalDateTime.of(2024, 1, 1, 9, 0);
        LocalDateTime end = LocalDateTime.of(2024, 1, 1, 17, 0);

        // 创建时间段（可以携带业务数据）
        TimeSlot meeting1 = new TimeSlot("meeting1",
                LocalDateTime.of(2024, 1, 1, 10, 0),
                LocalDateTime.of(2024, 1, 1, 12, 0),
                2);
        meeting1.setData("团队会议");

        TimeSlot urgentTask = new TimeSlot("urgent",
                LocalDateTime.of(2024, 1, 1, 11, 30),
                LocalDateTime.of(2024, 1, 1, 12, 30),
                1);
        urgentTask.setData("紧急任务");

        List<TimeSlot> slots = Arrays.asList(meeting1, urgentTask);


// 执行合并
        MergeResult result = merger.mergeTimeSlotsWithDiscard(slots, start, end);


    }

}