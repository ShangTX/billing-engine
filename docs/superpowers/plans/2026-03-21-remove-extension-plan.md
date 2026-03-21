# 取消延伸功能重构实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 删除计费单元延伸逻辑，简化规则实现，通过截断标记支持 CONTINUE 模式恢复

**Architecture:** 计算逻辑严格限制在计算窗口内，删除 boundaryReferences 机制，新增 queryTime 和 isTruncated 字段

**Tech Stack:** Java, Lombok, Maven

---

## 文件结构

### 新增字段
- `core/src/main/java/cn/shang/charging/billing/pojo/BillingRequest.java` - 新增 queryTime
- `core/src/main/java/cn/shang/charging/billing/pojo/BillingUnit.java` - 新增 isTruncated

### 删除字段
- `core/src/main/java/cn/shang/charging/promotion/pojo/PromotionAggregate.java` - 删除 boundaryReferences
- `core/src/main/java/cn/shang/charging/promotion/pojo/TimeRangeMergeResult.java` - 删除 boundaryReferences

### 修改逻辑
- `core/src/main/java/cn/shang/charging/charge/rules/daynight/DayNightRule.java` - 删除延伸方法
- `core/src/main/java/cn/shang/charging/charge/rules/relativetime/RelativeTimeRule.java` - 删除延伸方法
- `core/src/main/java/cn/shang/charging/charge/rules/compositetime/CompositeTimeRule.java` - 删除延伸方法
- `core/src/main/java/cn/shang/charging/promotion/PromotionEngine.java` - 删除 boundaryReferences 传递
- `core/src/main/java/cn/shang/charging/promotion/FreeTimeRangeMerger.java` - 删除边界参考处理
- `core/src/main/java/cn/shang/charging/settlement/ResultAssembler.java` - 新增 queryTime 处理

---

## Phase 1: 数据结构变更

### Task 1.1: BillingRequest 新增 queryTime 字段

**Files:**
- Modify: `core/src/main/java/cn/shang/charging/billing/pojo/BillingRequest.java`

- [ ] **Step 1: 新增 queryTime 字段**

在 `BillingRequest.java` 中添加字段：

```java
/**
 * 查询时间点（可选）
 * 用于返回该时刻的费用状态
 * 不提供时，默认使用 calcEndTime
 */
private LocalDateTime queryTime;

/**
 * 计算结束时间（可选）
 * 用于控制计算进度
 * 不提供时，使用 endTime
 */
private LocalDateTime calcEndTime;
```

- [ ] **Step 2: 编译验证**

Run: `cd D:/dev/code/park-bill/charge && mvn clean compile -pl core -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: 提交**

```bash
git add core/src/main/java/cn/shang/charging/billing/pojo/BillingRequest.java
git commit -m "[claude-code|glm-5|superpowers] feat: BillingRequest 新增 queryTime 和 calcEndTime 字段

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>"
```

---

### Task 1.2: BillingUnit 新增 isTruncated 字段

**Files:**
- Modify: `core/src/main/java/cn/shang/charging/billing/pojo/BillingUnit.java`

- [ ] **Step 1: 新增 isTruncated 字段**

在 `BillingUnit.java` 中添加字段：

```java
/**
 * 是否被 calcEndTime 截断
 * 用于 CONTINUE 模式恢复截断单元
 */
private Boolean isTruncated;
```

- [ ] **Step 2: 编译验证**

Run: `cd D:/dev/code/park-bill/charge && mvn clean compile -pl core -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: 提交**

```bash
git add core/src/main/java/cn/shang/charging/billing/pojo/BillingUnit.java
git commit -m "[claude-code|glm-5|superpowers] feat: BillingUnit 新增 isTruncated 字段

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>"
```

---

### Task 1.3: 删除 TimeRangeMergeResult.boundaryReferences

**Files:**
- Modify: `core/src/main/java/cn/shang/charging/promotion/pojo/TimeRangeMergeResult.java`

- [ ] **Step 1: 删除 boundaryReferences 字段和相关方法**

