# 归档：CONTINUE 续算与投影查询机制设计（删除前存档）

**归档日期**: 2026-07-03
**关联**: TODO-20260703-001（彻底去掉 CONTINUE 续算模式）
**存档目的**: 记录当前版本（删除前）为支持 CONTINUE 续算和投影查询而设计的机制，供未来重新实现"继续计算"能力时参考。

---

## 1. 概述

当前版本（截至 `b5da25f`，TODO-20260702-004 完成后）实现了两套相互关联的机制：

1. **CONTINUE 续算**：从上次计算结果继续计算，避免重复收费。适用于长期计费、分批查询。
2. **投影查询**：在已计算的全段结果上，查询任意时间点的费用，避免重算。适用于实时账单展示。

两套机制通过 `accumulatedAmount`（跨段累计金额）和 `ValueSpec`（单元内求值描述）协作。

TODO-20260703-001 决定彻底删除这两套机制，原因见 §5。本存档记录机制设计，供未来参考。

---

## 2. CONTINUE 续算机制

### 2.1 状态载体（3 个 POJO）

| POJO | 位置 | 职责 |
|------|------|------|
| `BillingCarryOver` | `billing/pojo/` | 顶层结转状态：`calculatedUpTo`、`lastTruncatedUnitStartTime`、`truncatedUnitChargedAmount`、`accumulatedAmount`、`segments`（按段 SegmentCarryOver） |
| `SegmentCarryOver` | `billing/pojo/` | 按段结转：`ruleState`（规则周期状态）、`promotionState`（PromotionCarryOver） |
| `PromotionCarryOver` | `billing/pojo/` | 优惠结转：`remainingMinutes`（FREE_MINUTES 剩余）、`usedFreeRanges`（已用 FREE_RANGE 时段） |

### 2.2 核心语义

CONTINUE 的本质是"从上次截断点继续，避免重复收费"，由四个语义组成：

#### 2.2.1 截断单元重算

- **场景**：上次计算被 `calcEndTime` 截断在某个计费单元内部（如 9:30-10:30 单元截断到 9:30），该单元按完整单元收了全额。
- **续算**：下次从 `lastTruncatedUnitStartTime`（9:30）重算，扣减 `truncatedUnitChargedAmount`（已收全额），避免重复。
- **实现**：`BillingService` 调整 `actualBeginTime = lastTruncatedUnitStartTime`；策略侧 `applyCapAndAccumulate` 第一段扣减 `truncatedUnitChargedAmount`。

#### 2.2.2 累计金额续算

- **场景**：跨段累计费用（如封顶判断需要全段累计）。
- **续算**：`previousAccumulatedAmount` 跨段传递，每段从该基数累加 `chargedAmount`，写入 `BillingUnit.accumulatedAmount`。
- **finalAmount**：CONTINUE 模式下取最后一个 `BillingUnit.accumulatedAmount`（含跨段累计）。
- **实现**：`BillingService` 跨段传 `previousAccumulatedAmount`；`ResultAssembler` finalAmount CONTINUE 分支。

#### 2.2.3 周期状态恢复

- **场景**：周期封顶（如每日封顶）需要知道上次算到第几个周期、当前周期累计。
- **续算**：`ruleState`（`RuleState`：cycleIndex/cycleAccumulated/cycleBoundary）跨调用恢复。
- **实现**：`AbstractTimeBasedRule.restoreState`/`initializeState`/`buildRuleOutputState`；`CycleStateManager.updateStateAfter*`。

#### 2.2.4 优惠结转

- **场景**：FREE_MINUTES 跨调用剩余、FREE_RANGE 已用时段不重复。
- **续算**：`PromotionCarryOver.remainingMinutes`（FREE_MINUTES 剩余分钟）、`usedFreeRanges`（已用 FREE_RANGE 时段，从可用时段减去）。
- **实现**：`PromotionEngine` 恢复 `remainingMinutes`/`usedFreeRanges`，`PromotionAggregateUtil.buildCarryOver` 输出新结转。

### 2.3 数据流

