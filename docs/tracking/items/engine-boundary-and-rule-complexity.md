# 收敛核心引擎职责边界与规则复杂度

---
id: TODO-20260514-007
type: feature
priority: P2
status: done
source_git: 972235c
created_at: 2026-05-14
completed_at: 2026-05-18
completed_git:
---

## 背景

本轮对 `billing-engine` 现有代码进行整体能力地图分析后，发现当前实现虽然仍然保持了 `core` 纯计算的主原则，但已经出现几类明显的可优化问题：

1. `billing-api` 已开始直接解析规则私有 `ruleData`，例如通过 `isSimplified` 判断是否触发精确重算，说明查询层对规则内部协议产生了耦合。
2. `DayNightRule`、`RelativeTimeRule`、`CompositeTimeRule` 都已经演化为超大类，同时承担计费模式分支、CONTINUE、简化计算、封顶、查询投影等多重职责，后续继续迭代的成本和误改风险都在上升。
3. simplification 相关元数据目前主要通过 `ruleData: Map<String, Object>` 在 `core` 与 `billing-api` 之间传播，公共契约不够清晰。
4. `bill-test` 中累积了较多示例、生成器和调试代码，若后续设计判断过度依赖这些测试工具而不是正式 API 契约，容易反向污染引擎设计。

这些问题目前还没有直接破坏功能，但已经偏离“简单优先、高级特性隔离、查询层不解析规则私有语义”的设计方向，值得在后续优化中集中处理。

## 目标

在不破坏现有能力的前提下，逐步收敛 `billing-engine` 的模块职责边界，降低规则实现复杂度，并把 simplification / 查询层相关协议提升为更稳定的公共设计。

## 范围

包含：

- 评估并收敛 `billing-api` 对 `ruleData` 的直接解析行为。
- 评估 `DayNightRule`、`RelativeTimeRule`、`CompositeTimeRule` 的内部职责切分方式。
- 评估 simplification 元数据是否应从松散 `Map` 协议提升为明确模型。
- 明确 `bill-test` 中哪些代码属于测试工具，哪些能力应回到正式模块或文档。

不包含：

- 停车业务专属语义接入。
- 非 `billing-engine` 仓库内的调用方改造。
- 与当前分析结论无关的规则能力扩展。

## 验收标准

- 能明确列出当前哪些对象或字段属于“公共契约”，哪些仅应停留在规则内部。
- 至少形成一版面向后续重构的拆分方案，说明规则大类该如何按职责切分。
- 对 simplification / 查询层交互给出清晰的边界方案，避免 `billing-api` 继续理解规则私有 `ruleData`。
- 对 `bill-test` 的定位给出清晰结论，避免测试工具反向影响核心引擎设计。

## 相关文件

- `core/src/main/java/cn/shang/charging/charge/rules/AbstractTimeBasedRule.java`
- `core/src/main/java/cn/shang/charging/charge/rules/daynight/DayNightRule.java`
- `core/src/main/java/cn/shang/charging/charge/rules/relativetime/RelativeTimeRule.java`
- `core/src/main/java/cn/shang/charging/charge/rules/compositetime/CompositeTimeRule.java`
- `billing-api/src/main/java/cn/shang/charging/wrapper/BillingTemplate.java`
- `billing-api/src/main/java/cn/shang/charging/wrapper/BillingResultViewer.java`
- `docs/billing-engine-capabilities-zh.md`

## 备注

本事项来源于 2026-05-14 的整体能力地图分析，不针对某一个具体 bug，而是记录当前引擎结构层面的优化方向。

### 当前阶段进展（2026-05-15）

已完成：

