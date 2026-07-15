# 实施计划：FREE_MINUTES 时段化下放到策略侧（TODO-20260702-004）

**关联 spec**: `docs/superpowers/specs/2026-07-02-duration-rule-and-promotion-two-tier-design.md` §3.3/3.4
**详情**: `docs/tracking/items/free-minutes-materialization-delegation.md`
**基线**: 89 测试全绿（`mvn -pl bill-test -am test`）

---

## 1. 现状与问题

`PromotionEngine.evaluate`（`core/.../promotion/PromotionEngine.java:108-113`）集中调 `FreeMinuteAllocator.allocate` 把 FREE_MINUTES 时段化为时间段，混入 `freeTimeRanges` 给所有策略用。问题：

- GLOBAL 时长策略也需要策略侧时段化，但最终不应把免费段作为时间轴明细落盘。
- 时段化留在聚合层 → `PromotionEngine` 需按"规则+模式"决定产出形式 → 反向耦合。

## 2. 评估阶段的关键发现（超出原任务描述的范围）

1. **4 个 CONTINUOUS 规则族都消费 `freeTimeRanges`**：DayNight（`ContinuousStrategy`）/RelativeTime/NaturalTime/CompositeTime 都继承 `AbstractTimeBasedRule`，在 `calculate` 中读 `promotionAggregate.getFreeTimeRanges()` 用于 `splitTimeAxis`/`runBoundaryDrivenLoop`。移除时段化后必须各自时段化，否则充电 break。
2. **RelativeTime + FREE_MINUTES + CONTINUE 被显式测试**：`PromotionCarryOverTest`、`ContinueModeTest.testContinue_FreeMinutesCarryOver_RelativeTime`。`buildPromotionCarryOver` 当前在 `PromotionEngine` 内依赖 materialization 产出的 usages → 时段化下放后 carryOver 构建必须迁移，否则续算 break。
3. **RelativeTime/NaturalTime/CompositeTime 当前不产出 promotionUsages**（`.promotionUsages(new ArrayList<>())`），FREE_MINUTES usages 被 PromotionEngine 产出后被这些规则丢弃——这是 TODO-20260701-001 的既有缺口（FREE_RANGE usage 只在 DayNight 落地）。本 TODO 须保留此既有行为（"行为不变"），不扩大修复。
4. **ExternalPromotionPoolTest / DurationBillingModeTest 仅覆盖 DayNight**：非 DayNight 规则的外部池、GLOBAL+FREE_MINUTES 无现成测试 → 004 须新增。

## 3. 设计决策

### D1. PromotionAggregate 中间形式
- 新增 `freeMinutesList`（`List<FreeMinutes>`，未时段化）。区别于既有 `freeMinutes`（long 标量，保留，用于简化计算判定，由 `freeMinutesList` 求和）。
- `freeTimeRanges` 改为仅含 FREE_RANGE（已合并，不含时段化 FREE_MINUTES）。
- `usages`：PromotionEngine 不再填充（策略侧产出）。aggregate 中置 null/空。
- `promotionCarryOver`：PromotionEngine 不再构建（见 D5）。
- `amountDiscounts`/`totalAmountDiscount`/`bestDiscountRate`：不变。

### D2. FreeMinuteAllocator 下放为策略侧工具
- 不再注入 `PromotionEngine`。保留类与包（`promotion`），新增方法 `allocateAndMerge(List<FreeMinutes>, List<FreeTimeRange> freeRangeOnly, CalculationWindow)`：
  - 内部 `new FreeTimeRangeMerger()` 做 FREE_RANGE + 生成段 的最终合并。
  - 返回 `FreeMinuteAllocationResult`（字段 `generatedFreeRanges` 改义为 **finalFreeRanges**=合并后全量免费段；`promotionUsages`=FREE_MINUTES usages，含 granted/used minutes）。
- 旧 `allocate`（FREE_MINUTES only，不合并）保留为 private/内部，或合并进 `allocateAndMerge`。

### D3. AbstractTimeBasedRule 提供 protected 时段化 helper
供 4 个 CONTINUOUS 规则族 + DayNightUnitBasedStrategy 共用：
```java
protected FreeMinuteAllocationResult materializeFreeMinutes(
        PromotionAggregate aggregate, CalculationWindow window)
// freeMinutesList 为空 → 返回空 result（finalFreeRanges = aggregate.freeTimeRanges，usages 空）
// 否则调 FreeMinuteAllocator.allocateAndMerge(freeMinutesList, aggregate.freeTimeRanges, window)
```
- `DayNightUnitBasedStrategy` 不继承 AbstractTimeBasedRule → 直接调 `FreeMinuteAllocator.allocateAndMerge`（或经静态包装）。

