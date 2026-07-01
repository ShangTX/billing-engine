# FREE_RANGE 免费时段产出 PromotionUsage

---
id: TODO-20260701-001
type: bug
priority: P2
status: todo
source_git: 37caeb5
created_at: 2026-07-01
completed_at:
completed_git:
---

## 背景

`PromotionUsage` 用于记录优惠使用情况（哪个优惠、覆盖时间范围、扣除分钟数/金额）。当前实现中，只有 `FREE_MINUTES`（免费分钟数）类型优惠在 `FreeMinuteAllocator` 里产出 `PromotionUsage`，`FREE_RANGE`（免费时间段）类型优惠在**所有计费模式**下都没有产出 `PromotionUsage`。

各规则的 `calculate*Internal` 方法中，`promotionUsages` 字段一律填 `new ArrayList<>()`，免费时段的覆盖信息只体现在 `BillingUnit.free` / `BillingUnit.freePromotionId`（单元模式）或 `DurationSegment.chargedMinutes=0`（时长模式）上，没有独立的优惠使用汇总。

## 问题影响

1. **调用方无法从结果层面获知免费段信息**：必须逐单元/逐段解析才能知道哪些时间免费、是哪个优惠导致的
2. **时长模式尤为突出**：时长模式下 `DurationSegment` 不背免费标识（`chargedMinutes=0` 表达免费段），免费原因本应走 `PromotionUsage` 汇总，但 `FREE_RANGE` 不产出，导致时长模式免费段无汇总可查
3. **优惠等效金额分析受限**：`PromotionEquivalentCalculator` 依赖完整结果做对比，`FREE_RANGE` 缺失 `PromotionUsage` 影响分析准确性

## 目标

`FREE_RANGE` 类型优惠在所有计费模式（CONTINUOUS / UNIT_BASED / 时长模式）下产出 `PromotionUsage`，记录：
- 优惠 ID
- 优惠类型（FREE_RANGE）
- 覆盖时间范围（usedFrom / usedTo）
- 扣除分钟数（usedMinutes）
- 等效优惠金额（equivalentAmount，按免费段时长 × 对应单价计算）

## 范围

包含：
- 各规则的 `calculate*Internal` 方法在处理 `FREE_RANGE` 免费段时，产出对应的 `PromotionUsage`
- CONTINUOUS 模式（4 个规则）
- 时长模式（PERIOD / GLOBAL）
- `PromotionUsage` 字段语义确认（usedFrom/usedTo/usedMinutes/equivalentAmount）

不包含：
- `FREE_MINUTES` 的产出逻辑（已实现）
- `AMOUNT` / `DISCOUNT` 的产出逻辑（另行处理）

## 验收标准

- `FREE_RANGE` 免费段在结果中产出 `PromotionUsage`
- `PromotionUsage.usedFrom` / `usedTo` 正确反映免费段实际覆盖时间
- `PromotionUsage.usedMinutes` 正确反映扣除分钟数
- `PromotionUsage.equivalentAmount` 正确反映等效优惠金额
- 各模式测试覆盖

## 相关文件

- `core/src/main/java/cn/shang/charging/promotion/pojo/PromotionUsage.java`
- `core/src/main/java/cn/shang/charging/charge/rules/daynight/DayNightRule.java`
- `core/src/main/java/cn/shang/charging/charge/rules/relativetime/RelativeTimeRule.java`
- `core/src/main/java/cn/shang/charging/charge/rules/compositetime/CompositeTimeRule.java`
- `core/src/main/java/cn/shang/charging/charge/rules/naturaltime/NaturalTimeRule.java`
- `core/src/main/java/cn/shang/charging/charge/rules/daynight/DayNightUnitBasedRule.java`

## 备注

- 与时长计费模式（TODO-20260630-003）关联：时长模式 `DurationSegment` 不背免费标识，依赖本 TODO 产出 `PromotionUsage` 提供免费段汇总
- 优先级 P2：跨模式问题，不影响计费金额正确性，影响结果可追溯性
