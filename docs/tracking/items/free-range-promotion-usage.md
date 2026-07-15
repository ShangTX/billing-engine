# FREE_RANGE 免费时段产出 PromotionUsage

---
id: TODO-20260701-001
type: bug
priority: P2
status: done
source_git: 37caeb5
created_at: 2026-07-01
completed_at: 2026-07-03
completed_git: a1043ab
---

## 背景

`PromotionUsage` 用于记录优惠使用情况（哪个优惠、覆盖时间范围、扣除分钟数/金额）。当前实现中，只有 `FREE_MINUTES`（免费分钟数）类型优惠在 `FreeMinuteAllocator` 里产出 `PromotionUsage`，`FREE_RANGE`（免费时间段）类型优惠在**所有计费模式**下都没有产出 `PromotionUsage`。

各规则的 `calculate*Internal` 方法中，`promotionUsages` 字段一律填 `new ArrayList<>()`，免费时段的覆盖信息只体现在 `BillingUnit.free` / `BillingUnit.freePromotionId`（单元计费类）或 `DurationSegment.chargedMinutes=0`（时长计费类）上，没有独立的优惠使用汇总。

按新设计（`docs/superpowers/specs/2026-07-02-duration-rule-and-promotion-two-tier-design.md` 3.3），FREE_MINUTES 时段化将下放到策略侧，`PromotionUsage` 的产出位置随之移到策略侧（时段化时产出）。本 TODO 的 FREE_RANGE 产出也应对齐到策略侧。

## 问题影响

1. **调用方无法从结果层面获知免费段信息**：必须逐单元/逐段解析才能知道哪些时间免费、是哪个优惠导致的
2. **时长模式尤为突出**：时长模式下 `DurationSegment` 不背免费标识（`chargedMinutes=0` 表达免费段），免费原因本应走 `PromotionUsage` 汇总，但 `FREE_RANGE` 不产出，导致时长模式免费段无汇总可查
3. **优惠等效金额分析受限**：`PromotionEquivalentCalculator` 依赖完整结果做对比，`FREE_RANGE` 缺失 `PromotionUsage` 影响分析准确性

## 目标

`FREE_RANGE` 类型优惠在所有计费模式（CONTINUOUS / UNIT_BASED / PERIOD / GLOBAL）下产出 `PromotionUsage`，记录：
- 优惠 ID
- 优惠类型（FREE_RANGE）
- 覆盖时间范围（usedFrom / usedTo）
- 扣除分钟数（usedMinutes）
- 等效优惠金额（equivalentAmount，按免费段时长 × 对应单价计算）

## 范围

包含：
- 各策略在处理 `FREE_RANGE` 免费段时，产出对应的 `PromotionUsage`（产出位置随时段化下放到策略侧，见 spec 3.3）
- 单元计费类策略（CONTINUOUS / UNIT_BASED）
- 时长计费类策略（PERIOD / GLOBAL）
- `PromotionUsage` 字段语义确认（usedFrom/usedTo/usedMinutes/equivalentAmount）

不包含：
- `FREE_MINUTES` 的产出逻辑（已实现，时段化下放见 TODO-20260702-004）
- `AMOUNT` / `DISCOUNT` 的产出逻辑（另行处理）

## 待定（GLOBAL 策略的 usage 形式）

GLOBAL 策略侧时段化 FREE_MINUTES，但最终输出收费汇总桶，不把免费段落为 `DurationSegment`；其 `PromotionUsage` 形式与 PERIOD 的时间轴明细不同，影响等效金额计算的取用方式。本 TODO 实现时需先定 GLOBAL usage 形式。

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

- 与门面策略结构重构（TODO-20260702-002）和 FREE_MINUTES 时段化下放（TODO-20260702-004）关联：产出位置随时段化下放到策略侧，需在策略结构落地后实现
- 优先级 P2：跨模式问题，不影响计费金额正确性，影响结果可追溯性

## GLOBAL usage 形式（待定项结论）

FREE_RANGE 本就是时间区间，所有消费方（含 GLOBAL）按时段消费，usage 形式一致：
`usedFrom`/`usedTo`/`usedMinutes` 反映 FREE_RANGE 在窗口内实际覆盖，`equivalentAmount` 从
`DurationSegment.originalAmount` 聚合。GLOBAL 的 FREE_RANGE usage 不依赖 FREE_MINUTES 时段化
（spec §5 待定的 GLOBAL usage 形式指 FREE_MINUTES，非 FREE_RANGE）。

## 验证

```bash
mvn -pl bill-test -am test
# Tests run: 87, Failures: 0, Errors: 0, Skipped: 0
# 含 FreeRangePromotionUsageTest 4 模式（CONTINUOUS/UNIT_BASED/PERIOD/GLOBAL）
```
