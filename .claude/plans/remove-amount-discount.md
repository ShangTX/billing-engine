# 移除 AMOUNT/DISCOUNT 优惠类型

## 背景与现状

- AMOUNT/DISCOUNT 是金额减免/折扣优惠类型，设计为"事后结算"（`AmountDiscountApplier` 在最终结果上应用）。
- **`AmountDiscountApplier` 从未被调用**（死代码）：Grep `AmountDiscountApplier` 只在自身定义 + 文档，无代码调用 `apply`/`calculateFinalAmount`。
- **`ResultAssembler`** 用各分段 `chargedAmount` 之和设置 `BillingResult.finalAmount`（[第 59-62 行](core/src/main/java/cn/shang/charging/settlement/ResultAssembler.java:59)），**不读** `BillingSegmentResult.finalAmount`。
- **`BillingSegmentResult` 的金额折扣 4 字段**（originalAmount/discountSavedAmount/amountDiscount/finalAmount）只被 `AmountDiscountApplier` 设置，从未被读取。
- 用户决策：AMOUNT/DISCOUNT 应在业务系统处理，不在计费库处理。
- 无测试用 `PromotionType.AMOUNT/DISCOUNT`；`PromotionRuleType` 无 AMOUNT/DISCOUNT（无独立规则实现）；Spring Boot starter 未注册。

## 移除范围

### 核心代码（10 文件）

1. **删除** `core/.../promotion/AmountDiscountApplier.java`（死代码）

2. **`PromotionEngine.java`**：移除 AMOUNT/DISCOUNT 处理
   - `amountDiscounts` 列表 + AMOUNT/DISCOUNT grant 收集（规则 + 外部）
   - `calculateTotalAmountDiscount`/`calculateBestDiscountRate` 调用 + builder 3 字段
   - `convertAmountFromGrant`/`convertDiscountFromGrant`/`calculateTotalAmountDiscount`/`calculateBestDiscountRate` 4 个私有方法
   - 类注释移除 AMOUNT/DISCOUNT 描述

3. **`PromotionAggregate.java`**：移除
   - `amountDiscounts`/`totalAmountDiscount`/`bestDiscountRate` 3 字段
   - `AmountDiscount` 内部类
   - `hasAmountDiscount`/`hasRateDiscount` 方法
   - `isEmpty`/`hasMultiplePromotionTypes`/`hasSinglePromotionType` 中的 amountDiscounts 引用
   - 类注释移除"金额减免、折扣"描述

4. **`PromotionGrant.java`**：移除 `amount`/`discountRate` 字段 + 注释

5. **`ExternalPromotionPool.java`**：移除 AMOUNT/DISCOUNT 全量透传
   - `amountDiscountGrants` 字段 + `case AMOUNT, DISCOUNT` + `clear()` + `remaining()` 透传
   - 注释移除 AMOUNT/DISCOUNT 描述

6. **`BConstants.java`**：`PromotionType` 移除 `AMOUNT`/`DISCOUNT` 枚举值

7. **`PromotionAggregateUtil.exclude`**：移除 `.amountDiscounts`/`.totalAmountDiscount`/`.bestDiscountRate` 保留

8. **`PromotionUsage.java`**：`type` 字段注释移除 AMOUNT/DISCOUNT

9. **`BillingTemplate.java`**：`roundExternalPromotions` 注释移除 AMOUNT/DISCOUNT

10. **`BillingSegmentResult.java`**：移除第五部分"金额折扣优惠结果"4 字段（originalAmount/discountSavedAmount/amountDiscount/finalAmount）

### 文档（一致性更新，后续可补）

- `docs/USER_GUIDE.md`、`docs/billing-engine-capabilities.md`/`-zh.md`、`docs/billing-engine-current-flow-zh.md`、`docs/billing-engine-calculation-flow-zh.md`、`WORK_HANDOFF.md`
- 这些文档提到 AMOUNT/DISCOUNT 与 `AmountDiscountApplier`，移除后需同步。本次先聚焦代码，文档单独提交。

## 不做

- 不改 `BillingResult.finalAmount`（顶层最终金额 = chargedAmount 之和，与 AMOUNT/DISCOUNT 无关，保留）
- 不改 `BillingUnit.originalAmount` / `HomogeneousSegment.originalAmount`（计费单元原价，与 AMOUNT/DISCOUNT 无关，保留）
- 不改 `PromotionRuleType`（本就无 AMOUNT/DISCOUNT）

## 验证

- `mvn install -pl core -am -DskipTests` 编译通过
- `mvn test` 全量测试通过（无测试用 AMOUNT/DISCOUNT，预期无回归）
- 确认无残留 `AMOUNT`/`DISCOUNT`/`amountDiscount`/`discountRate` 引用（文档除外）

## 涉及文件

**删除**：`AmountDiscountApplier.java`

**修改**（9 文件）：
- `core/.../promotion/PromotionEngine.java`
- `core/.../promotion/pojo/PromotionAggregate.java`
- `core/.../promotion/pojo/PromotionGrant.java`
- `core/.../promotion/ExternalPromotionPool.java`
- `core/.../billing/pojo/BConstants.java`
- `core/.../promotion/PromotionAggregateUtil.java`
- `core/.../promotion/pojo/PromotionUsage.java`
- `billing-api/.../wrapper/BillingTemplate.java`
- `core/.../billing/pojo/BillingSegmentResult.java`