删除以下内容：
1. 字段：`private List<FreeTimeRange> boundaryReferences;`
2. 构造函数中的初始化：`this.boundaryReferences = new ArrayList<>();`
3. 方法：`addBoundaryReference(FreeTimeRange range)`

修改后的类：

```java
@Data
@AllArgsConstructor
public class TimeRangeMergeResult {

    private List<FreeTimeRange> mergedRanges;      // 合并后的时间段集合
    private List<FreeTimeRange> discardedRanges;   // 被舍弃的时间段集合
    private Map<String, List<FreeTimeRange>> originalToDiscarded; // 原始时间段与被舍弃部分的映射

    public TimeRangeMergeResult() {
        this.mergedRanges = new ArrayList<>();
        this.discardedRanges = new ArrayList<>();
        this.originalToDiscarded = new HashMap<>();
    }

    // ... 其他方法保持不变 ...
}
```

- [ ] **Step 2: 编译验证**

Run: `cd D:/dev/code/park-bill/charge && mvn clean compile -pl core -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: 提交**

```bash
git add core/src/main/java/cn/shang/charging/promotion/pojo/TimeRangeMergeResult.java
git commit -m "[claude-code|glm-5|superpowers] refactor: 删除 TimeRangeMergeResult.boundaryReferences

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>"
```

---

### Task 1.4: 删除 PromotionAggregate.boundaryReferences

**Files:**
- Modify: `core/src/main/java/cn/shang/charging/promotion/pojo/PromotionAggregate.java`

- [ ] **Step 1: 删除 boundaryReferences 字段**

删除字段：

```java
// 删除这一行
List<FreeTimeRange> boundaryReferences;  // 边界参考时段（窗口外，用于延伸边界判断）
```

- [ ] **Step 2: 编译验证**

Run: `cd D:/dev/code/park-bill/charge && mvn clean compile -pl core -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: 提交**

```bash
git add core/src/main/java/cn/shang/charging/promotion/pojo/PromotionAggregate.java
git commit -m "[claude-code|glm-5|superpowers] refactor: 删除 PromotionAggregate.boundaryReferences

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>"
```

---

## Phase 2: 删除延伸逻辑

### Task 2.1: DayNightRule 删除延伸逻辑

**Files:**
- Modify: `core/src/main/java/cn/shang/charging/charge/rules/daynight/DayNightRule.java`

- [ ] **Step 1: 删除 extendLastUnit 方法**

找到并删除 `extendLastUnit` 方法（约 60-80 行）

- [ ] **Step 2: 删除 findNextFreeRangeBoundary 方法**

找到并删除 `findNextFreeRangeBoundary` 方法（约 30 行）

- [ ] **Step 3: 删除 boundaryReferences 获取逻辑**

在 `calculateUnitBased` 和 `calculateContinuous` 方法中，删除：

```java
// 删除以下代码
List<FreeTimeRange> boundaryReferences = promotionAggregate.getBoundaryReferences();
if (boundaryReferences == null) {
    boundaryReferences = List.of();
}
```

- [ ] **Step 4: 修改 calculationEndTime 赋值**

将：
```java
LocalDateTime extendedCalculationEndTime = extendLastUnit(...);
```

改为：
```java
LocalDateTime calculationEndTime = calcEnd;
```

- [ ] **Step 5: 新增截断标记逻辑**

在生成计费单元后，添加截断标记：

```java
// 标记最后一个单元是否被截断
if (!billingUnits.isEmpty()) {
    BillingUnit lastUnit = billingUnits.get(billingUnits.size() - 1);
    int unitMinutes = config.getUnitMinutes();
    if (lastUnit.getDurationMinutes() < unitMinutes && lastUnit.getEndTime().equals(calcEnd)) {
        lastUnit.setIsTruncated(true);
    }
}
```

- [ ] **Step 6: 编译验证**

Run: `cd D:/dev/code/park-bill/charge && mvn clean compile -pl core -q`
Expected: BUILD SUCCESS

