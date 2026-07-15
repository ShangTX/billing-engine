# 计费引擎抽象重构设计:模式行为驱动 + 规则语义注入

**状态**: 已实现（阶段 0-7 完成，主体重构落地）
**日期**: 2026-07-06（设计）→ 2026-07-06（实现完成）
**关联**: [2026-07-02-duration-rule-and-promotion-two-tier-design.md](./2026-07-02-duration-rule-and-promotion-two-tier-design.md)(前置 spec)、TODO-20260702-002(门面策略)、TODO-20260702-004(FREE_MINUTES 时段化)、TODO-20260703-001(CONTINUE 移除)、TODO-20260706-001(GLOBAL 时段化修复，阶段 0)

---

## 1. 背景

前置 spec(2026-07-02)确立了"门面 + 策略"结构与优惠两级模型,已完成 DayNight 规则族的门面化(TODO-20260702-002)和 FREE_MINUTES 时段化下放(TODO-20260702-004)。但仅有 DayNight 完成门面化,其余 3 个规则族(RelativeTime/NaturalTime/CompositeTime)仍继承 `AbstractTimeBasedRule`,且 DurationMode 仅 DayNight 实现。

在推进"其余规则族接入 DurationMode"时,发现现有抽象存在 5 个结构性问题,且 FREE_MINUTES 处理有一处历史错误(GLOBAL 模式不时段化)。本 spec 给出完整的抽象重构方案,作为后续多规则族、多模式演进的基础。

## 2. 现有抽象的结构性问题

### 2.1 四份近乎拷贝的 applyCapAndAccumulate

`ContinuousStrategy`(dayNight)、`RelativeTimeRule`、`NaturalTimeRule`、`CompositeTimeRule` 各持一份 `applyCapAndAccumulate`,核心循环 90% 相同:`subCount → isTruncated → cycleCapped → incompleteFree → charged 计算 → BillingUnit 构建 → 周期切换`。差异仅三处:周期切换判定方式、是否有 periodCap(仅 CompositeTime)、cap 标记字符串。`generateSimplifiedUnitsForContinuous` 同样两份拷贝。

### 2.2 AbstractTimeBasedRule 身份混乱

同时扮演四个角色:CONTINUOUS 策略基类、3 个 Rule 类的基类、static 工具宿主、旧切段模型容器(`TimeFragment`/`splitTimeAxis`/`organizeByCycle`)。3 个旧 Rule 类继承它却不用其 `calculate`,又委托给 `ContinuousCalculator`,Calculator 反调 Rule 的 `calculateContinuousInternal`——继承与委托拧在一起。

### 2.3 两套切段模型并存

- 旧:`splitTimeAxis` → `TimeFragment` → `organizeByCycle` → `CycleFragments` → `generateUnitsForCycle`
- 新:`BoundaryDrivenLoop` → `HomogeneousSegment` → `applyCapAndAccumulate`

`ContinuousStrategy` 简化路径用旧模型,非简化用新模型——同一策略两套切段。

### 2.4 DurationMode 实现锁在 dayNight

`DayNightDurationStrategy` ~90% 是通用逻辑(两个 build、FREE_MINUTES 处理、封顶数学),仅 `dayNightBoundary` provider 与 day/night 标签私有。其余规则族接入需复制 4 份。

### 2.5 模式分派散落

`BillingCalculator` 校验、`DayNightRule.calculate` 实际分派、3 个旧规则无分派点(只支持 CONTINUOUS,durationMode 进来直接抛异常)。

### 2.6 根源

抽象的正交维度选错:按"规则族 × 模式"两个维度组织实现,但规则族差异(周期模型、时段模型、价格)是**参数**,模式差异(切分、产出、封顶、优惠消费)才是**逻辑**。当前 N×M 个实现点,DurationMode 接入后将失控。

## 3. FREE_MINUTES 处理的历史错误与纠正

### 3.1 GLOBAL 模式也需时段化

前置 spec [3.3](./2026-07-02-duration-rule-and-promotion-two-tier-design.md) 论述"GLOBAL 无需时间位置,按分钟扣减",实现为 `deductFreeMinutesGlobal`(从窗口起点顺序扣减 chargedMinutes)。

**验证结论**:`deductFreeMinutesGlobal` 用"从窗口起点顺序 + `Math.min(remaining, available)`"隐式分配,与 `FreeMinuteAllocator`(同时段化路径)用同一分配策略,**最终金额(含每 period 金额)等价**。

