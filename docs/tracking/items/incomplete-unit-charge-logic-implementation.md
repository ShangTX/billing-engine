# 实现不足单元计费方式配置的实际计费逻辑

---
id: TODO-20260626-001
type: bug
priority: P1
status: todo
source_git: daf19d2
created_at: 2026-06-26
completed_at:
completed_git:
---

## 背景

代码库中存在两套"不足单元计费方式"配置，但计费逻辑均未实现，配置完全无效。这是 `TODO-20260508-004`（已标记 done）遗留的未完成部分——该事项只落地了配置框架，计费逻辑从未接入。

## 问题现状

### 1. `BConstants.IncompleteUnitChargeMode`（5 档，未实现）

枚举定义于 `BConstants.java:56`：
- `FULL_CHARGE`（默认）、`PROPORTIONAL`、`FREE`、`THRESHOLD_MINUTES`、`THRESHOLD_RATIO`

配置字段存在于 `DayNightConfig`、`RelativeTimeConfig`、`NaturalTimeConfig`（含 `thresholdMinutes`、`thresholdRatio`）。

**但三个规则的计费代码完全不读取这些字段**——`getIncompleteUnitChargeMode` / `getThresholdMinutes` / `getThresholdRatio` 在规则代码中零调用。截断单元一律按 FULL_CHARGE 收全额。

### 2. `InsufficientUnitMode`（2 档，部分实现）

枚举定义于 `compositetime/InsufficientUnitMode.java`：
- `FULL`（默认）、`PROPORTIONAL`

配置字段在 `CompositeTimeConfig`，**只在 UNIT_BASED/简化路径的 `generateUnitsInPeriod` 读取**（`CompositeTimeRule.java:1194`），CONTINUOUS 边界驱动的 `applyCapAndAccumulate` 不读取。

### 实测验证

**RelativeTime（CONTINUOUS）**，8:00-9:15，30min 单元 1.50：

| 模式 | 期望 | 实测 |
|------|------|------|
| FULL_CHARGE | 4.50 | 4.50 ✓ |
| PROPORTIONAL | 3.75 | 4.50 ✗ |
| FREE | 3.00 | 4.50 ✗ |

**CompositeTime（CONTINUOUS）**，8:00-9:15，60min 单元 2.00：

| 模式 | 期望 | 实测 |
|------|------|------|
| FULL | 4.00 | 4.00 ✓ |
| PROPORTIONAL | 2.50 | 4.00 ✗ |

截断单元在非 FULL 模式下仍按全额计费，配置被完全忽略。

## 根因

- 各规则的 `applyCapAndAccumulate`（CONTINUOUS 边界驱动路径）计算截断单元金额时直接用 `originalAmount = unitPrice`（FULL 语义），未根据配置调整
- DAY_BASED 路径的 `generateUnitsForCycle` / `generateUnitsForFragment` 同样未读取配置
- 两套枚举（`IncompleteUnitChargeMode` 5 档 vs `InsufficientUnitMode` 2 档）并存，CompositeTime 用一套、其他规则用另一套，语义不统一

## 目标

让不足单元计费方式配置真正生效，统一两套枚举，覆盖 CONTINUOUS 与 UNIT_BASED 两种模式。

## 范围

包含：

- **统一枚举**：合并 `InsufficientUnitMode` 到 `IncompleteUnitChargeMode`（保留 5 档），CompositeTime 改用统一枚举
- **CONTINUOUS 路径接入**：各规则 `applyCapAndAccumulate` 在计算截断单元 `chargedAmount` 时根据配置调整：
  - `FULL_CHARGE`：`unitPrice`（现状）
  - `PROPORTIONAL`：`unitPrice × durationMinutes / unitMinutes`
  - `FREE`：`0`
  - `THRESHOLD_MINUTES`：`durationMinutes >= thresholdMinutes ? unitPrice : 0`
  - `THRESHOLD_RATIO`：`ratio = durationMinutes / unitMinutes；ratio >= thresholdRatio ? unitPrice : unitPrice × ratio`
- **UNIT_BASED 路径接入**：各规则 `generateUnitsForCycle` / `generateUnitsForFragment` 同样根据配置调整
- **compact 合并兼容**：不足单元的 `chargedAmount` 与完整单元不同，自然不参与 compact 合并（`CompactMerger.canMerge` 已按 `chargedAmount` 判定），无需特殊处理
- **CONTINUE 续算**：`truncatedUnitChargedAmount` 取截断单元实际 `chargedAmount`，PROPORTIONAL 等模式下续算扣减金额随之变化，语义自洽

