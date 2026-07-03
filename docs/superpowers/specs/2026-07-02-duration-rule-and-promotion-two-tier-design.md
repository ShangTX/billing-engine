# 计费引擎架构演进设计：规则门面策略结构与优惠两级模型

**状态**: 设计中
**日期**: 2026-07-02
**关联**: TODO-20260630-003（时长模式）、TODO-20260701-001（FREE_RANGE PromotionUsage）、TODO-20260701-002（previousAccumulatedAmount 清理）、TODO-20260702-001（GLOBAL_ORIGIN 半成品）、`docs/designs/segment-promotion-consistency.md`、`docs/billing-engine-calculation-flow-zh.md`

---

## 1. 背景

时长计费模式（TODO-20260630-003）以"规则内模式"形式实现后，暴露三个架构问题：

1. **规则臃肿**：`DayNightRule`（976 行）同时承载 CONTINUOUS 和 Duration 两条计算路径，两者共享边界驱动循环但几乎不共享其他逻辑（产出结构、封顶语义、valueSpec、compact、简化、CONTINUE、条件免费、不足单元处理全不同）。`AbstractTimeBasedRule`（1023 行）背着 265 行 Duration 产出基础设施，4 个 CONTINUOUS 规则中 3 个从不使用。

2. **优惠全局一致性缺失**：外部优惠（优惠券等）每段独立 `PromotionEngine.evaluate`，FREE_MINUTES/FREE_RANGE 在多分段下重复使用。`segment-promotion-consistency.md` 曾论述"GLOBAL_ORIGIN 减法"解决，但代码核实 GLOBAL_ORIGIN 是半成品（`clipBegin/clipEnd` 从未被读取，多分段双重计费，见 TODO-20260702-001）。

3. **FREE_MINUTES 物化绑架所有规则**：`FreeMinuteAllocator` 集中把 FREE_MINUTES 物化为时间段，服务单元计费的"完整覆盖才免费"判定。时长计费按分钟计费，不需要物化，却被迫走物化路径。

本 spec 给出三个架构演进方向，作为后续功能开发的指导。仅描述架构层面，不涉及参数传递等实现细节。

---

## 2. 设计目标

- 计费规则采用"门面 + 策略"结构：一个规则族一个 type、一个门面、一个共享 config，按模式分派到独立策略实现，消除规则臃肿。
- 外部优惠在多分段下全局一致，不依赖 GLOBAL_ORIGIN 减法。
- FREE_MINUTES 的表示形式按消费方规则类型区分，物化不再是聚合的固有职责。
- CONTINUE 续算限定为单元计费类（CONTINUOUS/UNIT_BASED），时长计费类摆脱 carryOver 机制影响。

---

## 3. 设计方向

### 3.1 规则门面策略结构与模式分派

**决策**：每个计费规则族（如 DayNight）一个 `ChargeRuleType`、一个规则实现（门面）、一个共享 config。门面按模式分派到独立策略实现，自身只分派不扛逻辑。模式分两个维度：`BillingMode`（CONTINUOUS/UNIT_BASED，单元计费类）和 `DurationMode`（PERIOD/GLOBAL，时长计费类），对称声明，互不交叉。

**统一入口**：DayNight 规则族一个 type=`dayNight`，`DayNightRule` 作门面 `implements BillingRule<DayNightConfig>`，声明 `supportedModes()={CONTINUOUS, UNIT_BASED}` 与 `supportedDurationModes()={PERIOD, GLOBAL}`。门面 `calculate` 按请求指定的模式分派到对应策略。这修复了 UNIT_BASED 与 CONTINUOUS 无法共存的现有注册缺口——一个 type 一个实现支持多模式，不需覆盖注册。

**二级分类**：

| 计费类 | 模式 | 产出 | 切分模型 |
|---|---|---|---|
| 单元计费类 | CONTINUOUS / UNIT_BASED | `BillingUnit` | 边界驱动切断 / 固定单元对齐 |
| 时长计费类 | PERIOD / GLOBAL | `DurationSegment` | 边界驱动分钟流 |

两类产出结构、切分模型、封顶语义、优惠消费都不同（见 3.3），各自独立策略实现。

