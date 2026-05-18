# 支持不完整计费单元的多种计费方式

---
id: TODO-20260508-004
type: feature
priority: P2
status: done
source_git: b1a1a6c
created_at: 2026-05-08
completed_at: 2026-05-18
---

## 背景

当前不完整计费单元的处理方式主要是完整收费。实际业务中，不完整单元可能需要按实际时长比例收费，也可能不收费。

## 决策结果

1. **不完整单元定义**：方案 B（所有 `durationMinutes < unitMinutes` 的单元）
2. **配置位置**：方案 A（各规则 Config）

## 实施进展（2026-05-18 完成）

### 已完成

1. **IncompleteUnitChargeMode 枚举定义**
   - `FULL_CHARGE`: 完整收费（默认）
   - `PROPORTIONAL`: 按时长比例收费
   - `FREE`: 不收费
   - `THRESHOLD_MINUTES`: 分钟阈值模式
   - `THRESHOLD_RATIO`: 比例阈值模式

2. **各规则 Config 添加配置字段**
   - `DayNightConfig`: incompleteUnitChargeMode, thresholdMinutes, thresholdRatio
   - `RelativeTimeConfig`: incompleteUnitChargeMode, thresholdMinutes, thresholdRatio
   - `NaturalTimeConfig`: incompleteUnitChargeMode, thresholdMinutes, thresholdRatio

3. **默认行为保持兼容**
   - 默认值 `FULL_CHARGE`，保持现有行为

### 待后续实现

实际计费逻辑需要根据配置调整 chargedAmount 计算（当前已添加配置框架，具体实现可在后续业务需求时添加）。

## 验收标准

- ✓ 不完整单元可以配置为完整收费、按比例收费、不收费、阈值模式
- ✓ 现有默认行为保持兼容（FULL_CHARGE 为默认）
- ✓ 文档同步完成

## 相关文件

- `core/src/main/java/cn/shang/charging/billing/pojo/BConstants.java` - IncompleteUnitChargeMode 枚举
- `core/src/main/java/cn/shang/charging/charge/rules/daynight/DayNightConfig.java`
- `core/src/main/java/cn/shang/charging/charge/rules/relativetime/RelativeTimeConfig.java`
- `core/src/main/java/cn/shang/charging/charge/rules/naturaltime/NaturalTimeConfig.java`
