# CONTINUE 模式累计金额设计

## 日期
2026-03-31

---

## 一、背景

### 问题陈述

CONTINUE 模式存在截断单元重复收费 bug：

1. 第一次查询 09:23-11:45，生成截断单元 11:23-11:45（22分钟），按完整单元收费 2元
2. 第二次 CONTINUE 查询 09:23-13:20，从 11:23 开始重新计算，又生成了 11:23-12:23 单元收费 2元
3. 问题：11:23-11:45 这22分钟在两次查询中都被收费

**根因分析**：
- 原方案是"先计算再合并"，逻辑分散且复杂
- 截断单元按完整单元收费后，第二次计算又从截断位置开始新单元
- 调用方需要累加各次结果才能得到总费用，语义不清

### 目标

1. CONTINUE 模式返回**累计总费用**，而非增量费用
2. 每个计费单元携带 `accumulatedAmount`，显示从计费开始到当前单元的累计金额
3. 修复截断单元重复收费问题

---

## 二、设计决策

### 决策1：累计金额语义

| 模式 | 返回值语义 | 说明 |
|------|-----------|------|
| FROM_SCRATCH | 本次计费的总费用 | 直接返回计算结果 |
| CONTINUE | 从原始起点到当前的累计总费用 | 不是增量，是累计 |

**示例**：
```
第一次查询 09:23-11:45：返回 6元
第二次 CONTINUE 09:23-13:20：返回 10元（累计，不是 10-6=4元增量）
第三次 CONTINUE 09:23-15:08：返回 12元（累计）
```

### 决策2：BillingUnit.accumulatedAmount 字段

| 字段 | 类型 | 说明 |
|------|------|------|
| `accumulatedAmount` | BigDecimal | 从计费开始到当前单元的累计金额 |

**用途**：
- 调用方可直接查看任意单元的累计金额
- CONTINUE 模式下，从 `previousAccumulatedAmount` 继续累加

### 决策3：避免重复收费的机制

当存在截断单元时，CONTINUE 模式需要特殊处理：

| 字段 | 位置 | 说明 |
|------|------|------|
| `truncatedUnitChargedAmount` | BillingCarryOver | 截断单元已收取的金额 |
| `truncatedUnitChargedAmount` | BillingContext | 传递给规则层 |

**处理逻辑**：
1. ResultAssembler 提取截断单元的 `chargedAmount`，存入 `BillingCarryOver.truncatedUnitChargedAmount`
2. CONTINUE 模式从 `lastTruncatedUnitStartTime` 开始重新计算（保证单元完整性）
3. 规则层在计算累计金额时，**扣减** `truncatedUnitChargedAmount`（避免重复收费）

### 决策4：废弃字段

| 字段 | 原因 |
|------|------|
| `BillingResult.firstUnitMerged` | 新方案不再需要合并标记 |
| `BillingUnit.mergedFromPrevious` | 新方案不再需要合并标记 |
| `BillingContext.previousTruncatedUnitStartTime` | 改用 BillingService 层调整起点 |

---

## 三、架构设计

### 3.1 数据流

```
第一次计算 (FROM_SCRATCH):
  Rule → 生成 BillingUnit，计算 accumulatedAmount
  ResultAssembler → 提取 truncatedUnitChargedAmount
  BillingCarryOver → 存储 accumulatedAmount + truncatedUnitChargedAmount

第二次计算 (CONTINUE):
  BillingService → 读取 carryOver，设置 actualBeginTime = lastTruncatedUnitStartTime
  BillingContext → 携带 previousAccumulatedAmount + truncatedUnitChargedAmount
  Rule → accumulatedAmount = previousAccumulatedAmount - truncatedUnitChargedAmount + 本次金额
  ResultAssembler → 返回累计总费用
```

### 3.2 组件职责

| 组件 | 职责 |
|------|------|
| BillingService | 调整 actualBeginTime，传递 previousAccumulatedAmount 和 truncatedUnitChargedAmount |
| BillingContext | 携带累计金额和截断单元金额 |
| RelativeTimeRule | 计算 accumulatedAmount，扣减 truncatedUnitChargedAmount |
| DayNightRule | 同上 |
| ResultAssembler | 提取并设置 accumulatedAmount 和 truncatedUnitChargedAmount |

---

## 四、文件变更

| 文件 | 变更类型 | 说明 |
|------|---------|------|
| `BillingUnit.java` | 新增字段 | `accumulatedAmount` |
| `BillingCarryOver.java` | 新增字段 | `accumulatedAmount`, `truncatedUnitChargedAmount` |
| `BillingContext.java` | 新增字段 | `previousAccumulatedAmount`, `truncatedUnitChargedAmount` |
| `BillingService.java` | 修改逻辑 | CONTINUE 模式起点调整和字段传递 |
| `RelativeTimeRule.java` | 修改逻辑 | 累计金额计算 |
| `DayNightRule.java` | 修改逻辑 | 累计金额计算 |
| `ResultAssembler.java` | 新增方法 | 提取和设置累计金额 |

---

## 五、验证场景

### 场景：真实停车多次查询

```
车辆进入：09:23
第一次查询 09:23-11:45：返回 6元
第二次 CONTINUE 09:23-13:20：返回 10元
第三次 CONTINUE 09:23-15:08：返回 12元

验证：第三次结果 = 一次性计算 09:23-15:08 = 12元 ✓
```