**策略实现（代码独立）**：
- 单元计费类：CONTINUOUS 策略、UNIT_BASED 策略。两者切分模型根本不同，共享少，各自独立策略类。
- 时长计费类：PERIOD 策略、GLOBAL 策略。两者同切分模型，仅封顶数学不同（周期内 vs 全局×周期数），方法级分离即可，放同一时长策略类内两个 build 函数。

**模式维度对称**：保留 `BillingMode` + `DurationMode` 两个枚举，对称声明。`supportedModes()` 管 CONTINUOUS/UNIT_BASED，`supportedDurationModes()` 管 PERIOD/GLOBAL。两个维度互不交叉：DurationMode≠NONE 时走时长策略，否则按 BillingMode 走单元策略，天然互斥，不需额外约束。单元计费规则只用前者，时长规则只用后者，DayNight 规则族两者都用（声明都支持）。

**所有模式一视同仁**：没有模式是"基础/必须"的，模式都是规则自声明能力，规则至少支持一种，不强制特定模式。其他规则（RelativeTime 等）按需声明，可只支持 `{CONTINUOUS}`。

**循环原语复用**：边界驱动循环（`runBoundaryDrivenLoop` + `BoundaryProviders` + `HomogeneousSegment`）是纯调度层，零计费语义，单元策略和时长策略共享。该层从 `AbstractTimeBasedRule` 提取为独立工具，策略类调用工具，不通过继承。

**分层结构**：

```
层1（纯调度，0 计费语义）:
  BoundaryProvider / BoundaryProviders / HomogeneousSegment / runBoundaryDrivenLoop
  单元策略和时长策略都用

层2（时长计费类专属产出）:
  PeriodResolver / DurationResult / buildDurationSegments*
  只时长策略用，搬出 AbstractTimeBasedRule

层3（单元计费类专属产出）:
  RuleState / 简化 / CONTINUE / TimeFragment / applyCapAndAccumulate / 不足单元 / valueSpec
  只单元策略用，留在 AbstractTimeBasedRule（CONTINUOUS）/ UNIT_BASED 策略类
```

层2 与层3 是平级、互不依赖的两套产出逻辑，都建在层1 之上。`AbstractTimeBasedRule` 重新内聚为"CONTINUOUS 策略基类"。

**CONTINUE 限定**：CONTINUE 续算只在单元计费类（CONTINUOUS/UNIT_BASED）有意义。时长计费类不参与 CONTINUE，不背 carryOver 机制。`previousAccumulatedAmount` 跨段传递限定为 CONTINUE 场景（TODO-20260701-002），纯分段不传。

### 3.2 优惠两级模型

**决策**：优惠按来源分两级处理：

- **方案内优惠**（`promotionRules` 来源，跟方案走）：每段独立，分段1 用方案A 的优惠、分段2 用方案B 的优惠。本就该每段独立，不存在重复使用问题。
- **外部优惠**（`externalPromotions` 来源，跟请求走）：全局一致，整笔停车享一次。通过跨段共享的"可用量池"显式扣减实现，不依赖 GLOBAL_ORIGIN 减法。

**段内聚合**：每段 `PromotionEngine.evaluate` 时，剩余外部优惠 + 本段方案内优惠规则**按优先级聚合在一起**，产出本段最终免费段。外部优惠可能被方案内优惠覆盖而未使用。优惠有来源标识（`PromotionUsage.promotionId`），从本段结果按来源分辨实际使用量，回写扣减可用量池，下段拿到正确的剩余外部优惠。

**与减法方案的关系**：本设计取代 `segment-promotion-consistency.md` 论述的"GLOBAL_ORIGIN 减法保证外部优惠一致"。减法依赖 GLOBAL_ORIGIN（半成品，TODO-20260702-001），且只对 GLOBAL_ORIGIN 生效；两级模型在 SEGMENT_LOCAL 下也能保证外部优惠一致，更彻底。GLOBAL_ORIGIN 的窗口截取细节和业务意义留待下一阶段讨论。

**AMOUNT/DISCOUNT 不进核心计算**：只 FREE_MINUTES/FREE_RANGE 参与免费段切分与跨段扣减。AMOUNT/DISCOUNT 整笔一次性，由 `AmountDiscountApplier` 在最终结果上事后结算。现状已如此（分段级应用，主流程不自动调用，由 billing-api 显式触发）。

### 3.3 FREE_MINUTES 表示形式按消费方区分

