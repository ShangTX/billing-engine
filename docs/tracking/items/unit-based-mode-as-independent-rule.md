# UNIT_BASED 模式降级为独立计费规则

---
id: TODO-20260630-001
type: refactor
priority: P2
status: todo
source_git: 0251f21
created_at: 2026-06-30
completed_at:
completed_git:
---

## 背景

当前 4 个时间计费规则（DayNight/RelativeTime/CompositeTime/NaturalTime）各自内置 CONTINUOUS 与 UNIT_BASED 两套计算路径。UNIT_BASED 的不可替代性收窄到"免费时段部分覆盖单元时不切断单元、按整单元收费"这一窄场景，却带来约 2000 行重复代码、测试翻倍、阻碍时长模式引入等代价。

经讨论决定：**UNIT_BASED 不再作为每个规则的内置模式，而是降级为独立计费规则类型**。普通规则只保留 CONTINUOUS（边界驱动为唯一路径），UNIT_BASED 语义按需单独实现规则类，不复用边界驱动公共循环。

## 目标

- 4 个规则去掉 UNIT_BASED 分支，边界驱动成为普通规则唯一计算路径
- UNIT_BASED 作为独立规则类型存在，当前只补 1 个最经典的日夜 UNIT_BASED 规则，其余按需添加
- 消除约 2000 行重复代码
- 不破坏项目愿景（实时预估收入、conditional 免费段查询等能力保留）

## 范围

包含：

### 删除（4 个规则）

- `calculate()` 内 `if (mode == UNIT_BASED)` 分支
- `calculateUnitBasedInternal` 方法
- UNIT_BASED 专属方法（需甄别是否被 CONTINUOUS 简化路径复用，复用的保留或内联）
- `XxxUnitBasedCalculator` 包装类（4 个，各约 21 行）
- `supportedModes()` 改为 `EnumSet.of(CONTINUOUS)`（UNIT_BASED 枚举值保留）

### 新增

- `DayNightUnitBasedRule`：独立规则类，固定单元对齐 + 完全覆盖才免费语义，不继承边界驱动公共循环
- 注册到 `BillingRuleRegistry`
- `supportedModes() = EnumSet.of(UNIT_BASED)`

### 测试处理

- `BillingApiTest` 等 UNIT_BASED 用例：
  - 语义与 CONTINUOUS 等价的（无免费段场景）→ 改为 CONTINUOUS
  - 纯 UNIT_BASED 语义的（免费段部分覆盖不切断）→ 迁移到 `DayNightUnitBasedRule` 测试
- 删除 UNIT_BASED 专属断言，保留 CONTINUOUS 部分

不包含：

- 时长计费模式（独立 TODO）
- conditional 免费段迁移到时长模式（独立 TODO）
- 其他规则的 UNIT_BASED 变体（按需添加）

## 关键设计决策

1. **方式 A（按规则类型）而非方式 B（通用规则）**：UNIT_BASED 作为独立规则类，而非通用配置驱动规则。当前只实现日夜版，其余按需
2. **BillingMode.UNIT_BASED 枚举保留**：向后兼容，供独立 UNIT_BASED 规则声明支持
3. **直接删除而非 deprecated**：内部引擎、调用方可控，避免半废弃状态
4. **独立 UNIT_BASED 规则不用边界驱动**：UNIT_BASED 语义（固定对齐、不切断）与边界驱动（找最近边界跳过去）本质冲突，独立实现更清晰

## 实现注意

### CONTINUOUS 简化路径对 UNIT_BASED 方法的复用

关键发现：CONTINUOUS 的简化路径复用了 UNIT_BASED 的逐周期生成方法。例如 RelativeTimeRule 的 `generateUnitsForSingleCycle`（line 396）被 CONTINUOUS 简化路径（line 294/301/313/1252）调用。

删除 UNIT_BASED 分支时，必须逐规则甄别：
- 只被 UNIT_BASED 用的方法 → 删除
- 被 CONTINUOUS 简化路径复用的方法 → 保留，或内联到简化路径

不能简单删除所有 UNIT_BASED 专属方法。

### 4 个规则需逐一甄别的方法

- `RelativeTimeRule.generateUnitsForSingleCycle` / `generateUnitsInPeriod`（CONTINUOUS 简化复用）
- `CompositeTimeRule.generateUnitsInPeriod` / 相关（需甄别）
- `DayNightRule` 的 UNIT_BASED 专属方法（需甄别）
- `NaturalTimeRule.calculateUnitBasedInternal`（需甄别）

## 验收标准

- 4 个规则 `supportedModes()` 仅含 CONTINUOUS
- UNIT_BASED 分支及专属方法删除（CONTINUOUS 简化路径复用的除外）
- `DayNightUnitBasedRule` 独立实现，注册可用
- 现有 CONTINUOUS 测试全部通过
- UNIT_BASED 测试按规则迁移或删除
- 迁移指引文档说明：原 UNIT_BASED 调用方改用 CONTINUOUS 或 `DayNightUnitBasedRule`
- 实时预估收入、conditional 免费段查询等愿景能力不受影响

## 相关文件

- `core/src/main/java/cn/shang/charging/charge/rules/daynight/DayNightRule.java`
- `core/src/main/java/cn/shang/charging/charge/rules/relativetime/RelativeTimeRule.java`
- `core/src/main/java/cn/shang/charging/charge/rules/compositetime/CompositeTimeRule.java`
- `core/src/main/java/cn/shang/charging/charge/rules/naturaltime/NaturalTimeRule.java`
- `core/src/main/java/cn/shang/charging/charge/rules/daynight/DayNightUnitBasedCalculator.java`（删除）
- 其他 3 个 `XxxUnitBasedCalculator.java`（删除）
- `core/src/main/java/cn/shang/charging/billing/pojo/BConstants.java`（BillingMode 保留枚举值）
- `bill-test/src/main/java/cn/shang/charging/BillingApiTest.java`（测试迁移）
- 新增 `core/src/main/java/cn/shang/charging/charge/rules/daynight/DayNightUnitBasedRule.java`

## 备注

- 与时长计费模式（待立 TODO）关联：UNIT_BASED 废弃后，conditional 免费段的适配由时长模式接替，`DayNightUnitBasedRule` 作为需要固定边界语义时的备选
- 优先级 P2：重构性质，不影响现有功能，但为时长模式清理路径