```
BillingRequest.previousCarryOver
  → BillingService.calculate
      ├─ 恢复 actualBeginTime（lastTruncatedUnitStartTime 或 calculatedUpTo）
      ├─ 恢复 previousAccumulatedAmount / truncatedUnitChargedAmount
      └─ 逐段：
          ├─ BillingContext 携带 ruleState / promotionCarryOver / previousAccumulatedAmount / truncatedUnitChargedAmount / continueMode
          ├─ PromotionEngine.evaluate（恢复优惠结转）
          ├─ BillingCalculator → 策略（恢复 ruleState、截断重算、累计续算）
          ├─ 策略输出 ruleOutputState（供 buildCarryOverState）
          ├─ 策略写回 aggregate.promotionCarryOver（004 迁移）
          ├─ pool.writeBack（外部优惠跨段，003，非 CONTINUE）
          └─ previousAccumulatedAmount 跨段传递（仅 CONTINUE）
  → ResultAssembler.assemble
      ├─ finalAmount CONTINUE 分支（accumulatedAmount）
      └─ buildBillingCarryOver（提取 ruleOutputState / promotionCarryOver / 截断单元 / accumulatedAmount）
  → BillingResult.carryOver（供下次 CONTINUE）
```

### 2.4 CONTINUE 的设计假设

当前实现隐含假设：
- **单方案续算**：规则类型不变（CONTINUE 适合同一方案续算，不适合方案切换）。
- **单元计费类专属**：截断单元、累计金额续算只对 CONTINUOUS/UNIT_BASED 有意义；时长计费类（PERIOD/GLOBAL）不参与 CONTINUE（spec 3.1）。
- **全局开关**：`previousCarryOver != null` 即 CONTINUE，全局处理。时长计费段忽略 CONTINUE 上下文（空转）。

这些假设是删除决策的根因（见 §5）。

---

## 3. 投影查询机制

### 3.1 ValueSpec 体系

`UnitValueSpec` 是单元内求值描述，回答"如果计费在 queryTime 这一刻结束，命中单元当前应收多少"。

| 实现 | 用途 |
|------|------|
| `FixedValueSpec` | 固定值（单元内费用不变，如全额收费单元） |
| `StepValueSpec` | 阶梯值（条件免费：窗口内 0，窗口外原价） |
| `ProportionalValueSpec` | 线性投影（按分钟线性累计，如 PROPORTIONAL 不足单元） |
| `PiecewiseTimeValueSpec` | 分段投影（单元内不同时段不同价） |
| `MixedUnitValueSpec`（DayNight 私有） | 混合单元投影 |
| `CappedValueSpec`（DayNight 私有） | 封顶后投影 |

协议：
```
UnitValueSpec.project(queryTime, unitBeginTime, unitEndTime) → UnitValueProjection(currentAmount, nextChangeTime)
UnitValueEvaluator.evaluate(spec, queryTime, unitBegin, unitEnd) → UnitValueProjection
```

### 3.2 BillingResultViewer

#### 3.2.1 createQuerySummary

查询摘要核心公式：
```
queryAmount = unit.accumulatedAmount - unit.chargedAmount + valueAt(unit, queryTime)
```

- `accumulatedAmount`：命中单元前的累计（含跨段，CONTINUE 时代）
- `chargedAmount`：命中单元完整结束后的金额
- `valueAt(unit, queryTime)`：命中单元内 queryTime 的投影（ValueSpec）

逻辑：
1. `queryTime > calculationEndTime` 报错
2. 找包含 queryTime 的命中单元
3. 若 queryTime 在单元结束后（命中单元是最后一个）：`amount = accumulatedAmount`
4. 否则：`amount = accumulatedAmount - chargedAmount + projection.currentAmount()`
5. `effectiveTo = projection.nextChangeTime()`

#### 3.2.2 viewAtTime

返回指定时间点的视图（过滤单元 + 重算金额），透传 `carryOver`。

#### 3.2.3 compact 单元投影

`projectCompactUnit`：按子单元时长定位 queryTime 落在第 k 个子单元，累计 = `(k+1) × 子单元单价`。

### 3.3 calculateWithQuery 流程

`BillingTemplate.calculateWithQuery(request, queryTime)`：

1. 正常计算到 `endTime`（可能产出简化单元）
2. 生成 `QuerySummary`（ValueSpec 投影到 queryTime）
3. 如果命中简化单元（`isSimplifiedUnit`），复制请求 + `disableSimplification=true` 重算
4. 基于精确结果重新生成 `QuerySummary`

**核心价值**：
- **非简化场景**：全段计算 + ValueSpec 投影，避免每次查询重算（一次计算 + 多次投影）
- **简化场景**：命中简化单元时禁用简化重算（简化单元不承诺保存完整细节），保证查询精确

### 3.4 简化单元与查询的交互

简化单元（`ruleData.isSimplified=true`）不保存完整单元内部细节，`valueSpec` 可能为 null 或不精确。`calculateWithQuery` 命中简化单元时触发 `disableSimplification` 重算，产出精确单元后再投影。