**真实问题**(金额没错,语义有缺陷):
1. **DurationSegment 失去同质性**:一个段时间跨度 240min、chargedMinutes=180,免费部分藏在段内不独立,违背"同质段"契约,可追溯性受损
2. **业务语义焊死**:"从窗口起点消费"这个业务决策(对应 startFree 前 N 分钟免费)硬编码在扣减循环,未来改分配策略会静默算错

**spec 3.3 范畴错误**:从"封顶不需时间位置"正确推出"金额不需时段化",却错误推广到"明细也不需时段化"。封顶确实不需时间位置(GLOBAL 全局倍乘),但明细产出需要(保证段同质)。

**2026-07-15 修订**:GLOBAL 仍需先把 `FREE_MINUTES` / `SMART_FREE_MINUTES` 物化为免费时段,用于边界驱动切割、优惠用量追踪和收费分钟扣除;但最终 `DurationSegment` 不再按时间轴落免费段,而是只输出收费汇总桶。免费原因与使用分钟统一通过 `PromotionUsage` 表达。

### 3.2 FREE_MINUTES 处理的分层

| 层 | 职责 | 公共/特化 |
|----|------|-----------|
| 分配策略 | 选哪些时段消费(起点/优先高价/优先低价) | 公共可插拔 |
| 消费实现 | 体现在产出上 | **模式特化** |

消费语义根本不同:
- 时长模式:按分钟切分(线性,N 分钟 = N 分钟免费,段内可切分子段)
- 单元模式:按单元标记完整覆盖(离散,完整覆盖才免费,余量浪费)

**关键约束**:CONTINUOUS 模式的"边界驱动切断"语义要求免费段参与边界驱动(前置),后置会退化为 UNIT_BASED 的"完整覆盖"语义。因此 FREE_MINUTES 处理必须**前置**(所有模式统一前置),不支持后置两阶段。

### 3.3 优先高价(智能免费)的实现

"优先覆盖高费用时段"需要价格信息(规则私有)。两种实现方式:

- **priceFunction 注入公共 allocator**:违反 spec 3.3"聚合层不预知规则"原则,把规则价格语义拉进优惠分配层
- **SMART_FREE_MINUTES 类型 + 规则侧分配**(采用):优惠层只透传标量,规则侧消费时用自己的 config 价格信息分配,完全符合分层

采用后者。优先高价仅 GLOBAL 模式实现(GLOBAL 后置可控、封顶全局倍乘不与分配交互,复杂度低;其余模式需处理单元截断/周期封顶交互,复杂度超主功能比例)。

## 4. 设计目标

- 模式行为与规则语义正交解耦:规则族提供语义,模式提供行为,N+M 实现点
- 消除 4 份 applyCapAndAccumulate 重复
- DurationMode 实现通用化,4 规则族声明即支持
- FREE_MINUTES 分配策略公共化,消费模式特化
- 简化路径保留,实现优化(全局空隙),删旧切段模型
- 纠正 GLOBAL 时段化错误
- 模式枚举合并,消除互斥分派冗余

## 5. 设计方向

### 5.1 四层架构

```
层0  RuleSemantics(规则族实现,描述"是什么")
      周期/时段/单元边界provider + 价格函数 + PeriodLabeler
      + 封顶配置 + 周期切换判定 + 不足单元配置
层1  BoundaryDrivenLoop(纯调度,0 计费语义,不变)
      BoundaryProvider / HomogeneousSegment
层2  ModeStrategy(4 个实现,描述"怎么算")
      ContinuousStrategy / UnitBasedStrategy
      DurationPeriodStrategy / DurationGlobalStrategy
      各接收 (RuleSemantics, context, aggregate),复用层1
层3  BillingRule 门面(纯分派)
      DayNightRule / RelativeTimeRule / ...
      构造 RuleSemantics,按 calculationMode 委托对应 ModeStrategy
```

**正交收益**:新增规则族 → 实现 RuleSemantics + 门面,4 模式自动可用;新增模式 → 实现一个 ModeStrategy,所有规则族自动可用。

### 5.2 RuleSemantics 契约

规则族实现的语义接口,描述"是什么",不含计算逻辑:

