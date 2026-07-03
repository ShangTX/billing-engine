# FREE_MINUTES 物化下放到策略侧

---
id: TODO-20260702-004
type: refactor
priority: P2
status: todo
source_git: 81ca938
created_at: 2026-07-02
completed_at:
completed_git:
---

## 背景

当前 `FreeMinuteAllocator` 在 `PromotionEngine` 中集中把 FREE_MINUTES 物化为时间段（`generatedFreeRanges`），混进 `freeTimeRanges` 给所有规则用。这是为单元计费的"完整覆盖才免费"判定服务——需要免费分钟落在哪些时间位置。

但新设计（`docs/superpowers/specs/2026-07-02-duration-rule-and-promotion-two-tier-design.md` 3.3/3.4）指出物化需求按模式区分：

| 模式 | FREE_MINUTES 物化 | 原因 |
|---|---|---|
| CONTINUOUS / UNIT_BASED | 需要 | "完整覆盖才免费"判定需要时间位置 |
| PERIOD | 需要 | 周期内时长计费，需定位免费段在周期/时段中的位置 |
| GLOBAL | 不需要 | 全局累加，封顶按周期数倍乘一次算，按分钟扣减 |

集中物化导致 GLOBAL 时长策略被迫走物化路径，付不必要代价；且物化留在聚合层会让 `PromotionEngine` 按规则+模式决定产出形式，反向耦合。

## 目标

- `PromotionEngine` 不集中物化 FREE_MINUTES，产出规范中间形式（FREE_RANGE 时段 + FREE_MINUTES 分钟数 + AMOUNT/DISCOUNT 标量）
- `FreeMinuteAllocator` 从 `PromotionEngine` 解耦，下放到策略侧
- CONTINUOUS/UNIT_BASED/PERIOD 策略自行物化 FREE_MINUTES（CONTINUOUS/UNIT_BASED 经 `AbstractTimeBasedRule`，PERIOD 经时长策略）
- GLOBAL 策略不物化，按分钟直接扣减 chargedMinutes

## 范围

包含：

- `PromotionEngine` 产出改为规范中间形式：合并后的 FREE_RANGE 时段 + 未物化的 FREE_MINUTES 列表 + AMOUNT/DISCOUNT 标量
- `FreeMinuteAllocator` 移出 `PromotionEngine`，成为策略侧工具
- CONTINUOUS/UNIT_BASED 策略（经 `AbstractTimeBasedRule`）调用 `FreeMinuteAllocator` 物化
- PERIOD 策略物化 FREE_MINUTES（周期内定位）
- GLOBAL 策略不物化，按分钟扣减 chargedMinutes
- 等效金额的 `cloneAndExclude` 适配中间形式（物化未发生，exclude 未物化的 FREE_MINUTES）

不包含：

- 门面策略结构（TODO-20260702-002，本 TODO 的前置）
- 优惠两级模型（TODO-20260702-003）
- FREE_RANGE 的 PromotionUsage 产出（TODO-20260701-001）
- GLOBAL 不物化时的 PromotionUsage 形式（spec §5 开放问题，待定）

## 验收标准

- `PromotionEngine` 不再物化 FREE_MINUTES，产出中间形式
- `FreeMinuteAllocator` 不在 `PromotionEngine`，在策略侧
- CONTINUOUS/UNIT_BASED/PERIOD 策略物化 FREE_MINUTES，行为与现状一致
- GLOBAL 策略不物化，按分钟扣减，结果与物化路径等价（或符合预期语义）
- 等效金额计算在中间形式上 exclude 仍有效
- 现有测试通过

## 相关文件

- `core/src/main/java/cn/shang/charging/promotion/PromotionEngine.java`（产出中间形式，移除 FreeMinuteAllocator）
- `core/src/main/java/cn/shang/charging/promotion/FreeMinuteAllocator.java`（移到策略侧）
- `core/src/main/java/cn/shang/charging/charge/rules/AbstractTimeBasedRule.java`（CONTINUOUS 策略物化）
- 时长策略类（PERIOD 物化、GLOBAL 不物化）
- `billing-api/src/main/java/cn/shang/charging/wrapper/PromotionEquivalentCalculator.java`（适配中间形式）

## 备注

- 依赖 TODO-20260702-002（门面策略结构）：物化下放到策略侧，策略结构先立
- 与 TODO-20260701-001（FREE_RANGE PromotionUsage）关联：PromotionUsage 产出位置随物化下放
- 物化下放消除聚合层对规则+模式的反向耦合（spec 3.3 耦合论证）
- 优先级 P2：GLOBAL 不物化是性能/清晰度优化，物化路径现状可工作
