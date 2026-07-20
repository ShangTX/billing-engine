# 物化索引预估收入能力

---
id: TODO-20260630-002
type: feature
priority: P2
status: todo
source_git: ec62357
created_at: 2026-06-30
completed_at:
completed_git:
---

## 背景

项目最初愿景之一：停车场可查看当前整个停车场的当前预估收入。原设想是"预计算 + 时间索引"——把计费单元物化存储，按时间索引查询此时刻所有有效单元，总额求和，避免每辆车重算。

随着功能演进（valueSpec 单元内投影、conditional 免费段、compact 合并、CONTINUE 续算、截断处理策略），发现愿景的两个目标——**精确预估**与**免计算物化**——本质存在张力。经分析，物化索引预估并非"随开随用的简单开关"，而是**分情况、有条件**的能力。

## 愿景落地的条件矩阵（按特性集划分）

| 预估精度 | 特性集要求 | 物化内容 | 查询方式 | 可行性 |
|---------|-----------|---------|---------|--------|
| 粗略（≤1单元/段误差） | 任意 | accumulatedAmount + endTime | 索引最后单元求和 | ✓ 可物化 |
| 精确 | 无 conditional 免费段、无段内投影需求、FULL_CHARGE 单元模式 | chargedAmount + endTime | 索引求和 | ✓ 可物化 |
| 精确 | 无 conditional 免费段、无段内投影需求、时长模式 | 段有效分钟 × 单价 | 按段分组求和 | ✓ 可物化 |
| 精确 | 含 conditional 免费段 | — | 必须查询时算 | ✗ 不可物化 |
| 精确 | 要求段内投影 | — | 必须查询时算 | ✗ 不可物化 |

## 关键约束

1. **精确预估 ↔ 截断策略互斥（单元模式下）**：FULL_CHARGE 下截断单元 = 整单元金额，物化值精确。引入 PROPORTIONAL/FREE/THRESHOLD 后，截断单元金额依赖"实际时长/单元时长"比例，查询时点动态变化，物化固定金额不够。单元模式下精确预估只与 FULL_CHARGE 兼容。时长模式无截断概念，不受此约束。
2. **compact 数据不适合物化索引**：compact 合并 N 个子单元，queryTime 落在中间子单元时物化值无法直接用，需拆解 count。compact 服务"结果传输压缩"，非"按 queryTime 索引查询"。两者是正交优化，不应混用。
3. **物化预估可行性取决于特性集，而非计费模式**：经复盘，"时长模式是精确物化预估唯一路径"的结论不成立。时长模式只解决截断精度问题，不解决以下复杂度：
   - **多单价**（日夜/多时段）：物化需按段分组求和，不能全局 SUM
   - **免费段扣除**：段级或碎片级物化，各有代价
   - **conditional 免费段**：queryTime 相关，根本不能物化（queryTime 变了免费段可能消失，物化值失效）
   - **queryTime 落在段内投影**：需部分计算，非纯检索
   只要业务用到 conditional 免费段或要求段内精确投影，精确预估就必须查询时计算，任何模式都救不了。

## 目标

引擎**只提供实现的可能，不提供实现**。具体地：

- 引擎产出物化所需的原始数据（accumulatedAmount / chargedAmount / validMinutes）
- 引擎提供能力查询（告知当前配置支持哪种预估精度）
- 物化存储、时间索引、求和由业务层实现
- 未来若引擎内建物化存储，作为新模块提供，不污染 core 计算逻辑

## 范围

包含：

- **时长模式产出 validMinutes**：时长模式（待立 TODO）的 BillingUnit 可携带 validMinutes 字段，供物化索引使用（仅当业务特性集允许物化时才有意义）
- **能力查询接口**：`BillingConfigResolver` 或独立接口，告知当前配置（特性集：截断策略 + 计费模式 + 是否含 conditional 免费段 + 是否要求段内投影）支持的预估精度等级（ROUGH / PRECISE / UNSUPPORTED）
- **文档**：在能力文档中说明物化索引预估的条件矩阵，指导调用方按场景选择配置

不包含：

- 物化存储实现（业务层职责，或未来新模块）
- 时间索引实现
- 批量查询接口（业务层遍历在场车辆）

## 关键设计决策

1. **引擎与业务层边界**：引擎提供物化原始数据 + 能力查询，存储/索引/求和由业务层做。未来内建物化作为新模块，不污染 core。
2. **物化预估可行性取决于特性集**：时长模式只解决截断精度问题，不解决 conditional 免费段、多单价、段内投影等复杂度。物化预估的可行性由业务使用的特性集决定，而非计费模式。时长模式的高优先级来自其本职价值（精度一致性、适配 conditional 免费段），而非物化预估支撑。
3. **compact 与物化索引正交**：compact 服务传输压缩，物化索引服务查询预估，各自适用不同场景，不混用。
4. **能力查询暴露配置约束**：配了 PROPORTIONAL + 单元模式、或用了 conditional 免费段、或要求段内投影的调用方想用物化预估时，引擎应能告知"此配置不支持精确物化"，引导改用 FULL_CHARGE、时长模式或接受粗略预估。

