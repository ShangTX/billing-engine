# 为相关规则添加跨时段处理配置

---
id: TODO-20260508-005
type: feature
priority: P2
status: done
source_git: b1a1a6c
created_at: 2026-05-08
completed_at: 2026-05-18
---

## 背景

当前跨时段计费单元的处理方式：

| 规则 | 跨段处理 | 可配置性 |
|------|----------|----------|
| `dayNight` | `blockWeight` 固定方式 | ❌ |
| `relativeTime` | 截断固定方式 | ❌ |
| `compositeTime` | 7 种可选模式 | ✅ |
| `naturalTime`（已开发） | 7 种可选模式 | ✅ |

`dayNight` 和 `naturalTime` 都适合支持多种跨段处理方式。

## 目标

为 `dayNight` 和 `naturalTime` 添加 `crossPeriodMode` 配置能力，使用与 `compositeTime` 相同的 7 种模式。

**设计原则**：
- 复用 `CrossPeriodMode` 枚举，统一语义
- 各规则根据自身特点选择支持的模式 subset
- 保持配置向后兼容（默认值为当前行为）

## CrossPeriodMode 七种模式

| 模式 | 语义 | 适用场景 |
|------|------|----------|
| `BEGIN_TIME_TRUNCATE` | 截断 + 开始时段价格 | 单元不跨段 |
| `BEGIN_TIME_PRICE` | 不截断，开始时段价格 | 统一计费 |
| `BLOCK_WEIGHT` | 开始时段价格（按比例判断） | 类似当前 dayNight |
| `HIGHER_PRICE` | 取较高价格 | 保护用户 |
| `LOWER_PRICE` | 取较低价格 | 保护商户 |
| `PROPORTIONAL` | 按比例拆分 | 精确计算 |
| `END_TIME_PRICE` | 结束时段价格 | 特殊场景 |

## 实施进展（2026-05-18 完成）

### dayNight 改造（已完成）

1. ✓ `DayNightConfig` 添加 `crossPeriodMode` 字段，默认值 `BLOCK_WEIGHT`
2. ✓ `DayNightPriceResolver` 支持所有 7 种跨段模式
3. ✓ 保持 `blockWeight` 字段用于 BLOCK_WEIGHT 模式
4. ✓ `determineFinalAmount` 方法签名更新，接收 begin/end 时间参数
5. ✓ 回归测试通过（DayNightParkingParityTest, DayNightQueryValueTest）

### naturalTime 改造（已完成）

1. ✓ `NaturalTimeConfig` 直接支持 `crossPeriodMode`，默认值 `BEGIN_TIME_TRUNCATE`
2. ✓ `NaturalTimeCrossPeriodPriceResolver` 复用 CrossPeriodMode 逻辑
3. ✓ 测试覆盖跨时段高价模式场景

### relativeTime 不改造

`relativeTime` 各时段 `unitMinutes` 可能不同，单元无法跨段，截断是唯一合理选择。

## 验收标准

- ✓ `dayNight` 可配置跨段处理方式
- ✓ `naturalTime` 支持跨段配置
- ✓ 与 `compositeTime` 使用相同 `CrossPeriodMode` 枚举
- ✓ 默认值保持向后兼容
- ✓ 测试覆盖各模式场景

## 相关文件

- `core/src/main/java/cn/shang/charging/charge/rules/compositetime/CrossPeriodMode.java`
- `core/src/main/java/cn/shang/charging/charge/rules/daynight/DayNightConfig.java`
- `core/src/main/java/cn/shang/charging/charge/rules/daynight/DayNightPriceResolver.java`
- `core/src/main/java/cn/shang/charging/charge/rules/naturaltime/NaturalTimeConfig.java`
- `core/src/main/java/cn/shang/charging/charge/rules/naturaltime/NaturalTimeCrossPeriodPriceResolver.java`

## 备注

已完成实现，dayNight 和 naturalTime 都支持统一的跨段处理模式配置。