`SimplifiedUnitMeta.from(unit)` 读取 `ruleData` 的 `isSimplified`/`cycleIndex`/`simplifiedCycleCount`/`simplifiedCycleAmount`。

---

## 4. 机制协作与依赖关系

```
CONTINUE 续算                      投影查询
     │                                │
     ├─ BillingCarryOver              ├─ ValueSpec 体系
     ├─ RuleState（周期状态）          ├─ BillingResultViewer
     ├─ previousAccumulatedAmount ────┼─→ accumulatedAmount（查询公式基数）
     ├─ 截断单元重算                   ├─ calculateWithQuery
     ├─ PromotionCarryOver            ├─ QuerySummary
     └─ ruleOutputState               └─ disableSimplification 重算
                                          │
                          ┌───────────────┘
                          ↓
                    SimplifiedUnitMeta / ruleData
```

**关键耦合点**：
- `accumulatedAmount` 同时服务 CONTINUE（跨段累计续算）和查询（投影基数）
- `isTruncated` 同时服务 CONTINUE（截断单元识别）和不足单元计费（IncompleteUnitChargeMode 触发）
- `ruleData` 同时服务简化单元（isSimplified 标记）和 CONTINUE（cycleIndex 恢复）
- `calcEndTime` 同时服务局部计算和 CONTINUE 起点

---

## 5. 删除原因

### 5.1 CONTINUE 删除原因

1. **全局处理与单元计费专属语义冲突**：CONTINUE 是全局开关，但截断重算/累计续算/周期状态恢复只对单元计费类有意义。时长计费类不参与 CONTINUE 却要处理 CONTINUE 上下文（空转），跨段规则不一致时语义不清。
2. **架构耦合**：carryOver/ruleState/截断重算贯穿 BillingService / 策略 / Assembler / PromotionEngine / 9 个 POJO，与单元计费深度耦合，阻碍新架构演进。
3. **业务语义不贴切**：假设"单方案续算、规则类型不变"，对混合规则类型、分段续算支持不清。
4. **外部依赖低**：基本无人用/仅自用，breaking 可接受；业务可暂缓，无需立即替代。

### 5.2 ValueSpec 删除原因

1. **双轨语义**：ValueSpec（查询投影）与 IncompleteUnitChargeMode（截断计费）是两套独立的"单元内未完成费用"语义，可能配置不一致，导致"查询到 queryTime"与"算到 queryTime"结果不同。
2. **CONTINUE 删除后分离价值减弱**：CONTINUE 时代"中间点查询 + 续算"需要分离（投影避免重算）；删 CONTINUE 后"查询到 queryTime"和"算到 queryTime"语义应统一。
3. **复杂度**：ValueSpec 体系（6 种实现 + Evaluator + Projection）是为投影查询服务的复杂度，删除后查询统一走 IncompleteUnitChargeMode（一套语义）。

### 5.3 保留部分

删除 CONTINUE 和 ValueSpec 后，保留：
- `accumulatedAmount`（段内累计，查询公式基数，但语义从跨段→段内）
- `isTruncated`（不足单元计费触发，非 CONTINUE 专属）
- `calcEndTime`（局部计算用，非 CONTINUE 专属）
- 简化计算（`ruleData` 标记、`buildSimplifiedUnit`、`disableSimplification` 重算）
- 外部优惠跨段共享（`ExternalPromotionPool`，TODO-20260702-003）

---

## 6. 未来重新实现的参考

### 6.1 业务场景识别

重新实现"继续计算"前，先确认业务场景：
- **长期计费**：停车数月，按需查询阶段费用
- **分批查询**：先算到某点，后续继续算
- **实时账单**：高频查询不同时间点费用

不同场景对应不同设计，不一定需要 CONTINUE 的全部机制。

### 6.2 设计建议

#### 6.2.1 CONTINUE 按计费类型分离

未来 CONTINUE 应按计费类型分别处理，而非全局开关：
- 单元计费类（CONTINUOUS/UNIT_BASED）：截断单元重算、累计金额续算
- 时长计费类（PERIOD/GLOBAL）：从 calculatedUpTo 继续（无截断单元概念）
- BillingService 只做通用部分（actualBeginTime = calculatedUpTo），单元计费专属部分下放策略侧

#### 6.2.2 查询语义统一

未来查询应统一为"算到 queryTime"，与截断计费共用 IncompleteUnitChargeMode 一套语义，避免 ValueSpec 双轨。如果"一次计算 + 多次投影"是刚需，再考虑投影机制，但语义必须与截断计费一致。

