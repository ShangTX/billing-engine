# 时长计费模式（Duration-Based Billing Mode）

---
id: TODO-20260630-003
type: feature
priority: P1
status: todo
source_git: 64d8dfa
created_at: 2026-06-30
completed_at:
completed_git:
---

## 背景

当前计费引擎仅支持"单元计费模式"（Unit-Based Billing）：将时间轴按固定 unitMinutes 切分为单元，每个单元独立计费。这种模式存在两个问题：

1. **精度问题**：分单元四舍五入后求和，可能产生累积误差
2. **复杂场景支持不足**：对于"免费时段只扣除分钟数"的场景，单元模式需要特殊处理

时长模式的核心思想：**将时间轴视为连续的分钟流，按分钟数计费，而非按单元计费**。

## 目标

实现两种时长计费模式，复用现有边界驱动循环，仅在产出阶段区分：

- **PERIOD 模式**：周期内按时长计费，周期封顶（如日夜规则，每 24h 周期内分日段/夜段计费）
- **GLOBAL 模式**：全局按时长计费，周期数 × 封顶（如长期停车，48h 停 2 天，封顶金额 × 2）

## 范围

### 包含

1. **模式声明**
   - `BillingRule` 接口新增 `supportedDurationModes()` 方法
   - `BConstants` 新增 `DurationMode` 枚举（NONE / PERIOD / GLOBAL）

2. **配置解析**
   - `BillingConfigResolver` 新增 `resolveDurationMode()` 方法

3. **边界驱动适配**
   - PERIOD 模式：providers 包含周期边界，segment 自然按周期+时段切分
   - GLOBAL 模式：providers 不含周期边界，segment 按全局时段切分（跨周期合并）

4. **产出结构**
   - 新增 `DurationSegment` record（beginTime, endTime, chargedMinutes, unitPrice, chargedAmount, freePromotionId, ruleData）
   - `BillingSegmentResult` 新增 `durationSegments` 字段
   - 不修改现有 `BillingUnit` 结构

5. **封顶逻辑**
   - PERIOD：周期内累计 chargedAmount 达 maxCharge 封顶
   - GLOBAL：周期数 = ceil(总分钟数 / 周期分钟数)
     - 时段封顶：每个时段类型的总金额封顶 = period.maxCharge × 周期数
     - 周期封顶：总金额封顶 = maxChargeOneCycle × 周期数
     - 检查顺序：时段封顶 → 求和 → 周期封顶

6. **测试**
   - PERIOD 模式测试（日夜规则，周期内按时长计费）
   - GLOBAL 模式测试（长期停车，全局按时长计费 + 周期封顶）
   - 精度验证：时长模式 vs 单元模式，时长模式精度更高

### 不包含

- CONTINUE 续算（时长模式不支持）
- compact 合并（时长模式不需要）
- 迁移现有规则到时长模式（仅提供能力，规则按需接入）

## 设计

### DurationMode 枚举

```java
// BConstants.java
public enum DurationMode {
    NONE,      // 不使用时长模式
    PERIOD,    // 周期内时长模式
    GLOBAL     // 全局时长模式
}
```

### BillingRule 接口扩展

```java
public interface BillingRule<C extends RuleConfig> {
    Set<BillingMode> supportedModes();
    Set<DurationMode> supportedDurationModes(); // 新增
    // ...
}
```

### BillingConfigResolver 接口扩展

```java
public interface BillingConfigResolver {
    // 现有方法...
    DurationMode resolveDurationMode(String schemeId, 
                                     LocalDateTime segmentStart, 
                                     LocalDateTime segmentEnd, 
                                     Map<String, Object> context);
}
```

### DurationSegment 结构

```java
public record DurationSegment(
    LocalDateTime beginTime,
    LocalDateTime endTime,
    int chargedMinutes,       // 实际收费分钟数（免费段=0）
    BigDecimal unitPrice,     // 完整单元单价
    BigDecimal chargedAmount, // = unitPrice × chargedMinutes / unitMinutes
    String freePromotionId,
    Object ruleData
) {}
```

### 边界驱动适配

**PERIOD 模式**：
- providers：周期边界 + 时段边界 + 免费段边界 + calcEnd
- segment 按周期+时段切分
- 每个周期独立累计 chargedAmount，达 maxCharge 封顶

**GLOBAL 模式**：
- providers：时段边界 + 免费段边界 + calcEnd（不含周期边界）
- segment 按全局时段切分（跨周期合并）
- 周期数 = ceil(总分钟数 / 周期分钟数)
- 封顶金额 = 周期数 × maxCharge
- 最终金额 = min(封顶金额, 总金额)

### 封顶计算示例