- [ ] **Step 7: 提交**

```bash
git add core/src/main/java/cn/shang/charging/charge/rules/daynight/DayNightRule.java
git commit -m "[claude-code|glm-5|superpowers] refactor: DayNightRule 删除延伸逻辑，新增截断标记

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>"
```

---

### Task 2.2: RelativeTimeRule 删除延伸逻辑

**Files:**
- Modify: `core/src/main/java/cn/shang/charging/charge/rules/relativetime/RelativeTimeRule.java`

- [ ] **Step 1: 删除 extendLastUnit 方法**

找到并删除 `extendLastUnit` 方法（约 80-100 行）

- [ ] **Step 2: 删除 findNextFreeRangeBoundary 方法**

找到并删除 `findNextFreeRangeBoundary` 方法（约 30 行）

- [ ] **Step 3: 删除 boundaryReferences 获取逻辑**

在 `calculateUnitBased` 和 `calculateContinuous` 方法中，删除 boundaryReferences 相关代码

- [ ] **Step 4: 修改 calculationEndTime 赋值**

将延伸调用改为直接赋值：`calculationEndTime = calcEnd;`

- [ ] **Step 5: 新增截断标记逻辑**

同 Task 2.1 Step 5

- [ ] **Step 6: 编译验证**

Run: `cd D:/dev/code/park-bill/charge && mvn clean compile -pl core -q`
Expected: BUILD SUCCESS

- [ ] **Step 7: 提交**

```bash
git add core/src/main/java/cn/shang/charging/charge/rules/relativetime/RelativeTimeRule.java
git commit -m "[claude-code|glm-5|superpowers] refactor: RelativeTimeRule 删除延伸逻辑，新增截断标记

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>"
```

---

### Task 2.3: CompositeTimeRule 删除延伸逻辑

**Files:**
- Modify: `core/src/main/java/cn/shang/charging/charge/rules/compositetime/CompositeTimeRule.java`

- [ ] **Step 1: 删除 extendLastUnit 方法**

- [ ] **Step 2: 删除 findNextFreeRangeBoundary 方法**

- [ ] **Step 3: 删除 boundaryReferences 获取逻辑**

- [ ] **Step 4: 修改 calculationEndTime 赋值**

- [ ] **Step 5: 新增截断标记逻辑**

- [ ] **Step 6: 编译验证**

Run: `cd D:/dev/code/park-bill/charge && mvn clean compile -pl core -q`
Expected: BUILD SUCCESS

- [ ] **Step 7: 提交**

```bash
git add core/src/main/java/cn/shang/charging/charge/rules/compositetime/CompositeTimeRule.java
git commit -m "[claude-code|glm-5|superpowers] refactor: CompositeTimeRule 删除延伸逻辑，新增截断标记

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>"
```

---

## Phase 3: 简化 PromotionEngine 和 FreeTimeRangeMerger

### Task 3.1: FreeTimeRangeMerger 删除边界参考处理

**Files:**
- Modify: `core/src/main/java/cn/shang/charging/promotion/FreeTimeRangeMerger.java`

- [ ] **Step 1: 找到 preprocessRanges 方法**

查看当前实现

- [ ] **Step 2: 删除 boundaryReferences 相关逻辑**

删除将窗口外时段加入 boundaryReferences 的逻辑

- [ ] **Step 3: 编译验证**

Run: `cd D:/dev/code/park-bill/charge && mvn clean compile -pl core -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: 提交**

```bash
git add core/src/main/java/cn/shang/charging/promotion/FreeTimeRangeMerger.java
git commit -m "[claude-code|glm-5|superpowers] refactor: FreeTimeRangeMerger 删除边界参考处理

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>"
```

---

### Task 3.2: PromotionEngine 删除 boundaryReferences 传递

**Files:**
- Modify: `core/src/main/java/cn/shang/charging/promotion/PromotionEngine.java`

- [ ] **Step 1: 删除 boundaryReferences 获取和传递**

删除类似以下代码：
```java
List<FreeTimeRange> boundaryReferences = mergeResult.getBoundaryReferences();
```

以及：
```java
.boundaryReferences(boundaryReferences)
```

- [ ] **Step 2: 编译验证**

Run: `cd D:/dev/code/park-bill/charge && mvn clean compile -pl core -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: 提交**