- 已形成 `billing-engine` 的能力地图与路线图，明确该事项应优先于其他功能扩展执行。
- 已引入 `SimplifiedUnitMeta`，将 simplification 元数据收敛为显式公共契约。
- `billing-api` 中 `BillingTemplate` 已改为通过 `SimplifiedUnitMeta` 判断是否命中简化单元，不再直接解析 `ruleData["isSimplified"]`。
- `bill-test` 中与 simplification 相关的公共 helper 已改为通过 `SimplifiedUnitMeta` 判断简化单元。
- `DayNightRule` 已完成第一阶段结构拆分：
  - 模式分发拆分为 `DayNightContinuousCalculator` 与 `DayNightUnitBasedCalculator`
  - 判价逻辑拆分为 `DayNightPriceResolver`
  - 查询投影拆分为 `DayNightValueSpecFactory`
  - 周期状态与封顶处理拆分为 `DayNightCycleStateManager`
- `RelativeTimeRule` 已完成第一阶段模式拆分：
  - `RelativeTimeContinuousCalculator`
  - `RelativeTimeUnitBasedCalculator`
- `RelativeTimeRule` 已开始第二阶段职责拆分：
  - `RelativeTimePeriodResolver`
  - `RelativeTimeContinuousCapHandler`
  - `RelativeTimeSimplifiedCycleStateManager`
- `CompositeTimeRule` 已完成第一阶段模式拆分：
  - `CompositeTimeContinuousCalculator`
  - `CompositeTimeUnitBasedCalculator`
- `CompositeTimeRule` 已开始第二阶段职责拆分：
  - `CompositeTimePeriodResolver`
  - `CompositeTimeCrossPeriodPriceResolver`
  - `CompositeTimeContinuousCapHandler`
  - `CompositeTimeSimplifiedCycleStateManager`

### 2026-05-18 进展：CompositeTimeRule 状态收敛完成

已完成：

- 删除 `CompositeTimeRule` 中残留的 `applyCapForSimplified`、`getSimplifiedCycleIndex` 未使用方法
- 删除 `calculateWithSimplification` 中重复的状态更新代码（手动更新 + manager 调用）
- 重构 `calculateContinuousInternal` 非简化模式状态更新，改用 `updateStateAfterPlainContinuous`
- 重构 `calculateContinuousInternal` 简化模式状态更新，改用 `updateStateAfterContinuousSimplified`
- `CompositeTimeSimplifiedCycleStateManager` 已成为 `CompositeTimeRule` 中唯一的状态更新入口
- 回归测试全部通过（CompositeTimeSmokeTest, BillingResultViewerTest）

已验证：

- 上述结构性拆分均使用同一组工具型测试做了修改前后结果对比。
- `DayNightParkingParityTest`、`RelativeTimeParkingParityTest`、`BillingResultViewerTest`、`DayNightQueryValueTest`、`SimplifiedUnitMetaTest`、`EngineBoundarySmokeTest` 当前均可通过。

尚未完成：

### 2026-05-18 公共抽象提炼阶段总结

**本阶段工作已完成。**

已完成：

1. **公共契约明确化**
   - `SimplifiedUnitMeta` 已成为 `core`、`billing-api`、`bill-test` 之间唯一的简化单元元数据契约
   - `billing-api` 不再直接解析 `ruleData["isSimplified"]`
   - 公共契约清单：`SimplifiedUnitMeta`（简化单元）、`AbstractContinuousCapHandler`（连续模式封顶）、`SimplifiedCycleStateHelper`（周期状态工具方法）

2. **三个规则类拆分完成**
   - `DayNightRule`：模式分发 + 价格解析 + valueSpec 工厂 + 周期状态管理
   - `RelativeTimeRule`：模式分发 + period 定位 + 连续模式封顶 + 周期状态管理
   - `CompositeTimeRule`：模式分发 + period 定位 + 跨时段定价 + 连续模式封顶 + 周期状态管理
   - 所有残留状态更新代码已收敛到各自的 CycleStateManager

3. **公共抽象提炼**
   - `AbstractContinuousCapHandler`：连续模式封顶基类（消除约100行重复代码）
   - `SimplifiedCycleStateHelper`：周期状态工具类（extractCycleIndex、getSimplifiedCycleIndex、applyCapWithCarryOverForSimplified）
   - 命名模板文档化：`updateStateAfterUnitBasedSimplified`、`updateStateAfterPlainUnitBased` 等

