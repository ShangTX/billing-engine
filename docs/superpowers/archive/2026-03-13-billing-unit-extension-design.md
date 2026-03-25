# 计费单元延伸设计

## 背景

最后一个计费单元会被计费结束时间截断，导致：
1. `effectiveTo` 等于计费结束时间，缓存有效期没有意义
2. 无法支持预测计算场景下的"预计算"需求

## 解决方案

将截断的最后一个计费单元延伸到完整长度，延伸到最近的边界（周期边界或时间段边界）。

## 需求决策

| 维度 | 决策 |
|------|------|
| 延伸边界 | 周期边界和时间段边界取最近的 |
| 收费影响 | 无，收费金额不变（全额收费） |
| 单元存储 | 替换为延伸后的单元 |
| 新增字段 | `calculationEndTime`（分段级 + 顶层） |

## 示例

```
配置：
  单元长度：60分钟
  时间段边界：120分钟（从计费起点）

计费时间：08:00-09:00（1小时）
原截断单元：08:00-09:00（被结束时间截断）
下一个时间段边界：10:00（120分钟边界）
下一个周期边界：次日 08:00（24小时边界）

延伸后单元：08:00-10:00（取最近的边界：时间段边界）
收费金额：全额（不变）
calculationEndTime：10:00
effectiveTo：10:00（延伸后，缓存有效期延长）
```

## 数据结构变更

### BillingSegmentResult 新增字段

```java
/** 实际计算到的时间点（延伸后，最后一个单元的结束时间） */
private LocalDateTime calculationEndTime;
```

### BillingResult 新增字段

```java
/** 实际计算到的时间点（延伸后，用于缓存有效期判断） */
private LocalDateTime calculationEndTime;
```

### BillingCarryOver 依赖

此功能是 CONTINUE 模式的前置依赖：
- `BillingCarryOver.calculatedUpTo` 将使用 `calculationEndTime`
- CONTINUE 模式从延伸后的时间点继续计算，避免重复计算延伸部分

## 延伸逻辑

```
计算最后一个单元时：
1. 获取单元原始结束时间（未截断的完整单元）
2. 查找下一个边界：
   - 下一个周期边界：currentCycleEnd + 24h
   - 下一个时间段边界：根据 RelativeTimeRule 的 periods 配置
3. 取最近的边界作为延伸终点
4. 更新单元 endTime 为延伸后的时间
5. 设置 calculationEndTime = 延伸后的单元结束时间
6. 收费金额不变
```

### 边界计算规则

**周期边界**：
- DayNightRule：从计费起点开始，每24小时为一个周期
- RelativeTimeRule：同上

**时间段边界**：
- RelativeTimeRule：根据 `RelativeTimePeriod.endMinute` 计算
  - 例如：period1 = 0-120分钟，period2 = 120-1440分钟
  - 计费起点 08:00，第一个时间段边界在 10:00（120分钟）

**取最近边界**：
```
nextBoundary = min(nextCycleBoundary, nextPeriodBoundary)
延伸终点 = nextBoundary（如果比当前单元结束时间晚）
```

## 测试用例

### 测试1：延伸到时间段边界

```
配置：
  单元长度：60分钟
  period1: 0-120分钟
  period2: 120-1440分钟

计费时间：08:00-09:00
原单元：08:00-09:00
下一个时间段边界：10:00
下一个周期边界：次日 08:00

延伸后：08:00-10:00
calculationEndTime：10:00
收费：全额
```

### 测试2：延伸到周期边界

```
配置：
  单元长度：60分钟
  无时间段边界限制

计费时间：08:00-09:00
下一个周期边界：次日 08:00

延伸后：08:00-次日 08:00（24小时单元）
calculationEndTime：次日 08:00
收费：全额
```

### 测试3：单元完整不延伸

```
计费时间：08:00-09:00
单元长度：60分钟
最后一个单元：08:00-09:00（被截断，但下一个边界是 09:00）

延伸后：08:00-09:00（无变化，边界恰好等于结束时间）
calculationEndTime：09:00
```

### 测试4：免费时段覆盖的单元

```
计费时间：08:00-09:00
免费时段：08:00-10:00
最后一个单元：08:00-09:00（免费）

延伸后：08:00-10:00（延伸到免费时段结束）
calculationEndTime：10:00
收费：0（免费）
```

## 文件变更清单

### 修改文件

| 文件路径 | 变更内容 |
|---------|---------|
| `core/.../billing/pojo/BillingResult.java` | 新增 calculationEndTime 字段 |
| `core/.../billing/pojo/BillingSegmentResult.java` | 新增 calculationEndTime 字段 |
| `core/.../settlement/ResultAssembler.java` | 汇总 calculationEndTime |
| `core/.../charge/rules/relativetime/RelativeTimeRule.java` | 实现计费单元延伸逻辑 |
| `core/.../charge/rules/daynight/DayNightRule.java` | 实现计费单元延伸逻辑 |

## 与 CONTINUE 模式的关联

此功能完成后，CONTINUE 模式将：
1. 使用 `calculationEndTime` 作为 `calculatedUpTo` 的值
2. 从延伸后的时间点继续计算
3. 避免重复计算延伸部分

```
第一次计算：
  计费时间：08:00-09:00
  延伸后：08:00-10:00
  calculationEndTime：10:00

CONTINUE 模式：
  从 10:00 继续计算（而非 09:00）
```