```java
interface RuleSemantics<C extends RuleConfig> {
    // 边界 providers(供边界驱动)
    BoundaryProvider cycleBoundaryProvider(LocalDateTime cycleOrigin);
    BoundaryProvider periodBoundaryProvider(C config);
    BoundaryProvider unitAlignmentProvider(C config);

    // 价格函数(段构造 + SMART_FREE_MINUTES 分配用)
    BigDecimal priceAt(LocalDateTime time, C config);

    // 时段标签与封顶
    PeriodLabeler periodLabeler(C config);  // time → {label, periodCap}

    // 周期与封顶配置
    int cycleMinutes();                       // 默认 1440
    BigDecimal cycleCap(C config);
    int unitMinutes(C config);
    IncompleteUnitChargeMode incompleteMode(C config);
    Integer thresholdMinutes(C config);
    BigDecimal thresholdRatio(C config);

    // 周期切换判定(消除 applyCapAndAccumulate 的唯一差异点)
    boolean isCycleBoundary(HomogeneousSegment seg, LocalDateTime cycleOrigin);
}
```

各规则族实现自己的 `RuleSemantics`(DayNightSemantics / RelativeTimeSemantics / ...),封装自己的周期模型、时段结构、价格。

### 5.3 四个 ModeStrategy

每个策略接收 `(RuleSemantics, BillingContext, PromotionAggregate)`,内部复用 `BoundaryDrivenLoop`,产出对应结构:

| ModeStrategy | 产出 | 核心逻辑 | FREE_MINUTES |
|--------------|------|---------|--------------|
| ContinuousStrategy | BillingUnit | 边界驱动切断 + compact + 简化 + 不足单元 | 前置时段化(起点) |
| UnitBasedStrategy | BillingUnit | 固定单元对齐 + 完整覆盖才免费 | 前置时段化(起点) |
| DurationPeriodStrategy | DurationSegment | 周期边界 + 周期内累计封顶 | 前置时段化(起点) |
| DurationGlobalStrategy | DurationSegment | 同质收费桶汇总 + 完整周期/尾周期分别封顶 + SMART_FREE_MINUTES | 前置时段化(起点)+ SMART_FREE_MINUTES 规则侧分配 |

`ContinuousStrategy` 持有**唯一一份** `applyCapAndAccumulate`(通用),周期切换通过 `RuleSemantics.isCycleBoundary` 注入,periodCap 通过 `periodLabeler` 注入(CompositeTime 提供,其余返回 null)。4 份重复消除为 1 份。

`DurationPeriodStrategy` / `DurationGlobalStrategy` 共享 `DurationSupport` 工具(`segmentCharge` / `segmentOriginalCharge` / `DurationResult` / `PeriodResolver` 接口),各自实现封顶数学。

### 5.4 CalculationMode 合并(决策 A)

删除 `BillingMode` + `DurationMode`,合并为单一枚举:

```java
enum CalculationMode {
    CONTINUOUS, UNIT_BASED, DURATION_PERIOD, DURATION_GLOBAL
}
```

去掉 `NONE`:合并后 CONTINUOUS/UNIT_BASED 就是非时长模式,不需要"不使用"标记。四种计算模式平级。

影响:`resolveBillingMode` + `resolveDurationMode` → `resolveCalculationMode`(一次解析);`supportedModes` + `supportedDurationModes` → `supportedCalculationModes`(一个声明);门面 `calculate` 一个 `switch(mode)`;`BillingService` 消除"白解析"。

### 5.5 SMART_FREE_MINUTES(决策 D)

新增优惠类型 `SMART_FREE_MINUTES`,与 `FREE_MINUTES` 并列:

- **聚合层**:`SMART_FREE_MINUTES` 作为标量透传,不时段化
- **FreeMinuteAllocator**:只处理 `FREE_MINUTES`(从窗口起点),零改动
- **DurationGlobalStrategy 消费**:用 `RuleSemantics.priceAt` 知各时段单价,按优先高价分配 `SMART_FREE_MINUTES` 到高价时段,产出免费段
- **非 GLOBAL 模式**:遇到 `SMART_FREE_MINUTES` 报错(与 `BillingCalculator` 现有"不支持即抛异常"语义一致)
- **同时存在**:普通 `FREE_MINUTES` + `SMART_FREE_MINUTES` 按 `priority` 排序,各自分配,跳过已占用时段

`SMART_FREE_MINUTES` 的分配逻辑(在 `DurationGlobalStrategy` 内):

```
1. 用 periodBoundaryProvider + priceAt 把窗口切成同价时段
2. 按单价降序排序时段
3. 从高价时段消费 SMART_FREE_MINUTES,产出免费段(从时段起点切)
4. 与普通免费段(FREE_RANGE + FREE_MINUTES 时段化)合并,参与边界驱动
```

复杂度锁定在 GLOBAL 模式内,符合"增强功能复杂度不超过主功能比例"原则。

### 5.6 简化计算:全局空隙实现(决策 C)

保留简化,改"全局视角算无优惠空隙"实现,保留原有穿插简化语义,删旧切段模型依赖:

