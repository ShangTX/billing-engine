# GLOBAL_ORIGIN 窗口截取模式为半成品，多分段下双重计费

---
id: TODO-20260702-001
type: bug
priority: P1
status: todo
source_git: 9660959
created_at: 2026-07-02
completed_at:
completed_git:
---

## 背景

讨论 `docs/designs/segment-promotion-consistency.md` 时，对照代码核对 GLOBAL_ORIGIN（窗口截取）模式的实现，发现该模式是半成品：`CalculationWindow` 中预留的 `clipBegin` / `clipEnd` 字段从未被任何规则、`BillingService` 或 `ResultAssembler` 读取，文档问题4 所论述的"减法"语义（`分段i = calc(全局起点→段末) − calc(全局起点→段首)`）并未实现。

### 现状证据

- `CalculationWindowFactory.create()` 中 GLOBAL_ORIGIN 的实际行为：
  - `calculationBegin = beginTime`（全局起点）
  - `calculationEnd = segment.endTime`
  - `clipBegin/clipEnd = segment` 边界
- `CalculationWindow.clipBegin` / `clipEnd` 字段注释为"最终要截取的时间范围（可为空）"，但全局检索显示这两个字段从未被读取。
- `DayNightUnitBasedRule` 等规则直接用 `calcBegin/calcEnd` 计算，不读 clip。
- `ResultAssembler.assemble()` 只做 `chargedAmount` 求和与 compact 合并，无任何截取/相减逻辑。

### 后果

对分段2（如 4:00-10:00，方案B），GLOBAL_ORIGIN 当前实际计算的是 `[全局起点 1:00, 10:00]` 整段用方案B，而非"全窗口减前段窗口"。叠加分段1（`[1:00,4:00]` 方案A）后，`1:00-4:00` 被**双重计费**。

- `SchemeSwitchTest` 场景2（GLOBAL_ORIGIN）只打印不断言金额，所以 bug 潜伏。
- 文档问题4 的推导（减法保证外部优惠全局一致）是基于目标语义，不是现状。当前 GLOBAL_ORIGIN 既不能保证优惠一致，还附带双重计费。

## 目标

明确 GLOBAL_ORIGIN 的现状边界，止血潜伏的计费正确性 bug，为后续减法/截取实现铺路。

## 范围

### 包含（止血阶段）

- 明确标注 GLOBAL_ORIGIN 当前为半成品：多分段下双重计费，仅单分段（等价 SEGMENT_LOCAL）可用
- 在 `BillingCalculator` 或 `BillingService` 加守卫：`GLOBAL_ORIGIN + segments.size()>1` 时抛异常，避免静默错误（与"复杂路径显式隔离"原则一致）
- 文档同步：`docs/billing-engine-calculation-flow-zh.md`、`docs/billing-engine-capabilities-zh.md` / `.md`、`docs/USER_GUIDE.md` 标注限制
- 明确 UNIT_BASED 与 GLOBAL_ORIGIN 的结构性不兼容（单元对齐语义与全局起点截取冲突），UNIT_BASED 仅支持 SEGMENT_LOCAL

### 不包含

- GLOBAL_ORIGIN 减法/截取的完整实现（见下方"后续实现 TODO"，待立）
- 外部优惠全局一致性（依赖减法实现，见 `docs/designs/segment-promotion-consistency.md` 问题3/4）
- 优惠分类处理（方案内 vs 外部，见问题5）

## 验收标准

- GLOBAL_ORIGIN + 多分段时抛异常，异常信息明确说明原因
- GLOBAL_ORIGIN + 单分段正常计算（等价 SEGMENT_LOCAL）
- UNIT_BASED + GLOBAL_ORIGIN 抛异常（结构性不兼容）
- 能力文档、流程文档、用户指南均标注 GLOBAL_ORIGIN 当前限制
- 现有测试通过

## 相关文件

- `core/src/main/java/cn/shang/charging/billing/CalculationWindowFactory.java`
- `core/src/main/java/cn/shang/charging/billing/pojo/CalculationWindow.java`
- `core/src/main/java/cn/shang/charging/billing/BillingCalculator.java`
- `core/src/main/java/cn/shang/charging/billing/BillingService.java`
- `core/src/main/java/cn/shang/charging/settlement/ResultAssembler.java`
- `docs/billing-engine-calculation-flow-zh.md`
- `docs/billing-engine-capabilities-zh.md` / `docs/billing-engine-capabilities.md`
- `docs/USER_GUIDE.md`

## 备注

- 与 `docs/designs/segment-promotion-consistency.md` 问题4 直接相关：该文档把减法当作现状推导，实际是目标语义。本 TODO 是止血，减法实现是后续工作。
- 优先级 P1：潜伏的计费正确性 bug，不仅是优惠一致性提升。
- 后续实现方向（待立独立 TODO）：GLOBAL_ORIGIN 减法实现，先支持 CONTINUOUS + DurationMode，UNIT_BASED 显式排除。
- 关联 TODO：TODO-20260701-001（FREE_RANGE 产出 PromotionUsage，是验证减法抵消的前置）、TODO-20260701-002（previousAccumulatedAmount 清理）。
