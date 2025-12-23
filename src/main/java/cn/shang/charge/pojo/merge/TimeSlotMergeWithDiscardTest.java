package cn.shang.charge.pojo.merge;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public class TimeSlotMergeWithDiscardTest {

    record TestData(LocalDateTime start, LocalDateTime end, List<TimeSlot> slots) {

    }

    static void main() {
        TimeSlotMergeWithDiscard merger = new TimeSlotMergeWithDiscard();

        var testData = getTestData2();

        // 整体时间区间：00:00 - 06:00
        LocalDateTime overallStart = testData.start();
        LocalDateTime overallEnd = testData.end();
        var slots = testData.slots();

        // 执行合并
        MergeResult result = merger.mergeTimeSlotsWithDiscard(slots, overallStart, overallEnd);

        // 打印详细结果
        merger.printDetailedResult(result);

        // 验证结果
        System.out.println("\n=== 验证 ===");

        // 1. 检查是否有重叠
        boolean hasOverlap = false;
        List<TimeSlot> merged = result.getMergedSlots();
        for (int i = 0; i < merged.size(); i++) {
            for (int j = i + 1; j < merged.size(); j++) {
                if (merged.get(i).overlaps(merged.get(j))) {
                    hasOverlap = true;
                    System.out.println("错误：发现重叠: " + merged.get(i) + " 和 " + merged.get(j));
                }
            }
        }
        System.out.println("合并后时间段是否有重叠: " + (hasOverlap ? "是" : "否"));

        // 2. 检查是否都在整体区间内
        boolean allInRange = merged.stream()
                .allMatch(s -> !s.getStart().isBefore(overallStart) &&
                        !s.getEnd().isAfter(overallEnd));
        System.out.println("所有时间段都在整体区间内: " + allInRange);

        // 3. 检查时间段1被分割的情况
        List<TimeSlot> slot1Remaining = result.getRemainingParts("slot1");
        List<TimeSlot> slot1Discarded = result.getDiscardedParts("slot1");

        System.out.println("\n时间段1 (02:00-04:00) 分析:");
        System.out.println("原始时长: 120分钟");
        long remainingMinutes = slot1Remaining.stream()
                .mapToLong(TimeSlot::getDurationMinutes)
                .sum();
        long discardedMinutes = slot1Discarded.stream()
                .mapToLong(TimeSlot::getDurationMinutes)
                .sum();
        System.out.println("保留时长: " + remainingMinutes + "分钟");
        System.out.println("被覆盖时长: " + discardedMinutes + "分钟");
        System.out.println("分割为 " + slot1Remaining.size() + " 个时间段");
    }

    static TestData getTestData() {
        // 创建测试数据
        LocalDateTime baseDate = LocalDateTime.of(2024, 1, 1, 0, 0);

        // 整体时间区间：00:00 - 06:00
        LocalDateTime overallStart = baseDate;
        LocalDateTime overallEnd = baseDate.plusHours(6);
        // 创建时间段集合，使用固定ID以便追踪
        List<TimeSlot> slots = new ArrayList<>();

        // 时间段1：低优先级 02:00-04:00 (P2)
        TimeSlot slot1 = new TimeSlot("slot1",
                baseDate.plusHours(2),  // 02:00
                baseDate.plusHours(4),  // 04:00
                2                        // 优先级2
        );
        slots.add(slot1);

        // 时间段2：高优先级 03:00-03:11 (P1)
        TimeSlot slot2 = new TimeSlot("slot2",
                baseDate.plusHours(3),  // 03:00
                baseDate.plusHours(3).plusMinutes(11),  // 03:11
                1                        // 优先级1
        );
        slots.add(slot2);

        // 时间段3：部分在区间外 05:00-07:00 (P2)
        TimeSlot slot3 = new TimeSlot("slot3",
                baseDate.plusHours(5),  // 05:00
                baseDate.plusHours(7),  // 07:00
                2                        // 优先级2
        );
        slots.add(slot3);

        // 时间段4：完全在区间外 07:00-08:00 (P1)
        TimeSlot slot4 = new TimeSlot("slot4",
                baseDate.plusHours(7),  // 07:00
                baseDate.plusHours(8),  // 08:00
                1                        // 优先级1
        );
        slots.add(slot4);
        return new TestData(overallStart, overallEnd, slots);
    }

    static DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    static LocalDateTime parse(String str) {
        return LocalDateTime.parse(str,FORMATTER);
    }

    static TestData getTestData2() {
        return new TestData(
                parse("2025-01-01 10:21:00"),
                parse("2025-01-06 03:42:00"),
                new ArrayList<>(List.of(
                        new TimeSlot("s1", parse("2025-01-01 09:42:00"), parse("2025-01-01 18:11:00"), 1),
                        new TimeSlot("s2", parse("2025-01-01 15:31:00"), parse("2025-01-01 20:33:00"), 2),
                        new TimeSlot("s3", parse("2025-01-01 19:51:00"), parse("2025-01-02 23:17:00"), 1),
                        new TimeSlot("s4", parse("2025-01-02 22:36:00"), parse("2025-01-03 20:10:00"), 0),
                        new TimeSlot("s5", parse("2025-01-03 19:59:00"), parse("2025-01-04 04:17:00"), 2)
                ))
        );
    }
}