### D4. 各策略消费方式
| 策略 | FREE_MINUTES 处理 | usages 产出 |
|------|-------------------|-------------|
| CONTINUOUS（DayNight/Relative/Natural/Composite 经 AbstractTimeBasedRule） | 调 `materializeFreeMinutes` → finalFreeRanges 用于 `splitTimeAxis`/`runBoundaryDrivenLoop` | DayNight：合并 FREE_MINUTES usages（替代旧 `aggregate.getUsages()`）；其余 3 规则：**保留既有空 usages 行为**（仅用 ranges，丢弃 usages） |
| DayNightUnitBasedStrategy | 调 `allocateAndMerge` → finalFreeRanges 用于"完整覆盖才免费"判定 | 合并 FREE_MINUTES usages（同现状） |
| DayNightDurationStrategy PERIOD | 调 `allocateAndMerge` → finalFreeRanges 用于免费段 | 合并 FREE_MINUTES usages |
| DayNightDurationStrategy GLOBAL | 策略侧时段化；免费段参与边界驱动与收费分钟扣除，但最终不落 `DurationSegment` | 产出 FREE_MINUTES usage |

### D5. PromotionCarryOver 构建迁移（必须，保 CONTINUE）
- `PromotionEngine.buildPromotionCarryOver` 逻辑移到 `PromotionAggregateUtil.buildCarryOver(List<PromotionUsage> freeMinutesUsages, List<FreeTimeRange> finalFreeRanges, LocalDateTime calcEnd)`（static）。
- `PromotionEngine` 不再构建 carryOver（aggregate.promotionCarryOver = null）。
- 各策略在产出 usages + finalFreeRanges 后，调 `buildCarryOver` 并**写回 `aggregate.promotionCarryOver`**（mutate，per-segment aggregate 不跨段共享，安全）。`ResultAssembler.extractPromotionCarryOver` 读取路径不变。
- **关键**：RelativeTime/NaturalTime/CompositeTime 虽丢弃 result.usages，但**必须写回 carryOver**（用 helper 的 usages + finalFreeRanges 构建），否则 `testContinue_FreeMinutesCarryOver_RelativeTime` 等 break。
- GLOBAL：用其 FREE_MINUTES usages（分钟扣减）+ `aggregate.freeTimeRanges`（FREE_RANGE）构建 carryOver（GLOBAL 不参与 CONTINUE，构建仅为格式一致）。

### D6. GLOBAL 免费分钟消费语义（2026-07-15 修订）
**决策**：GLOBAL 改为策略侧时段化，和 PERIOD 共用免费段物化入口；最终金额与原分钟流扣减等价，但输出不落免费段，只保留收费汇总桶。

旧分钟流扣减方案曾计划在 `buildDurationSegmentsGlobalMode` 的 rawCharges 与 period 封顶之间插入扣减 pass；该方案已被通用 `DurationGlobalStrategy` 的时段化 + 汇总桶输出取代。保留等价性论证如下：
1. pass1：rawCharges[i] = segmentCharge（FREE_RANGE 段=0，其余按规则算）；同时记 `chargedMinutes[i]`。
2. **新 pass1.5（FREE_MINUTES 扣减）**：按 priority 排序 freeMinutesList，顺序消费。游标从 calcBegin 起，跳过 FREE_RANGE 免费段，对后续段按分钟扣减 `chargedMinutes[i]`（可部分扣减），同步缩减 `rawCharges[i]`（`rawCharges = chargedMinutes × price / unitMinutes`），累计 `usedMinutes` 与扣减起止点。
3. pass2：period 封顶（用扣减后 rawCharges，等价于 materialized 下 FREE_MINUTES 段不进 period 累计）。
4. pass3：构建 DurationSegment + cycle 封顶。

等价性论证：FreeMinuteAllocator 从窗口起点、跳过 FREE_RANGE、按 priority 顺序填空隙 → 与 pass1.5 游标逻辑一致 → 扣减的分钟与金额与 materialized 下"FREE_MINUTES 段免费"相同 → finalAmount 等价。

FREE_MINUTES usage 形式：`usedMinutes`=实际扣减分钟，`usedFrom`=首个扣减段起点，`usedTo`=扣减结束点。给等效金额计算提供时间位置（见 D7）。

### D7. 等效金额计算适配（`PromotionEquivalentCalculator`）
- `cloneAndExclude` → `PromotionAggregateUtil.exclude` 须过滤新 `freeMinutesList`（按 id），重算 `freeMinutes`（标量）。
- `extractAndSortRanges`：当前要求 `usedFrom/usedTo != null`。GLOBAL FREE_MINUTES usage 已设名义 usedFrom/usedTo（D6）→ 可进入排序迭代，等效金额对其生效。
- 消去法：exclude 命中 `freeMinutesList` 的 id → 策略重算时 freeMinutesList 减少 → CONTINUOUS 时段化少一段 / GLOBAL 扣减少分钟 → 费用上升 → equivalent = 差值。✓

### D8. PromotionEngine.evaluate 精简后产出
```
freeTimeRanges = 合并后的 FREE_RANGE（不含 FREE_MINUTES）
freeMinutesList = 未时段化 FREE_MINUTES（已应用 remainingMinutes）
freeMinutes = sum(freeMinutesList.minutes)  // 简化计算判定用
usages = null
promotionCarryOver = null
amountDiscounts / totalAmountDiscount / bestDiscountRate  // 不变
```
保留：FREE_RANGE 合并（`FreeTimeRangeMerger`）、CONTINUE 恢复（`applyRemainingMinutes`/`filterUsedFreeRanges`，作用于 freeMinutesList/freeTimeRanges）、AMOUNT/DISCOUNT 汇总。