```
1. 前置时段化 → freeTimeRanges(已合并排序)
2. 全局算无优惠空隙:
   gaps = []
   cursor = calcBegin
   for r in freeTimeRanges:
     if r.beginTime > cursor: gaps.append([cursor, r.beginTime])
     cursor = max(cursor, r.endTime)
   if cursor < calcEnd: gaps.append([cursor, calcEnd])
3. 每个 gap 对齐周期边界,算覆盖周期数
4. gap 周期数 > 阈值 → 简化单元(min(总应收, cycleCap × 周期数))
   否则 → 正常生成
5. 优惠段所在周期 → 正常生成明细
```

简化单元 `ruleData` 记时段金额(`dayAmount`/`nightAmount`/`cycleCount`),保留可追溯性。

收益:
- 行为不变(穿插简化语义),现有测试无需改预期
- 无状态机(从"逐周期累计 + 中途结算"变成"一次性算空隙 + 逐空隙判断")
- 信息源正确(无优惠空隙由 freeTimeRanges 直接定义)
- 删 `findCyclesWithPromotion` / `organizeByCycle` / `splitTimeAxis` / `TimeFragment` / `CycleFragments`(旧切段模型,简化是唯一用户)

### 5.7 共享解析逻辑(决策 E)

抽取 `resolveSegmentContext`,让 `calculate` 与 `prepareContexts` 共用:

```java
SegmentContext resolveSegmentContext(
    BillingRequest request, BillingSegment segment,
    CalculationWindow window, ExternalPromotionPool externalPool);
```

`calculate` 和 `prepareContexts` 都调它,消除不同步(calculationMode、externalPool、未来新增解析项)。`PromotionEquivalentCalculator` 的消去法基于与 `calculate` 完全一致的解析,等效金额准确。

`externalPool` 在分步消取法下的状态重置(`prepareContexts` 一次、`calculateWithContexts` 多次重算)留 plan 细化。

## 6. 模式特性矩阵(更新)

| 特性 | CONTINUOUS | UNIT_BASED | DURATION_PERIOD | DURATION_GLOBAL |
|------|-----------|-----------|-----------------|-----------------|
| 产出结构 | BillingUnit | BillingUnit | DurationSegment | DurationSegment |
| 切分模型 | 边界驱动切断 | 固定单元对齐 | 边界驱动分钟流 | 边界驱动分钟流 |
| 公共调度层 | 用 | 不用 | 用 | 用 |
| FREE_MINUTES 处理 | 前置时段化(起点) | 前置时段化(起点) | 前置时段化(起点) | 前置时段化(起点)+ SMART_FREE_MINUTES |
| SMART_FREE_MINUTES | 报错 | 报错 | 报错 | 规则侧优先高价分配 |
| compact 合并 | 有 | 无 | 无 | 无 |
| 简化计算 | 全局空隙 | 无 | 无 | 无 |
| 封顶基准 | 逐周期封顶 | 每日封顶 | 周期内封顶 | 完整周期封顶 + 尾周期实际费用封顶 |

## 7. 迁移顺序

重构分阶段,每阶段可独立验证:

**阶段 0:GLOBAL 时段化修复(独立先行,降风险)**
- `DurationGlobalStrategy`(当前 `DayNightDurationStrategy` 的 GLOBAL 路径)改走前置时段化,删 `deductFreeMinutesGlobal`
- 验收:金额不变 + DurationSegment 同质(收费汇总桶,`beginTime`/`endTime` 为空)
- 纠正 spec 3.3 表述
- 无论后续阶段是否进行,此项都有价值

**阶段 1:CalculationMode 合并(决策 A)**
- 删 `BillingMode`/`DurationMode`,加 `CalculationMode`
- `BillingConfigResolver` / `BillingContext` / `BillingRule` / `BillingCalculator` / `BillingService` 接口调整
- 各门面分派改 `switch(calculationMode)`

**阶段 2:3 规则族门面化**
- RelativeTime / NaturalTime / CompositeTime 照搬 DayNight 模式
- Rule 类变纯分派,CONTINUOUS 下沉到 ContinuousStrategy,不继承 `AbstractTimeBasedRule`
- 各自实现 `RuleSemantics`

**阶段 3:抽 ContinuousStrategy 通用骨架**
- 4 份 `applyCapAndAccumulate` 合并为 1 份,周期切换通过 `isCycleBoundary` 注入,periodCap 通过 `periodLabeler` 注入
- `generateSimplifiedUnitsForContinuous` 改全局空隙实现(决策 C)