**决策**：聚合产出规范中间形式（FREE_RANGE 为时段、FREE_MINUTES 为分钟数、AMOUNT/DISCOUNT 为标量），不集中物化。物化是消费者侧职责，按模式区分：

| 消费方 | FREE_MINUTES 处理 | 原因 |
|---|---|---|
| CONTINUOUS / UNIT_BASED | 转时间段 | "完整覆盖才免费"判定需要时间位置 |
| PERIOD | 转时间段 | 周期内时长计费，需定位免费段在周期/时段中的位置（逐周期封顶、周期内时段封顶都依赖时间位置） |
| GLOBAL | 按分钟扣减 chargedMinutes | 全局累加，封顶按周期数倍乘一次算，无需时间位置，按分钟扣减 |

FREE_RANGE 本就是时间区间，所有消费方都按时段消费。AMOUNT/DISCOUNT 是标量，事后结算。

**现状问题**：`FreeMinuteAllocator` 集中物化 FREE_MINUTES（`generatedFreeRanges`），混进 `freeTimeRanges` 给所有规则用。GLOBAL 时长策略被迫走物化路径，付不必要代价（CONTINUOUS/UNIT_BASED/PERIOD 需要物化，GLOBAL 不需要）。

**为什么必须下放（耦合论证）**：若物化留在聚合层，`PromotionEngine` 要按"规则 + 模式"决定产出形式（CONTINUOUS/UNIT_BASED/PERIOD 需物化、GLOBAL 不需），即规则与模式反向耦合进优惠聚合层。每新增一个规则族或模式，聚合层需加分支。物化下放到消费者侧后，聚合层只产出对所有规则一致的中间形式，不预知规则与模式，耦合消除。

**位置与职责**：物化下放是**职责调整**，不是**位置调整**。`PromotionEngine` 仍在 `BillingService` 编排层统一调用，位置不动，理由有三：

1. 外部优惠可用量池是跨段共享状态，回写扣减发生在段间，状态由编排层持有最自然。
2. 方案内优惠每段独立聚合，规则不该重复承担聚合逻辑。
3. `prepareContexts`/`calculateWithContexts` 的分离仍然成立：前者产出中间形式 aggregate，后者由规则消费 + 物化；等效金额的 `cloneAndExclude` 在中间形式上 exclude 仍有效（物化未发生，exclude 未物化的 FREE_MINUTES 更简单）。

职责重划：

| 原归属 | 职责 | 新归属 |
|---|---|---|
| `PromotionEngine` | FREE_RANGE 合并（`FreeTimeRangeMerger`） | 保留（优惠自身合并，与规则无关） |
| `PromotionEngine` | FREE_MINUTES 物化（`FreeMinuteAllocator`） | 下放给规则侧（CONTINUOUS/UNIT_BASED/PERIOD 策略承担，GLOBAL 策略不物化） |
| `PromotionEngine` | AMOUNT/DISCOUNT 汇总 | 保留（标量汇总，与规则无关） |

`PromotionEngine` 产出变为：合并后的 FREE_RANGE 时段 + 未物化的 FREE_MINUTES 列表 + AMOUNT/DISCOUNT 标量。`FreeMinuteAllocator` 从 `PromotionEngine` 解耦，成为规则侧工具。

**与 3.1 的关系**：时长计费类的 GLOBAL 策略有独立的优惠消费方式（按分钟扣减，不物化），PERIOD 策略仍需物化（周期内定位）。这是时长模式按模式区分优惠消费的结构体现，也是 GLOBAL 策略不被物化路径绑架的理由。

### 3.4 模式特性矩阵

各模式对核心特性的支持与需要情况，作为策略实现的参考：

| 特性 | CONTINUOUS | UNIT_BASED | PERIOD | GLOBAL |
|---|---|---|---|---|
| 产出结构 | BillingUnit | BillingUnit | DurationSegment | DurationSegment |
| 切分模型 | 边界驱动切断 | 固定单元对齐 | 边界驱动分钟流 | 边界驱动分钟流 |
| 公共调度层（BoundaryDrivenLoop） | 用 | 不用 | 用 | 用 |
| FREE_MINUTES 物化 | 需要 | 需要 | 需要 | 不需要 |
| valueSpec（查询投影） | 有 | 有 | 无 | 无 |
| compact 合并 | 有 | 无 | 无 | 无 |
| 简化计算 | 有 | 无 | 无 | 无 |
| CONTINUE 续算 | 支持 | 支持 | 不支持 | 不支持 |
| 封顶基准 | 逐周期封顶 | 每日封顶 | 周期内封顶 | 全局封顶 × 周期数 |
| 跨段累计（previousAccumulatedAmount） | 展示用 | 展示用 | 无 | 无 |