4. **拆分经验沉淀**
   - 第一刀按 CONTINUOUS / UNIT_BASED 拆分
   - 第二刀抽本规则内部稳定职责
   - 第三刀提炼跨规则公共抽象
   - 不强求接口统一，优先命名模板

待后续迭代：

- `bill-test` 中工具层与正式能力边界的文档说明（低优先级）
- 是否需要强制定义 CycleStateManager 接口（当前命名模板已足够）

### 当前已验证的拆分经验

本轮优化已经验证出以下方法在当前引擎中可行：

1. 规则类第一刀优先按 `CONTINUOUS` / `UNIT_BASED` 拆分。
2. 第二刀只抽“本规则内部已经稳定”的职责，如：
   - `dayNight`：价格解析、`valueSpec` 工厂、周期状态管理
   - `relativeTime`：period 定位、连续模式封顶
3. 不要求不同规则在同一轮拆分后结构完全一致。
4. 只有当多个规则都被拆清楚后，再统一分析哪些逻辑值得上升为公共抽象。

这意味着后续处理 `CompositeTimeRule` 时，应优先先把它自身拆清楚，而不是先为所有规则设计一套统一框架。

### 当前统一抽象候选分析

基于 `DayNightRule`、`RelativeTimeRule`、`CompositeTimeRule` 当前的拆分结果，可以先得出以下横向结论：

#### 已验证可统一的模式

1. **规则类第一层统一模式分发**
   - 三个超大规则类都已经验证：第一刀先拆成 `CONTINUOUS` / `UNIT_BASED` 两个入口是成立的。
   - 这层抽象稳定、低风险，而且不依赖某个规则的私有算法。

2. **规则内部稳定职责可以先各自提炼**
   - `dayNight` 已提炼出：
     - 价格解析器
     - `valueSpec` 工厂
     - 周期状态管理器
   - `relativeTime` 已提炼出：
     - period 定位器
     - 连续模式封顶处理器
     - simplification 周期状态处理器
   - `compositeTime` 已提炼出：
     - 模式分发入口
     - period / natural period 定位器
   - 说明第二层拆分应继续沿“本规则内部稳定职责”推进，而不是先强求跨规则一致。

3. **simplification 公共契约已具备继续推广条件**
   - `SimplifiedUnitMeta` 已经可以作为 `core`、`billing-api`、`bill-test` 之间的稳定交互模型。
   - 后续如果 `CompositeTimeRule` 也需要消费简化单元元数据，应优先沿用该模型，而不是再发明新的 `ruleData` 约定。

#### 当前不适合立即抽公共的部分

1. **价格解析器**
   - `dayNight` 的核心是 `DAY/NIGHT/MIXED + blockWeight`。
   - `relativeTime` 的核心是 period 定位与周期封顶。
   - `compositeTime` 则同时叠加自然时段定价、跨时段模式与周期语义。
   - 因此当前还不适合抽统一 `PriceResolver` 公共层。

2. **连续模式封顶处理**
   - `dayNight` 和 `relativeTime` 都有连续模式封顶，但单元合并、截断处理和状态推进方式并不完全一致。
   - 在 `CompositeTimeRule` 完成第二层职责拆分前，不宜直接合并为单一公共 `ContinuousCapHandler`。

3. **周期状态管理**
   - `dayNight` 与 `relativeTime` 都有 24h 周期，但前者更偏日夜混合单元与封顶，后者更偏 period + simplification + cap。
   - `compositeTime` 还叠加了时间段独立封顶和自然时段价格变化。
   - 当前适合保留各自的 `CycleStateManager`，等 `CompositeTimeRule` 也拆清楚后再统一分析。

#### 当前最合理的后续策略