## 开放讨论记录（2026-07-15）

### continue 的重新定位

不建议恢复旧 `CONTINUE` 计算模式。旧机制把截断单元重算、累计金额续算、周期封顶状态、优惠结转、规则状态和分段状态揉进全局 carryOver，导致 core 主流程和规则实现高度耦合，也削弱了当前架构的易用性。

如果未来仍需要“继续计算”，更适合放在 billing-api 或业务层，定义为“差额结算”而非 core 计算模式：

```text
currentTotal = calculate(entryTime, queryTime, stableRuleSnapshot)
delta = currentTotal - settledAmount
```

这要求调用方保存已结算金额、规则版本、优惠版本、方案快照和入场时间。规则或优惠发生变化时，应由业务明确选择“按旧版本续算”还是“按新版本重算并调整”。这属于结算账本问题，不应重新引入 `CalculationMode.CONTINUE`。

### 物化功能的核心难点

物化功能不应理解为“保存 BillingResult”或“保存 compact 后的 BillingUnit”。真正困难的是：查询时刻 `T` 的金额能否由预先落库的数据通过时间索引直接得到。

主要困难包括：

- **段内投影**：`queryTime` 落在计费单元内部时，FULL_CHARGE、PROPORTIONAL、THRESHOLD 等不足单元模式会产生不同金额曲线，固定 `chargedAmount` 不够表达。
- **conditional 免费段**：优惠是否生效依赖最终计费窗口，`queryTime` 改变会导致已物化数据失效。
- **价格感知 FREE_MINUTES**：`CHARGED_TIME` 默认按时间顺序填充非免费且单价大于 0 的收费时段，物化难度相对可控；但 `HIGHEST_PRICE` 时，随着查询窗口延长，未来高价段进入窗口后仍可能改变免费分钟分配位置。
- **封顶**：封顶会产生平台期、部分削减单元和周期重置，要求物化的是金额函数或原子段，而不是简单单价表。
- **compact**：compact 是结果传输压缩，不是索引结构。物化索引需要未 compact 的原子段或专门的 timeline atom。

### 可能的长期方向：ChargeTimeline

比起恢复 continue，更值得探索的是引入一个不面向展示、不 compact、不直接承担结算语义的可选中间产物：

```text
ChargeTimeline
  ChargeAtom:
    begin
    end
    valueType: STEP / LINEAR / CONSTANT
    amountBefore
    amountAfter
    slope
    capGroup
    promotionIds
    materializationPrecision
```

业务层可以基于 `ChargeTimeline` 自行建立时间索引、物化表和批量求和逻辑；core 仍保持纯计算和无存储副作用。

能力查询可进一步细分为：

| 等级 | 含义 | 典型场景 |
|------|------|----------|
| `ROUGH` | 粗略预估，允许一个单元或一个原子段误差 | 所有规则基本可提供 |
| `EXACT_STEP` | 金额只在边界跳变 | FULL_CHARGE、无段内投影、无 conditional 优惠 |
| `EXACT_LINEAR` | 金额在段内线性增长 | 时长模式、部分比例计费场景 |
| `UNSUPPORTED` | 必须查询时重算 | conditional 优惠、动态窗口下的价格感知 FREE_MINUTES 等 |

因此，长期愿景的重点不是“少算一次”的 continue，而是“能否把金额表达为可索引函数”的 timeline / atom 能力。

## 验收标准

- 时长模式的 BillingUnit 包含 validMinutes 字段
- 能力查询接口可返回当前配置支持的预估精度等级
- 能力文档包含条件矩阵，指导调用方按场景选配置
- 现有功能不受影响

## 相关文件

- `core/src/main/java/cn/shang/charging/billing/pojo/BillingUnit.java` - 时长模式新增 validMinutes
- `core/src/main/java/cn/shang/charging/billing/BillingConfigResolver.java` - 能力查询
- `docs/billing-engine-capabilities-zh.md` - 能力文档补充物化预估说明
- 时长模式实现（待立 TODO）

## 备注

- 与时长计费模式关联：时长模式消除截断精度问题，在物化预估中提供一条精确路径（无 conditional 免费段、无段内投影需求时），但非物化预估的唯一基础
- 与 TODO-20260626-001（不足单元计费策略）关联：PROPORTIONAL 等策略引入后，单元模式失去精确物化能力，本 TODO 的条件矩阵说明了这一约束
- 优先级 P2：愿景支撑能力，依赖特性集判定与时长模式落地
