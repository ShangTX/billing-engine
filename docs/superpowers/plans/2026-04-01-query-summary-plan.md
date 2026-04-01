# QuerySummary 实施计划

## 任务概览

| Task | 文件 | 操作 |
|------|------|------|
| 1 | `QuerySummary.java` | 新增轻量级查询摘要类 |
| 2 | `BillingResultViewer.java` | 新增 `createQuerySummary()` 方法 |
| 3 | `CalculationWithQueryResult.java` | 修改 `queryResult` 类型 |
| 4 | `BillingTemplate.java` | 修改 `calculateWithQuery()` 实现 |
| 5 | 测试验证 | 确保功能正确 |

---

## Task 1: 新增 QuerySummary 类

**文件**: `billing-api/src/main/java/cn/shang/charging/wrapper/QuerySummary.java`

```java
package cn.shang.charging.wrapper;

import cn.shang.charging.promotion.pojo.PromotionUsage;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 查询结果摘要
 * 轻量级结构，用索引代替复制单元列表
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuerySummary {
    /**
     * 查询单元索引（0-based）
     * -1 表示无对应单元（units 为空）
     */
    private int unitIndex;

    /**
     * 查询时间点的累计金额
     * = units[unitIndex].accumulatedAmount
     * 当 unitIndex = -1 时为 0
     */
    private BigDecimal amount;

    /**
     * 有效时间范围起点
     * = units[0].beginTime
     */
    private LocalDateTime effectiveFrom;

    /**
     * 有效时间范围终点
     * = units[unitIndex].endTime
     */
    private LocalDateTime effectiveTo;

    /**
     * 查询时间点
     */
    private LocalDateTime queryTime;

    /**
     * 截取的优惠使用记录
     */
    private List<PromotionUsage> promotionUsages;
}
```

---

## Task 2: 新增 BillingResultViewer.createQuerySummary()

**文件**: `billing-api/src/main/java/cn/shang/charging/wrapper/BillingResultViewer.java`

新增方法：

```java
/**
 * 创建查询摘要（轻量级，用索引代替复制）
 *
 * @param result    完整计算结果
 * @param queryTime 查询时间点
 * @return 查询摘要
 * @throws IllegalArgumentException 当 queryTime <= units[0].beginTime
 */
public QuerySummary createQuerySummary(BillingResult result, LocalDateTime queryTime) {
    if (result == null || queryTime == null) {
        throw new IllegalArgumentException("result 和 queryTime 不能为 null");
    }

    List<BillingUnit> units = result.getUnits();
    if (units == null || units.isEmpty()) {
        return QuerySummary.builder()
            .unitIndex(-1)
            .amount(BigDecimal.ZERO)
            .queryTime(queryTime)
            .promotionUsages(List.of())
            .build();
    }

    // 边界检查：queryTime <= 第一个单元的 beginTime
    LocalDateTime firstBeginTime = units.get(0).getBeginTime();
    if (queryTime.compareTo(firstBeginTime) <= 0) {
        throw new IllegalArgumentException(
            "查询时间过早，无对应计费单元。queryTime=" + queryTime +
            ", firstUnitBeginTime=" + firstBeginTime);
    }

    // 查找单元：beginTime < queryTime <= endTime
    int unitIndex = findUnitIndex(units, queryTime);

    // 获取金额
    BigDecimal amount = unitIndex >= 0
        ? units.get(unitIndex).getAccumulatedAmount()
        : BigDecimal.ZERO;

    // 截取优惠使用记录
    List<PromotionUsage> filteredUsages = filterUsages(result.getPromotionUsages(), queryTime);

    return QuerySummary.builder()
        .unitIndex(unitIndex)
        .amount(amount)
        .effectiveFrom(units.get(0).getBeginTime())
        .effectiveTo(unitIndex >= 0 ? units.get(unitIndex).getEndTime() : null)
        .queryTime(queryTime)
        .promotionUsages(filteredUsages)
        .build();
}

/**
 * 查找满足 beginTime < queryTime <= endTime 的单元索引
 */
private int findUnitIndex(List<BillingUnit> units, LocalDateTime queryTime) {
    for (int i = 0; i < units.size(); i++) {
        BillingUnit unit = units.get(i);
        LocalDateTime beginTime = unit.getBeginTime();
        LocalDateTime endTime = unit.getEndTime();

        if (beginTime != null && endTime != null &&
            beginTime.isBefore(queryTime) && !queryTime.isAfter(endTime)) {
            return i;
        }
    }

    // queryTime > 最后一个单元的 endTime，返回最后一个索引
    return units.size() - 1;
}
```

---

## Task 3: 修改 CalculationWithQueryResult

**文件**: `billing-api/src/main/java/cn/shang/charging/wrapper/CalculationWithQueryResult.java`

```java
package cn.shang.charging.wrapper;

import cn.shang.charging.billing.pojo.BillingResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 计算结果与查询摘要
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CalculationWithQueryResult {
    /**
     * 完整计算结果
     * - 用于 CONTINUE 进度存储
     * - 用于费用稳定窗口判断
     */
    private BillingResult calculationResult;

    /**
     * 查询结果摘要（轻量级）
     * - 用索引代替复制单元列表
     * - 用于展示给用户
     */
    private QuerySummary queryResult;
}
```

---

## Task 4: 修改 BillingTemplate.calculateWithQuery()

**文件**: `billing-api/src/main/java/cn/shang/charging/wrapper/BillingTemplate.java`

修改方法：

```java
/**
 * 计算并返回两种结果
 *
 * @param request   计费请求
 * @param queryTime 查询时间点
 * @return 计算结果和查询摘要
 * @throws IllegalArgumentException 当 queryTime <= units[0].beginTime
 */
public CalculationWithQueryResult calculateWithQuery(BillingRequest request, LocalDateTime queryTime) {
    BillingResult calculationResult = billingService.calculate(request);
    QuerySummary queryResult = resultViewer.createQuerySummary(calculationResult, queryTime);
    return new CalculationWithQueryResult(calculationResult, queryResult);
}
```

---

## Task 5: 测试验证

### 单元测试场景

| 场景 | 输入 | 预期输出 |
|------|------|---------|
| 正常查找 | queryTime 在中间单元 | 返回正确索引和金额 |
| 最后单元之后 | queryTime > last.endTime | 返回 last 索引 |
| 第一个单元之前 | queryTime <= first.beginTime | IllegalArgumentException |
| 空单元列表 | units = [] | unitIndex=-1, amount=0 |
| 优惠截取 | usage 跨越 queryTime | 截断到 queryTime |

### 运行测试

```bash
mvn clean test -q
```

---

## 提交信息

```
[claude-code|glm-5|superpowers] feat: QuerySummary 轻量级查询摘要
```