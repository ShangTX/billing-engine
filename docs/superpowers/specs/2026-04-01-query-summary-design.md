# QuerySummary 设计文档

## 背景

当前 `CalculationWithQueryResult` 返回两个完整的 `BillingResult`：
- `calculationResult` - 完整计算结果
- `queryResult` - 截取后的结果（复制单元列表）

这导致：
1. 内存浪费：截取结果复制了部分单元
2. 金额重复计算：`queryResult.finalAmount` 可直接从 `accumulatedAmount` 获取

## 解决方案

引入 `QuerySummary` 替代截取的 `BillingResult`，用索引代替复制。

### 核心设计

```java
class CalculationWithQueryResult {
    BillingResult calculationResult;  // 完整计算结果（不变）
    QuerySummary queryResult;         // 新增：轻量级查询摘要
}

class QuerySummary {
    int unitIndex;                    // 查询单元索引（0-based，-1 表示无单元）
    BigDecimal amount;                // = units[unitIndex].accumulatedAmount
    LocalDateTime effectiveFrom;      // = units[0].beginTime
    LocalDateTime effectiveTo;        // = units[unitIndex].endTime
    LocalDateTime queryTime;          // 查询时间点
    List<PromotionUsage> promotionUsages;  // 截取的优惠使用记录
}
```

### 单元查找逻辑

查找满足 `beginTime < queryTime <= endTime` 的单元：

```
遍历 units（按时间顺序）：
  找到第一个满足条件的单元 → 返回其索引

边界处理：
  queryTime <= units[0].beginTime → IllegalArgumentException("查询时间过早，无对应计费单元")
  queryTime > units[last].endTime → 返回 last 索引（最后一个单元）
  units 为空                    → 返回 -1，amount = 0
```

### 优惠截取逻辑

与原来一致：
- 按 `usedTo <= queryTime` 截取
- 跨越 `queryTime` 的使用记录截断到 `queryTime`

## 文件变更

| 文件 | 操作 | 说明 |
|------|------|------|
| `QuerySummary.java` | 新增 | 轻量级查询摘要类 |
| `BillingResultViewer.java` | 修改 | 新增 `createQuerySummary()` 方法 |
| `BillingTemplate.java` | 修改 | `calculateWithQuery()` 返回类型不变，内部使用 `QuerySummary` |
| `CalculationWithQueryResult.java` | 修改 | `queryResult` 类型改为 `QuerySummary` |

## 向后兼容性

**不兼容变更**：
- `CalculationWithQueryResult.queryResult` 类型从 `BillingResult` 变为 `QuerySummary`

**迁移路径**：
- 现有调用方如需完整截取结果，可继续使用 `BillingResultViewer.viewAtTime()`
- `BillingResultViewer` 保留不变

## 使用示例

```java
// 计算并查询
CalculationWithQueryResult result = billingTemplate.calculateWithQuery(request, queryTime);

// 获取查询金额（直接从摘要获取）
BigDecimal queryAmount = result.getQueryResult().getAmount();

// 获取查询单元（从完整结果中按索引获取）
int index = result.getQueryResult().getUnitIndex();
BillingUnit queryUnit = result.getCalculationResult().getUnits().get(index);

// 如需完整截取结果（兼容旧代码）
BillingResult fullView = new BillingResultViewer()
    .viewAtTime(result.getCalculationResult(), queryTime);
```

## 优势

1. **内存节省**：不再复制单元列表
2. **性能提升**：金额直接从 `accumulatedAmount` 获取，无需累加
3. **语义清晰**：索引明确指向"当前计费位置"