# Compact 计费结果模式

---
id: TODO-20260623-002
type: feature
priority: P2
status: todo
source_git: 4e081b3
created_at: 2026-06-23
completed_at:
completed_git:
---

## 背景

当前计费结果中每个 `BillingUnit` 独立输出，在细粒度计费场景下会导致结果臃肿。例如停车按每分钟计费，8 小时 = 480 个单元，每个单元包含 `beginTime`、`endTime`、`durationMinutes`、`unitPrice`、`originalAmount`、`chargedAmount`、`accumulatedAmount`、`valueSpec` 等字段，其中大部分连续单元的值完全相同。

需要一种 compact 模式：连续 N 个相同单价、相同时长、相同免费状态、相同 valueSpec、未被截断、时间连续的计费单元，合并为一个 compact 单元，用 `count` 表示合并数量。

## 目标

在不影响现有计算语义和 CONTINUE 模式的前提下，将计费循环从逐单元推进改为边界驱动，计算的自然产物即为 compact 输出，同时显著减少细粒度计费场景下的计算开销和结果体积。

## 范围

包含：

- **边界驱动循环**：计算循环从"逐单元推进 + 每次检查所有边界"改为"先找最近边界（免费起止、时段结束、周期结束、计费结束），一步跳到同质段终点"。循环次数从 O(单元数) 降至 O(打断点数)
- `BillingUnit` 新增 `compact: boolean` 和 `count: int`（默认 1）字段，compact 输出是边界驱动循环的自然产物
- `BillingResultViewer` 适配 compact 单元的查询时点计算
- `effectiveFrom`/`effectiveTo` 语义适配 compact 单元
- 截断单元（`isTruncated=true`）始终以非 compact 形式输出
- `valueSpec` 实现类补充 `equals`/`hashCode` 以支持等价性判断
- CONTINUE 模式：最后一个单元若为 compact 且被截断，先展开再标记截断

不包含：

- 改变 `BillingService` 管道流程
- 简化计算（`SimplifiedUnitMeta`）的重构（compact 与简化是正交特性，但边界驱动的"同质段"与简化计算的"简化段"在概念上可统一，后续可合并）
- 跨分段边界的合并
- `BillingRequest.compactResult` 开关（边界驱动是唯一路径，无需开关）

## 验收标准

- 边界驱动循环的计算结果与现有逐单元循环完全一致
- 连续相同单元合并为一个 compact 单元，`count` 正确
- compact 单元的 `accumulatedAmount` 指向合并段最后一个子单元的累计值
- `BillingResultViewer.createQuerySummary()` 能正确定位 compact 单元内的 queryTime
- 跨分段边界不合并
- 截断单元不参与合并
- 所有现有测试通过
- 典型场景（停车 8h、24h、30 天）循环次数验证下降至打断点数级别

## 关键设计决策（讨论结论）

1. **边界驱动为唯一路径**：逐单元循环在所有真实计费场景下严格劣于边界驱动。真实场景中边界数 B 通常只有单元数 N 的 0.3%~2%，不存在边界密集到每个单元都是边界的情况
2. **compact 是计算的自然产物**：边界驱动循环中，一次迭代处理一个同质段，直接输出 compact 单元，无需后处理合并
3. **accumulatedAmount**：始终指向合并段之后的总费用，语义不变
4. **查询时点**：子单元索引 = `(queryTime - beginTime) / durationMinutes`，依赖 `valueSpec.project()`
5. **截断单元**：始终以非 compact 形式输出
6. **compact 与 SimplifiedUnitMeta**：两者正交，可共存；边界驱动的"同质段"与简化计算的"简化段"在概念上可统一，后续可合并
7. **valueSpec 等价性**：通过 `equals` 判断，需要各实现类补充

## 极端配置防护

边界驱动循环的复杂度为 O(B log B) 预处理 + O(B) 迭代，其中 B = 边界点总数。B 接近 N 的唯一场景是免费时段以计费单元粒度为间隔交替出现（如每分钟交替收费/免费），这在计费业务中不会出现。但为防止恶意或错误配置导致性能退化，应在配置校验层加入边界密度检查：

- 当 `freeTimeRanges 数量 × 2 / 预计单元数 > 阈值（如 0.5）` 时，拒绝配置并给出明确错误信息
- 阈值可配置，默认设为保守值

此项作为 TODO-20260623-002 的附属验收条件，不需要单独追踪事项。

## 性能预期

| 场景 | 单元数 N | 边界数 B | 当前循环次数 | 优化后循环次数 | 减少比例 |
|------|---------|---------|------------|-------------|---------|
| 停车 8h，1min 单元，日夜两时段，两个免费区间 | 480 | ~8 | 480 | ~8 | 98.3% |
| 停车 24h，1min 单元，日夜两时段，无免费 | 1440 | ~5 | 1440 | ~5 | 99.7% |
| 停车 30 天，5min 单元，日夜时段，周末免费 | 8640 | ~70 | 8640 | ~70 | 99.2% |

## 相关文件

- `core/src/main/java/cn/shang/charging/billing/pojo/BillingUnit.java`
- `core/src/main/java/cn/shang/charging/billing/pojo/BillingRequest.java`
- `core/src/main/java/cn/shang/charging/settlement/ResultAssembler.java`
- `core/src/main/java/cn/shang/charging/billing/value/FixedValueSpec.java`
- `core/src/main/java/cn/shang/charging/billing/value/PiecewiseTimeValueSpec.java`
- `core/src/main/java/cn/shang/charging/billing/value/StepValueSpec.java`
- `core/src/main/java/cn/shang/charging/charge/rules/AbstractTimeBasedRule.java`
- `core/src/main/java/cn/shang/charging/charge/rules/compositetime/CompositeTimeRule.java`
- `core/src/main/java/cn/shang/charging/charge/rules/daynight/DayNightRule.java`
- `core/src/main/java/cn/shang/charging/charge/rules/relativetime/RelativeTimeRule.java`
- `billing-api/src/main/java/cn/shang/charging/wrapper/BillingResultViewer.java`

## 备注

- 与 `TODO-20260623-001`（优化自定义规则扩展体验）有协同效应：边界驱动循环应作为 `AbstractTimeBasedRule` 的公共方法，所有规则共享，避免三份重复实现
- 优先级 P2，可在下一轮规则层重构时推进
