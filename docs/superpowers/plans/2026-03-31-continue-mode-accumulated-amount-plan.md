# CONTINUE 模式累计金额实现计划

> **For agentic workers:** REQUIRED SUB-SILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** CONTINUE 模式返回累计总费用，修复截断单元重复收费问题

**Architecture:** 每个计费单元携带 `accumulatedAmount` 累计金额，CONTINUE 模式从 `previousAccumulatedAmount` 继续累加，通过 `truncatedUnitChargedAmount` 扣减避免重复收费。

**Tech Stack:** Java 21, Lombok, Maven

**Design Doc:** `docs/superpowers/specs/2026-03-31-continue-mode-accumulated-amount-design.md`

---

## 文件结构

| 文件 | 变更类型 | 说明 |
|------|---------|------|
| `BillingUnit.java` | 新增字段 | `accumulatedAmount` |
| `BillingCarryOver.java` | 新增字段 | `accumulatedAmount`, `truncatedUnitChargedAmount` |
| `BillingContext.java` | 新增字段 | `previousAccumulatedAmount`, `truncatedUnitChargedAmount` |
| `BillingService.java` | 修改逻辑 | CONTINUE 模式传递累计金额和截断单元金额 |
| `RelativeTimeRule.java` | 修改逻辑 | 累计金额计算，扣减截断单元金额 |
| `DayNightRule.java` | 修改逻辑 | 同上 |
| `ResultAssembler.java` | 新增方法 | 提取并设置累计金额和截断单元金额 |

---

## Task 1: 数据结构新增字段

**Files:**
- Modify: `core/src/main/java/cn/shang/charging/billing/pojo/BillingUnit.java`
- Modify: `core/src/main/java/cn/shang/charging/billing/pojo/BillingCarryOver.java`
- Modify: `core/src/main/java/cn/shang/charging/billing/pojo/BillingContext.java`

- [x] **Step 1: BillingUnit 新增 accumulatedAmount 字段**

```java
/**
 * 从计费开始到当前单元的累计金额
 */
private BigDecimal accumulatedAmount;
```

- [x] **Step 2: BillingCarryOver 新增字段**

```java
/**
 * 上次计算的累计总金额
 */
private BigDecimal accumulatedAmount;

/**
 * 截断单元已收取的金额
 * CONTINUE 模式下重新计算截断单元时，需要扣减此金额以避免重复收费
 */
private BigDecimal truncatedUnitChargedAmount;
```

- [x] **Step 3: BillingContext 新增字段**

```java
/**
 * 从 carryOver 恢复的累计金额
 */
private BigDecimal previousAccumulatedAmount;

/**
 * 截断单元已收取的金额
 */
private BigDecimal truncatedUnitChargedAmount;
```

- [x] **Step 4: 编译验证**

Run: `mvn compile -pl core -q`
Expected: 编译成功

---

## Task 2: BillingService 传递累计金额

**Files:**
- Modify: `core/src/main/java/cn/shang/charging/billing/BillingService.java`

- [x] **Step 1: CONTINUE 模式读取并传递累计金额**

```java
if (isContinueMode) {
    if (carryOver.getLastTruncatedUnitStartTime() != null) {
        actualBeginTime = carryOver.getLastTruncatedUnitStartTime();
        truncatedUnitChargedAmount = carryOver.getTruncatedUnitChargedAmount();
    } else if (carryOver.getCalculatedUpTo() != null) {
        actualBeginTime = carryOver.getCalculatedUpTo();
    }
    if (carryOver.getAccumulatedAmount() != null) {
        previousAccumulatedAmount = carryOver.getAccumulatedAmount();
    }
}
```

- [x] **Step 2: 构建 BillingContext 时传递字段**

---

## Task 3: ResultAssembler 提取并设置累计金额

**Files:**
- Modify: `core/src/main/java/cn/shang/charging/settlement/ResultAssembler.java`

- [x] **Step 1: 新增提取截断单元金额方法**

```java
private BigDecimal extractTruncatedUnitChargedAmount(List<BillingSegmentResult> segmentResultList) {
    // 查找最后一个截断单元，返回其 chargedAmount
}
```

- [x] **Step 2: 构建 BillingCarryOver 时设置字段**

```java
return BillingCarryOver.builder()
        .calculatedUpTo(calculationEndTime)
        .segments(segments)
        .lastTruncatedUnitStartTime(lastTruncatedUnitStartTime)
        .truncatedUnitChargedAmount(truncatedUnitChargedAmount)
        .accumulatedAmount(accumulatedAmount)
        .build();
```

---

## Task 4: 规则层计算累计金额

**Files:**
- Modify: `core/src/main/java/cn/shang/charging/charge/rules/relativetime/RelativeTimeRule.java`
- Modify: `core/src/main/java/cn/shang/charging/charge/rules/daynight/DayNightRule.java`

- [x] **Step 1: 计算累计金额，扣减截断单元金额**

```java
BigDecimal accumulatedAmount = context.getPreviousAccumulatedAmount();
if (accumulatedAmount == null) {
    accumulatedAmount = BigDecimal.ZERO;
}

BigDecimal truncatedUnitChargedAmount = context.getTruncatedUnitChargedAmount();
if (truncatedUnitChargedAmount != null && !allUnits.isEmpty()) {
    accumulatedAmount = accumulatedAmount.subtract(truncatedUnitChargedAmount);
    if (accumulatedAmount.compareTo(BigDecimal.ZERO) < 0) {
        accumulatedAmount = BigDecimal.ZERO;
    }
}

for (BillingUnit unit : allUnits) {
    accumulatedAmount = accumulatedAmount.add(unit.getChargedAmount());
    unit.setAccumulatedAmount(accumulatedAmount);
}
```

- [x] **Step 2: 两处修改（UNIT_BASED 和 CONTINUOUS 模式）**

---

## Task 5: 标记废弃字段

**Files:**
- Modify: `core/src/main/java/cn/shang/charging/billing/pojo/BillingUnit.java`
- Modify: `core/src/main/java/cn/shang/charging/billing/pojo/BillingResult.java`
- Modify: `core/src/main/java/cn/shang/charging/billing/pojo/BillingContext.java`

- [x] **Step 1: 添加 @Deprecated 注解**

```java
@Deprecated
private Boolean mergedFromPrevious;

@Deprecated
private Boolean firstUnitMerged;

@Deprecated
private LocalDateTime previousTruncatedUnitStartTime;
```

---

## Task 6: 文档更新

**Files:**
- Modify: `README.md`
- Modify: `README_CN.md`

- [x] **Step 1: 更新 BillingUnit 字段说明**
- [x] **Step 2: 更新 BillingCarryOver 字段说明**

---

## Task 7: 测试验证

**Files:**
- Modify: `bill-test/src/main/java/cn/shang/charging/ContinueModeTest.java`

- [x] **Step 1: 更新测试验证逻辑**

CONTINUE 模式返回累计金额，验证时取最后一次结果，而非相加。

- [x] **Step 2: 运行测试验证**

Run: `mvn exec:java -pl bill-test -Dexec.mainClass="cn.shang.charging.ContinueModeTest"`
Expected: 真实停车场景测试通过，累计金额 = 一次性计算结果

---

## 完成状态

✅ 所有 Task 已完成
✅ 测试验证通过
✅ 文档已更新