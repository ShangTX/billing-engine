# 实现金额减免和折扣优惠

---
id: TODO-20260508-001
type: feature
priority: P2
status: done
source_git: 1206a30
created_at: 2026-05-08
completed_at: 2026-05-18
---

## 背景

`BConstants.PromotionType` 中已有 `AMOUNT` 和 `DISCOUNT`，文档中也标注为待实现，但当前主要优惠能力集中在 `FREE_RANGE` 和 `FREE_MINUTES`。

## 决策结果

1. **优惠叠加顺序**：方案 A（总和扣除 + 最优折扣 + 先折扣后减免）
2. **应用时机**：方案 C（结果层，不影响规则计算）
3. **PromotionGrant 扩展**：添加 `amount` 和 `discountRate` 字段

## 实施进展（2026-05-18 完成）

### 已完成

1. **PromotionGrant 扩展**
   - 添加 `amount` 字段（金额减免额度）
   - 添加 `discountRate` 字段（折扣率，如 0.8 表示 8 折）

2. **PromotionAggregate 扩展**
   - 添加 `amountDiscounts` 列表
   - 添加 `totalAmountDiscount` 总减免金额
   - 添加 `bestDiscountRate` 最优折扣率
   - 添加 `hasAmountDiscount()` 和 `hasRateDiscount()` 判断方法

3. **PromotionEngine 改造**
   - 处理 AMOUNT 类型优惠
   - 处理 DISCOUNT 类型优惠
   - 计算 AMOUNT 总和
   - 计算 DISCOUNT 最优折扣

4. **BillingSegmentResult 扩展**
   - 添加 `originalAmount`（折扣前金额）
   - 添加 `discountSavedAmount`（折扣优惠金额）
   - 添加 `amountDiscount`（金额减免总额）
   - 添加 `finalAmount`（最终实收金额）

5. **AmountDiscountApplier 工具类**
   - 实现优惠叠加逻辑（先折扣后减免）
   - 应用金额上限约束
   - 计算最终金额

## 验收标准

- ✓ 可以通过规则或外部输入应用金额减免
- ✓ 可以通过规则或外部输入应用折扣优惠
- ✓ 与现有 FREE_RANGE、FREE_MINUTES 组合时结果可解释
- ✓ 优惠叠加顺序明确（先折扣后减免，总和扣除）
- ✓ 文档同步完成

## 相关文件

- `core/src/main/java/cn/shang/charging/billing/pojo/BConstants.java`
- `core/src/main/java/cn/shang/charging/promotion/pojo/PromotionGrant.java`
- `core/src/main/java/cn/shang/charging/promotion/pojo/PromotionAggregate.java`
- `core/src/main/java/cn/shang/charging/promotion/PromotionEngine.java`
- `core/src/main/java/cn/shang/charging/promotion/AmountDiscountApplier.java`
- `core/src/main/java/cn/shang/charging/billing/pojo/BillingSegmentResult.java`