不包含：

- 改变 `isTruncated` 的判定条件（仍为 `durationMinutes < unitMinutes && endTime == calcEnd`）
- 改变 compact 合并规则
- 改变 CONTINUE 续算机制（仍从截断单元起点重算、扣减已收金额）

## 验收标准

- `PROPORTIONAL` 模式：截断单元 `chargedAmount = unitPrice × durationMinutes / unitMinutes`，总额符合期望
- `FREE` 模式：截断单元 `chargedAmount = 0`，`free=true`
- `THRESHOLD_MINUTES`：截断时长 ≥ 阈值收全额，否则免费
- `THRESHOLD_RATIO`：截断比例 ≥ 阈值收全额，否则按比例
- `FULL_CHARGE` 行为不变（默认，向后兼容）
- compact 合并正确：不足单元不与完整单元合并
- CONTINUE 续算金额自洽：第一步截断单元按配置收费，第二步扣减对应金额
- CompositeTime CONTINUOUS 路径接入（当前仅 UNIT_BASED/简化路径生效）
- 现有测试全部通过
- 新增各模式 + 各规则的回归测试

## 关键设计决策（待讨论）

1. **截断单元的 `originalAmount` 与 `chargedAmount`**：PROPORTIONAL 模式下，`originalAmount` 是否也按比例？还是 `originalAmount = unitPrice`、`chargedAmount = 比例值`？建议前者（originalAmount 反映实际应收，chargedAmount 等于 originalAmount，无优惠时两者一致）
2. **compact 单元的 `unitPrice` 字段**：compact 合并的前提是子单元 `chargedAmount` 一致。若不足单元按 PROPORTIONAL 收费，它与前一个完整单元 `chargedAmount` 不同，不会合并——符合预期。但若整段都是不足单元（如全是 15min 片段），是否合并？建议仍按 `chargedAmount` 一致性判定
3. **valueSpec 投影**：不足单元若按 PROPORTIONAL，其 `valueSpec` 应表达"按时长线性累计"而非"固定全额"。`FixedValueSpec(chargedAmount)` 在查询时会返回固定值，与 PROPORTIONAL 语义不符。需用 `PiecewiseTimeValueSpec` 或新增 `ProportionalValueSpec`
4. **THRESHOLD 模式的边界**：`>=` 还是 `>`？建议 `>=`（达到阈值即全额）

## 相关文件

- `core/src/main/java/cn/shang/charging/billing/pojo/BConstants.java` - IncompleteUnitChargeMode 枚举
- `core/src/main/java/cn/shang/charging/charge/rules/compositetime/InsufficientUnitMode.java` - 待合并删除
- `core/src/main/java/cn/shang/charging/charge/rules/compositetime/CompositeTimeConfig.java`
- `core/src/main/java/cn/shang/charging/charge/rules/compositetime/CompositeTimeRule.java`
- `core/src/main/java/cn/shang/charging/charge/rules/daynight/DayNightConfig.java`
- `core/src/main/java/cn/shang/charging/charge/rules/daynight/DayNightRule.java`
- `core/src/main/java/cn/shang/charging/charge/rules/relativetime/RelativeTimeConfig.java`
- `core/src/main/java/cn/shang/charging/charge/rules/relativetime/RelativeTimeRule.java`
- `core/src/main/java/cn/shang/charging/charge/rules/naturaltime/NaturalTimeConfig.java`
- `core/src/main/java/cn/shang/charging/charge/rules/naturaltime/NaturalTimeRule.java`
- `core/src/main/java/cn/shang/charging/billing/value/` - 可能需新增 ProportionalValueSpec

## 备注

- 与 `TODO-20260508-004`（已 done）直接相关：该事项只落地配置框架，本 TODO 完成其遗留的计费逻辑
- 与 compact 改造（`TODO-20260623-002`，已 done）协同：compact 合并按 `chargedAmount` 判定，不足单元因金额不同自然不合并，无需特殊处理
- 优先级 P1：配置已暴露给调用方但无效，属于"承诺了但没兑现"的功能，应优先修复