- 当前已经形成较稳定的拆分模板，可以先做一次总收口与模板沉淀，再决定是否继续深拆 `CompositeTimeRule`。
- 然后统一评估以下候选公共抽象：
  - 周期状态管理接口
  - simplification 状态推进接口
  - 连续模式封顶处理接口
  - 规则类统一拆分模板
- 为了让 `CompositeTimeRule` 也进入稳定回归路径，已补充 `CompositeTimeSmokeTest`，固定以下场景：
  - `UNIT_BASED` 基本计算
  - `HIGHER_PRICE` 跨自然时段定价
  - `CONTINUOUS` 模式周期封顶

当前不建议直接抽统一实现，而应先抽”接口/模板级共性”，避免把某一个复杂规则的特殊性误提升为所有规则的通用逻辑。

### 2026-05-18 三个规则类拆分结果横向分析

#### 子组件结构对比表

| 组件类型 | DayNightRule | RelativeTimeRule | CompositeTimeRule |
|----------|--------------|------------------|-------------------|
| 模式分发 | DayNightContinuousCalculator, DayNightUnitBasedCalculator | RelativeTimeContinuousCalculator, RelativeTimeUnitBasedCalculator | CompositeTimeContinuousCalculator, CompositeTimeUnitBasedCalculator |
| 状态管理 | DayNightCycleStateManager | RelativeTimeSimplifiedCycleStateManager | CompositeTimeSimplifiedCycleStateManager |
| 封顶处理 | (合并在 CycleStateManager) | RelativeTimeContinuousCapHandler | CompositeTimeContinuousCapHandler |
| 价格解析 | DayNightPriceResolver | (内联在规则类) | CompositeTimeCrossPeriodPriceResolver |
| 周期解析 | (内联在规则类) | RelativeTimePeriodResolver | CompositeTimePeriodResolver |
| 查询投影 | DayNightValueSpecFactory | (无) | (无) |

#### 方法签名对比

**ContinuousCapHandler.applyWithCarryOver**（RelativeTime 与 CompositeTime 近乎一致）：
```java
// RelativeTimeContinuousCapHandler
BigDecimal apply(List<BillingUnit> units, BigDecimal maxCharge, BigDecimal carryOverAccumulated)

// CompositeTimeContinuousCapHandler
BigDecimal applyWithCarryOver(List<BillingUnit> units, BigDecimal maxCharge, BigDecimal carryOverAccumulated)
```
核心逻辑 90%+ 相同：累计判断、封顶截断、合并免费单元。

**SimplifiedCycleStateManager 方法对比**：

| 方法 | RelativeTime | CompositeTime | 相似度 |
|------|--------------|---------------|--------|
| extractCycleIndex | ✓ | ✓ | 100%（相同实现） |
| getSimplifiedCycleIndex | ✓ | ✓ | 100%（相同实现） |
| applyCapWithCarryOverForSimplified | ✓ | ✓ | 95%（仅 config 类型不同） |
| updateStateAfterUnitBasedSimplified | (无) | ✓ | - |
| updateStateAfterPlainUnitBased | (无) | ✓ | - |
| updateStateAfterContinuousSimplified | ✓ | ✓ | 90%（参数略有差异） |
| updateStateAfterPlainContinuous | (无) | ✓ | - |

#### 可提炼公共抽象清单

**高优先级（代码高度相似）**：

1. **ContinuousCapHandler 公共实现**
   - `RelativeTimeContinuousCapHandler.apply` 与 `CompositeTimeContinuousCapHandler.applyWithCarryOver` 逻辑几乎完全相同
   - 可提炼为 `BaseContinuousCapHandler` 或直接合并为单一实现
   - 建议方法签名：`BigDecimal applyWithCarryOver(List<BillingUnit> units, BigDecimal maxCharge, BigDecimal carryOverAccumulated)`

2. **SimplifiedCycleStateManager 公共方法**
   - `extractCycleIndex` 和 `getSimplifiedCycleIndex` 可提炼为静态工具方法或公共基类方法
   - `applyCapWithCarryOverForSimplified` 核心逻辑相同，仅 config 类型不同，可用泛型或函数参数解耦