## 4. 改动文件清单

**core**
- `promotion/PromotionAggregate.java`：加 `freeMinutesList` 字段；`isEmpty`/`hasMultiplePromotionTypes` 适配。
- `promotion/PromotionEngine.java`：移除 `FreeMinuteAllocator` 依赖与调用、移除 `buildPromotionCarryOver`；产出中间形式（D8）。
- `promotion/FreeMinuteAllocator.java`：加 `allocateAndMerge`（含 FreeTimeRangeMerger）；`FreeMinuteAllocationResult` 字段改义/重命名。
- `promotion/PromotionAggregateUtil.java`：加 `buildCarryOver`；`exclude` 过滤 `freeMinutesList` + 重算 `freeMinutes`。
- `charge/rules/AbstractTimeBasedRule.java`：加 `materializeFreeMinutes` helper；`findCyclesWithPromotion` 仍用 `freeMinutes` 标量（不变）。
- `charge/rules/daynight/ContinuousStrategy.java`：调 helper → finalFreeRanges；usages 从 helper 取；写回 carryOver。
- `charge/rules/daynight/DayNightUnitBasedStrategy.java`：调 `allocateAndMerge`；usages；写回 carryOver。
- `charge/rules/daynight/DayNightDurationStrategy.java`：PERIOD 调 `allocateAndMerge`；GLOBAL 分钟扣减（D6）；写回 carryOver。
- `charge/rules/relativetime/RelativeTimeRule.java`、`naturaltime/NaturalTimeRule.java`、`compositetime/CompositeTimeRule.java`：调 `materializeFreeMinutes` 取 finalFreeRanges 用于充电；**写回 carryOver**；保留既有空 result.usages 行为。

**billing-api**
- `wrapper/PromotionEquivalentCalculator.java`：`extractAndSortRanges`/`cloneAndExclude` 适配（D7，主要靠 `PromotionAggregateUtil.exclude`）。

**spring-boot-starter（v3/v4）**
- `BillingAutoConfiguration.java`：`PromotionEngine` 构造去掉 `FreeMinuteAllocator` 参数；移除 `freeMinuteAllocator()` bean（或保留 bean 但不注入）。

**bill-test**
- 所有 `new PromotionEngine(resolver, merger, allocator, registry)` → `new PromotionEngine(resolver, merger, registry)`（~20 文件，机械改）。
- 新增 004 测试：GLOBAL+FREE_MINUTES 与时段化路径 finalAmount 等价；CONTINUOUS/UNIT_BASED/PERIOD 时段化行为与现状一致；CONTINUE+RelativeTime+FREE_MINUTES 续算（既有用例保绿）。

## 5. 验证

- `mvn -pl bill-test -am test`：既有 89 + 新增 004 测试全绿。
- 重点回归：`PromotionCarryOverTest`、`ContinueModeTest`（CONTINUE+FREE_MINUTES）、`ExternalPromotionPoolTest`（外部池回写）、`DurationBillingModeTest`（PERIOD/GLOBAL）、`PromotionEquivalentCalculatorTest`（等效金额）、`RelativeTimeTest`/`RelativeTimeParkingParityTest`。

## 6. 文档同步

- `docs/billing-engine-calculation-flow-zh.md`：§7 优惠聚合 + 流程图行 48/60（已描述期望状态，补 004 落地说明）。
- `docs/billing-engine-capabilities.md` / `-zh.md`：§7 优惠聚合（FREE_MINUTES 时段化位置）。
- `docs/tracking/items/free-minutes-materialization-delegation.md`：status done + 验证命令 + completed_git。
- `docs/TODO.md` → `docs/DONE.md` 迁移。

## 7. 提交

1. `[claude-code|opus-4-8|superpowers] refactor: FREE_MINUTES 时段化下放到策略侧（TODO-20260702-004）` — 实现 + 文档同步。
2. `[claude-code|opus-4-8|superpowers] docs: TODO-20260702-004 FREE_MINUTES 时段化下放迁移 DONE` — TODO→DONE，`completed_git` 指向提交 1。

trailer：`Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`。

## 8. 风险与回退

- **风险**：carryOver 迁移若遗漏某策略写回 → CONTINUE break。缓解：所有策略统一调 `buildCarryOver` 写回；`PromotionCarryOverTest`/`ContinueModeTest` 全绿作守卫。
- **风险**：GLOBAL 分钟扣减与时段化 finalAmount 不等价。缓解：新增等价性测试；pass1.5 游标严格对齐 FreeMinuteAllocator 语义。
- **风险**：非 DayNight 规则既有空 usages 行为若被误改 → 外部池行为变。缓解：仅取 ranges，不合并 usages；`ExternalPromotionPoolTest`（DayNight only）+ 既有 RelativeTime 测试保绿。