**读法**：
- 产出结构与切分模型决定策略属于单元计费类还是时长计费类（见 3.1 二级分类）。
- 公共调度层只有 UNIT_BASED 不用，其余三模式共享边界驱动循环。
- FREE_MINUTES 物化只有 GLOBAL 不需要（见 3.3），其余三模式需物化为时间段。
- valueSpec/compact/简化是单元计费类专属（CONTINUOUS 全有，UNIT_BASED 无 compact/简化因不走公共循环）；时长计费类不背这些特性。
- CONTINUE 续算只单元计费类支持，时长计费类不参与（见 3.1 CONTINUE 限定）。
- 封顶基准与跨段累计按模式不同：CONTINUOUS/UNIT_BASED 逐周期/每日封顶 + 展示用累计；PERIOD 周期内封顶；GLOBAL 全局封顶 × 周期数，无跨段累计。

---

## 4. 优惠等效金额的影响

**结论**：等效金额计算（消去法）的复杂度不受两级优惠模型影响。

**原因**：等效金额计算把引擎当黑盒——"排除优惠 X，给我新总费用"，N+1 次调用取差值。两级分拆是聚合输入侧的变化，对等效金额计算可见的输出侧（每段一个聚合好的 aggregate、最终一个 finalAmount）不变。段内聚合已按优先级合并两类优惠、处理重叠，产出的免费段不重叠、按来源可归属，消去法每次排除一个优惠、重算、取差，结果干净。

**机制支持**：消去法对"排除的是段内方案内优惠还是跨段外部优惠"不敏感，引擎重算时自然重走对应层级的聚合。当前代码不完全支持（FREE_RANGE 未产出 PromotionUsage、外部优惠跨段扣减未实现），是数据通路未打通，非消去法机制限制。通路补齐后天然支持。

**归属语义**（非复杂度问题）：消去法算出的等效金额，对方案内优惠是段内等效，对外部优惠是跨段等效。结果 Map 不区分来源层级，调用方按优惠来源解读。

---

## 5. 待下一阶段决策

以下为架构层面已识别但暂不决策的开放问题，留待下一阶段详细讨论：

1. **外部优惠状态载体**：跨段共享的可用量池以什么形式承载，与 CONTINUE 的 carryOver 如何正交分离（carryOver 专属 CONTINUE，分段专用外部优惠状态）。
2. **GLOBAL_ORIGIN 窗口截取**：减法/截取的实现细节与业务意义（周期封顶全局基准、前段假想费用占用额度等，见 `segment-promotion-consistency.md` 问题4）。
3. **时长计费类的 PromotionUsage 形式**：不物化 FREE_MINUTES 后，usage 按时段归属记分钟数还是记时间区间，影响等效金额计算的取用方式。
4. **AMOUNT/DISCOUNT 整笔语义**：是否整笔一次性不扣减的最终语义，分段级应用与整笔应用的关系。
5. **循环原语工具形态**：`runBoundaryDrivenLoop` 等提取为静态工具还是独立类注入。

---

## 6. 相关文档与 TODO

- `docs/billing-engine-calculation-flow-zh.md` — 期望计算流程（已体现本 spec 的架构方向）
- `docs/designs/segment-promotion-consistency.md` — 分段与优惠一致性讨论（减法方案，本 spec 3.2 取代其外部优惠一致性部分）
- `docs/billing-engine-capabilities-zh.md` / `.md` — 当前能力（本 spec 为演进方向，未实现）
- TODO-20260630-003 — 时长计费模式（已完成，本 spec 将其从规则内模式重构为门面下的时长策略）
- TODO-20260701-001 — FREE_RANGE 产出 PromotionUsage（3.2/3.3 的前置）
- TODO-20260701-002 — previousAccumulatedAmount 清理（3.1 CONTINUE 限定）
- TODO-20260702-001 — GLOBAL_ORIGIN 半成品（3.2 不依赖减法的原因）