#### 6.2.3 状态载体精简

未来 carryOver 设计建议：
- 与外部优惠池（ExternalPromotionPool）正交分离（外部优惠跨段共享不依赖 carryOver）
- 仅承载"续算必需"的状态（截断单元、累计金额、周期状态），不混入优惠结转（优惠结转可由外部优惠池或独立机制处理）
- 考虑无状态设计：调用方保存 `calculationEndTime`，下次 `beginTime = 上次终点` 计算，引擎无状态（可能要求 calcEndTime 对齐单元边界）

#### 6.2.4 可复用概念

- **截断单元**（`isTruncated`）：单元被 calcEndTime 截断的概念，与不足单元计费共享
- **段内累计**（`accumulatedAmount`）：段内查询的基数
- **简化单元**（`ruleData.isSimplified`）：长周期简化计算，查询命中时禁用简化重算
- **外部优惠池**（`ExternalPromotionPool`）：跨段优惠一致，与 CONTINUE 正交

### 6.3 重新实现的触发条件

建议在以下条件满足时重新实现：
- 明确的业务场景驱动（非预设"可能需要"）
- 计费类型分离设计已落地（不再全局开关）
- 查询语义统一方案已确定（避免 ValueSpec 双轨复现）

---

## 7. 相关代码位置（删除前）

### CONTINUE 相关
- `billing/pojo/BillingCarryOver.java`、`SegmentCarryOver.java`、`PromotionCarryOver.java`
- `billing/BillingService.java`（CONTINUE 起点恢复、跨段传递）
- `charge/rules/AbstractTimeBasedRule.java`（RuleState、restoreState、buildCarryOverState）
- `charge/rules/daynight/ContinuousStrategy.java`、`DayNightUnitBasedStrategy.java`（截断重算、累计续算）
- `charge/rules/relativetime/RelativeTimeRule.java`、`naturaltime/NaturalTimeRule.java`、`compositetime/CompositeTimeRule.java`
- `charge/rules/daynight/DayNightCycleStateManager.java`、`compositetime/CompositeTimeSimplifiedCycleStateManager.java`（updateStateAfter*）
- `settlement/ResultAssembler.java`（buildBillingCarryOver、finalAmount CONTINUE 分支）
- `promotion/PromotionEngine.java`（CONTINUE 恢复 remainingMinutes/usedFreeRanges）
- `promotion/PromotionAggregateUtil.java`（buildCarryOver、filterCarryOver）
- `charge/rules/BillingRule.java`（buildCarryOverState 接口）
- `charge/rules/CalculationContext.java`（hasContinueMode）

### ValueSpec 相关
- `billing/value/UnitValueSpec.java`、`FixedValueSpec.java`、`StepValueSpec.java`、`ProportionalValueSpec.java`、`PiecewiseTimeValueSpec.java`
- `billing/value/UnitValueEvaluator.java`、`UnitValueProjection.java`
- `charge/rules/daynight/DayNightValueSpecFactory.java`（MixedUnitValueSpec、CappedValueSpec）
- `billing-api/wrapper/BillingResultViewer.java`（createQuerySummary、viewAtTime、投影公式）
- `billing-api/wrapper/BillingTemplate.java`（calculateWithQuery）

### 测试
- `bill-test/src/main/java/cn/shang/charging/ContinueModeTest.java`
- `bill-test/src/main/java/cn/shang/charging/PromotionCarryOverTest.java`
- `bill-test/src/main/java/cn/shang/charging/generator/GeneratedContinueStep.java`
- `bill-test/src/main/java/cn/shang/charging/DayNightQueryValueTest.java`
- `bill-test/src/main/java/cn/shang/charging/BillingApiTest.java`（查询相关）

---

## 8. 参考

- `docs/billing-engine-calculation-flow-zh.md` §3 CONTINUE 处理、§11 单元求值 valueSpec、§12 查询摘要、§13 calculateWithQuery
- `docs/billing-engine-capabilities-zh.md` 查询时点金额能力
- `docs/superpowers/specs/2026-07-02-duration-rule-and-promotion-two-tier-design.md` 3.1 CONTINUE 限定
- `docs/superpowers/specs/2026-04-20-unit-value-spec-design.md` ValueSpec 设计
- `docs/superpowers/specs/2026-03-31-continue-mode-accumulated-amount-design.md` CONTINUE 累计金额设计
- `docs/superpowers/specs/2026-04-01-query-summary-design.md` 查询摘要设计