**中优先级（接口级抽象）**：

3. **CycleStateManager 接口**
   - 定义状态更新契约：`updateStateAfterUnitBased`, `updateStateAfterContinuous`, `updateStateAfterSimplified`
   - 各规则实现具体逻辑，接口统一命名

4. **Calculator 模式模板**
   - `ContinuousCalculator` 和 `UnitBasedCalculator` 命名模式已稳定
   - 可文档化为规则拆分模板，暂不强制抽象为类

**低优先级（暂不处理）**：

5. **价格解析器**
   - 各规则算法差异大（日夜/相对时段/自然时段），不适合统一

6. **周期解析器**
   - RelativeTime 和 CompositeTime 逻辑不同，保持独立

#### 不建议立即统一的部分

| 部分 | 原因 |
|------|------|
| PriceResolver | DayNight 日夜判定、CompositeTime 自然时段定价，算法差异显著 |
| PeriodResolver | 各规则周期定义不同 |
| ValueSpecFactory | 仅 DayNight 需要，其他规则未用 |
| 状态管理完整实现 | 各规则状态字段含义不同 |

#### 建议下一步行动

1. **提炼 ContinuousCapHandler 公共实现**
   - 将 `RelativeTimeContinuousCapHandler` 与 `CompositeTimeContinuousCapHandler` 合并
   - 或提炼为公共基类 `AbstractContinuousCapHandler`

2. **提炼 SimplifiedCycleStateManager 公共方法**
   - `extractCycleIndex`、`getSimplifiedCycleIndex` 提升为工具类或基类
   - `applyCapWithCarryOverForSimplified` 抽象为泛型方法

3. **定义 CycleStateManager 接口**
   - 统一状态更新方法命名
   - 为后续规则扩展提供契约

### 2026-05-18 公共抽象提炼完成

已完成：

1. **提炼 ContinuousCapHandler 公共实现**
   - 创建 `AbstractContinuousCapHandler` 基类（[AbstractContinuousCapHandler.java](billing/core/src/main/java/cn/shang/charging/charge/rules/AbstractContinuousCapHandler.java)）
   - `RelativeTimeContinuousCapHandler` 和 `CompositeTimeContinuousCapHandler` 继承基类
   - 核心封顶逻辑统一：累计判断、封顶截断、合并免费单元
   - 回归测试全部通过（48 tests, 0 failures）

2. **提炼 SimplifiedCycleStateManager 公共方法**
   - 创建 `SimplifiedCycleStateHelper` 工具类（[SimplifiedCycleStateHelper.java](billing/core/src/main/java/cn/shang/charging/charge/rules/SimplifiedCycleStateHelper.java)）
   - `extractCycleIndex` 和 `getSimplifiedCycleIndex` 统一实现
   - 两个 CycleStateManager 改为委托给工具类
   - 统一使用 `SimplifiedUnitMeta.from(unit)` 公共契约

3. **CycleStateManager 命名模板文档化**
   - 不强制定义接口（各规则参数差异较大）
   - 通过命名模板统一方法命名规范：
     - `updateStateAfterUnitBasedSimplified`
     - `updateStateAfterPlainUnitBased`
     - `updateStateAfterContinuousSimplified`
     - `updateStateAfterPlainContinuous`

已验证：

- 所有回归测试通过（48 tests, 0 failures）
- 计费结果与修改前一致
- 无新增编译警告

4. **提炼 applyCapWithCarryOverForSimplified 公共逻辑**
   - 将核心封顶逻辑（按周期分组、比例削减、置零处理）提炼到 `SimplifiedCycleStateHelper.applyCapWithCarryOverForSimplified`
   - RelativeTime 和 CompositeTime 的 CycleStateManager 改为委托调用
   - 统一使用 `SimplifiedUnitMeta` 公共契约读取简化单元元数据
   - 删除各规则类中的重复实现（约 50 行代码）