**PERIOD 模式**（日夜规则，24h 周期，日段 3 元/h，夜段 1 元/h，封顶 50 元/天）：
```
00:00-06:00 夜段：360min × 1/60 = 6 元
06:00-18:00 日段：720min × 3/60 = 36 元
18:00-24:00 夜段：360min × 1/60 = 6 元
周期累计：48 元 < 50 元封顶 → 收费 48 元
```

**GLOBAL 模式**（停车 47h，日段 3 元/h，夜段 1 元/h，封顶 50 元/天）：
```
假设日段 24h，夜段 23h（跨周期）：
日段：1440min × 3/60 = 72 元
夜段：1380min × 1/60 = 23 元
总金额：95 元
周期数：ceil(2820min / 1440min) = 2
封顶金额：2 × 50 = 100 元
最终金额：min(100, 95) = 95 元
```

**GLOBAL 模式 + 时段封顶**（48h 停车，3 个 period，周期封顶 50 元/天）：
```
Period 1 (0-120min): 单价 5 元/h, 时段封顶 10 元/天
Period 2 (120-480min): 单价 3 元/h, 时段封顶 30 元/天
Period 3 (480-1440min): 单价 1 元/h, 无时段封顶

48h = 2 周期，每个 period 出现 2 次

1. 按时段类型累计金额：
   - Period 1 总金额：240min × 5/60 = 20 元
   - Period 2 总金额：720min × 3/60 = 36 元
   - Period 3 总金额：1800min × 1/60 = 30 元

2. 应用时段封顶（period.maxCharge × 周期数）：
   - Period 1：min(20, 10×2) = min(20, 20) = 20 元
   - Period 2：min(36, 30×2) = min(36, 60) = 36 元
   - Period 3：无封顶 = 30 元

3. 求和：20 + 36 + 30 = 86 元

4. 应用周期封顶（maxChargeOneCycle × 周期数）：
   - 最终金额：min(86, 50×2) = min(86, 100) = 86 元
```

## 验收标准

- `BillingRule.supportedDurationModes()` 可声明支持 PERIOD / GLOBAL
- `BillingConfigResolver.resolveDurationMode()` 可返回 NONE / PERIOD / GLOBAL
- PERIOD 模式：边界驱动产出 DurationSegment，周期内累计达 cap 封顶
- GLOBAL 模式：边界驱动产出 DurationSegment，全局累计，周期数 × cap 封顶
- `BillingSegmentResult.durationSegments` 包含正确的 DurationSegment 列表
- 测试覆盖 PERIOD / GLOBAL 两种模式
- 精度验证：时长模式无累积舍入误差

## 关键文件

- `core/src/main/java/cn/shang/charging/billing/pojo/BConstants.java`（DurationMode 枚举）
- `core/src/main/java/cn/shang/charging/charge/rules/BillingRule.java`（supportedDurationModes）
- `core/src/main/java/cn/shang/charging/billing/BillingConfigResolver.java`（resolveDurationMode）
- `core/src/main/java/cn/shang/charging/billing/pojo/DurationSegment.java`（新增）
- `core/src/main/java/cn/shang/charging/billing/pojo/BillingSegmentResult.java`（durationSegments 字段）
- `core/src/main/java/cn/shang/charging/billing/BillingService.java`（调用 resolveDurationMode）
- `core/src/main/java/cn/shang/charging/charge/rules/AbstractTimeBasedRule.java`（时长模式的 applyDurationAccumulate）

## 实现计划

### 阶段 1：模式声明与配置解析
- BConstants 新增 DurationMode 枚举
- BillingRule 接口新增 supportedDurationModes()
- BillingConfigResolver 接口新增 resolveDurationMode()

### 阶段 2：产出结构
- 新增 DurationSegment record
- BillingSegmentResult 新增 durationSegments 字段

### 阶段 3：PERIOD 模式实现
- BillingService 调用 resolveDurationMode()
- PERIOD 模式：providers 包含周期边界
- 新增 applyDurationPeriodAccumulate（周期内累计达 cap 封顶）

### 阶段 4：GLOBAL 模式实现
- GLOBAL 模式：providers 不含周期边界
- 新增 applyDurationGlobalAccumulate（全局累计，周期数 × cap 封顶）

### 阶段 5：测试
- PERIOD 模式测试（日夜规则）
- GLOBAL 模式测试（长期停车）
- 精度验证

### 阶段 6：文档
- 更新 USER_GUIDE：时长模式的使用方式
- 更新能力文档：时长模式的能力说明

## 风险

- PERIOD 和 GLOBAL 两种模式的封顶语义不同，需要明确文档说明
- 周期数计算（ceil）可能导致 47h = 2 周期，用户可能困惑
- 不支持 CONTINUE 续算，长期停车场景可能需要分批计算