**阶段 4:抽 DurationSupport + 拆两个时长策略(决策 B)**
- 提取 `DurationSupport`(`segmentCharge` / `DurationResult` / `PeriodResolver` 接口)
- `DayNightDurationStrategy` 拆为 `DurationPeriodStrategy` / `DurationGlobalStrategy`(通用,接收 RuleSemantics)
- `DurationGlobalStrategy` 输出同质收费汇总桶,并按完整周期与尾周期分别处理时段封顶/周期封顶
- dayNight 私有部分(`dayNightBoundary` + day/night 标签)归 `DayNightSemantics`

**阶段 5:SMART_FREE_MINUTES(决策 D)**
- 新增 `SMART_FREE_MINUTES` 类型
- `DurationGlobalStrategy` 实现优先高价分配(用 `RuleSemantics.priceAt`)
- 非 GLOBAL 报错

**阶段 6:共享解析逻辑(决策 E)**
- 抽 `resolveSegmentContext`,`calculate` / `prepareContexts` 共用
- `prepareContexts` 补 calculationMode + externalPool
- `PromotionEquivalentCalculator` 对齐

**阶段 7:废弃旧模型**
- 删 `TimeFragment` / `splitTimeAxis` / `organizeByCycle` / `CycleFragments`(简化改全局空隙后无依赖)
- 删 `AbstractTimeBasedRule`(职责拆解完毕:调度归层1,工具归 `RuleSupport`,CONTINUOUS 基类职责归 `ContinuousStrategy`)
- 删 `SimplifiedUnitMeta`(若简化单元改用 ruleData)

**阶段 8:文档同步**
- spec / plan / TODO / DONE
- README / README_CN / USER_GUIDE
- 能力文档(中英)/ 流程文档
- AGENTS.md(架构变更)

## 8. 阶段 0 详细设计(GLOBAL 时段化修复)

阶段 0 可独立先行,作为降风险前置。

### 8.1 改动

- `DayNightDurationStrategy.calculate` 的 GLOBAL 分支:改用前置时段化(复用 `materializeFreeMinutes`),删 `deductFreeMinutesGlobal` 与 `buildDurationSegmentsGlobalMode` 的分钟扣减路径
- GLOBAL 路径先物化免费段参与边界驱动,最终产出按同质收费桶汇总;免费段不落 `DurationSegment`,使用信息走 `PromotionUsage`

### 8.2 验收

- 现有 GLOBAL 模式测试金额不变
- DurationSegment 同质:每个 GLOBAL 汇总桶代表相同 periodKey/periodLabel/unitPrice/unitMinutes 的收费分钟集合,`beginTime` / `endTime` 为空
- 明细:免费段不再"揉进"收费桶的收费分钟,但也不作为时间轴段落盘;优惠原因与使用分钟由 `PromotionUsage` 追踪

### 8.3 文档

- 纠正 [2026-07-02 spec 3.3](./2026-07-02-duration-rule-and-promotion-two-tier-design.md) "GLOBAL 无需时间位置"为"GLOBAL 封顶无需时间位置,但明细产出需时段化保证段同质"
- 新增 TODO item 记录此修复

## 9. 待下一阶段决策(留 plan 细化)

1. **externalPool 分步重算语义**:`prepareContexts` 一次、`calculateWithContexts` 多次重算时,pool 如何重置
2. **简化单元 ruleData 结构**:`dayAmount`/`nightAmount`/`cycleCount` 的具体字段与序列化
3. **RuleSemantics 与 config 的关系**:语义方法直接读 config,还是规则族在构造时固化
4. **DurationSupport 工具形态**:静态工具类还是注入实例
5. **不足单元配置位置**:已于 2026-07-15 定案为 config 直读；新增 `IncompleteUnitChargeSpec` 统一承载 mode 与阈值，`RuleSemantics` 继续通过 `RuleConfig` 默认 getter 读取。

## 10. 相关文档与 TODO

- [2026-07-02-duration-rule-and-promotion-two-tier-design.md](./2026-07-02-duration-rule-and-promotion-two-tier-design.md) — 前置 spec(本 spec 3.3 纠正其 GLOBAL 论述,5.1 扩展其分层)
- [docs/billing-engine-calculation-flow-zh.md](../billing-engine-calculation-flow-zh.md) — 计算流程(重构后同步)
- [docs/billing-engine-capabilities-zh.md](../billing-engine-capabilities-zh.md) — 能力文档(重构后同步)
- 待新增 TODO item:
  - 阶段 0:GLOBAL 时段化修复
  - 主体重构:规则抽象重构(本 spec)