```bash
git add core/src/main/java/cn/shang/charging/promotion/PromotionEngine.java
git commit -m "[claude-code|glm-5|superpowers] refactor: PromotionEngine 删除 boundaryReferences 传递

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>"
```

---

## Phase 4: ResultAssembler 新增 queryTime 处理

### Task 4.1: ResultAssembler 支持 queryTime 过滤

**Files:**
- Modify: `core/src/main/java/cn/shang/charging/settlement/ResultAssembler.java`

- [ ] **Step 1: 新增 queryTime 处理方法**

```java
/**
 * 根据 queryTime 过滤计费单元并重新计算金额
 */
private BillingResult filterByQueryTime(BillingResult result, LocalDateTime queryTime) {
    if (queryTime == null) {
        return result;
    }

    // 过滤单元：只保留 queryTime 之前完成的单元
    List<BillingUnit> filteredUnits = result.getUnits().stream()
            .filter(unit -> !unit.getEndTime().isAfter(queryTime))
            .toList();

    // 重新计算金额
    BigDecimal filteredAmount = filteredUnits.stream()
            .map(unit -> unit.getChargedAmount() != null ? unit.getChargedAmount() : BigDecimal.ZERO)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

    // 重新计算 effectiveFrom/effectiveTo
    LocalDateTime effectiveFrom = filteredUnits.isEmpty() ? null : filteredUnits.get(0).getBeginTime();
    LocalDateTime effectiveTo = filteredUnits.isEmpty() ? null : filteredUnits.get(filteredUnits.size() - 1).getEndTime();

    return result.toBuilder()
            .units(filteredUnits)
            .finalAmount(filteredAmount)
            .effectiveFrom(effectiveFrom)
            .effectiveTo(effectiveTo)
            .build();
}
```

- [ ] **Step 2: 在 assemble 方法中调用**

修改 assemble 方法：

```java
public BillingResult assemble(BillingRequest request,
                              List<BillingSegmentResult> segmentResultList) {
    // ... 现有逻辑 ...

    BillingResult result = BillingResult.builder()
            .units(allUnits)
            // ... 其他字段 ...
            .build();

    // 根据 queryTime 过滤
    LocalDateTime queryTime = request.getQueryTime() != null
            ? request.getQueryTime()
            : request.getCalcEndTime();

    return filterByQueryTime(result, queryTime);
}
```

- [ ] **Step 3: 编译验证**

Run: `cd D:/dev/code/park-bill/charge && mvn clean compile -pl core -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: 提交**

```bash
git add core/src/main/java/cn/shang/charging/settlement/ResultAssembler.java
git commit -m "[claude-code|glm-5|superpowers] feat: ResultAssembler 支持 queryTime 过滤

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>"
```

---

## Phase 5: 测试验证

### Task 5.1: 运行现有测试

- [ ] **Step 1: 运行全部测试**

Run: `cd D:/dev/code/park-bill/charge && mvn clean test -q`
Expected: Tests run: X, Failures: 0, Errors: 0

- [ ] **Step 2: 如有失败，修复后重新运行**

---

### Task 5.2: 更新 MEMORY.md

**Files:**
- Modify: `memory/MEMORY.md`

- [ ] **Step 1: 更新当前状态**

将"取消延伸功能重构"章节更新为已完成状态

- [ ] **Step 2: 提交**

```bash
git add memory/MEMORY.md
git commit -m "[claude-code|glm-5|superpowers] docs: 更新 MEMORY.md 任务状态

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>"
```

---

## 完成检查

- [ ] 所有测试通过
- [ ] 编译无警告
- [ ] MEMORY.md 已更新
- [ ] 所有提交信息格式正确