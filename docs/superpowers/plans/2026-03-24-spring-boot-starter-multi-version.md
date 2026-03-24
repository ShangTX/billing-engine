# Spring Boot Starter 多版本支持实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 创建两个独立的 Spring Boot Starter 模块（v3 和 v4），支持不同版本的 Spring Boot，长期并行维护。

**Architecture:** 两个 starter 共享 `billing-api` 核心模块，各自包含 Spring Boot 版本相关的自动配置代码，包名保持一致，用户迁移只需更换 artifactId。

**Tech Stack:** Maven, Spring Boot 3.2.0 (v3) / 4.0.0 (v4), JDK 21

---

## 文件结构

**修改文件:**
- `pom.xml` (根目录) - 更新 modules 列表
- `billing-spring-boot-starter/pom.xml` → `billing-v3-spring-boot-starter/pom.xml` - 更新 artifactId
- `CLAUDE.md` - 添加两个 starter 的文档说明

**重命名:**
- `billing-spring-boot-starter/` → `billing-v3-spring-boot-starter/`

**新建文件:**
- `billing-v4-spring-boot-starter/pom.xml`
- `billing-v4-spring-boot-starter/src/main/java/cn/shang/charging/spring/boot/autoconfigure/BillingAutoConfiguration.java`
- `billing-v4-spring-boot-starter/src/main/java/cn/shang/charging/spring/boot/autoconfigure/BillingProperties.java`
- `billing-v4-spring-boot-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`

---

## Task 1: 重命名现有 starter 模块目录

**Files:**
- Rename: `billing-spring-boot-starter/` → `billing-v3-spring-boot-starter/`

- [ ] **Step 1: 使用 git mv 重命名目录**

```bash
git mv billing-spring-boot-starter billing-v3-spring-boot-starter
```

Expected: 目录成功重命名，git 自动追踪重命名

---

## Task 2: 更新 v3 starter 的 pom.xml

**Files:**
- Modify: `billing-v3-spring-boot-starter/pom.xml`

- [ ] **Step 1: 更新 artifactId**

将第 13 行的 `<artifactId>billing-spring-boot-starter</artifactId>` 改为 `<artifactId>billing-v3-spring-boot-starter</artifactId>`

```xml
<artifactId>billing-v3-spring-boot-starter</artifactId>
<name>billing-v3-spring-boot-starter</name>
<description>Spring Boot 3.x Starter for billing engine (3.0.x - 3.4.x)</description>
```

- [ ] **Step 2: 验证 pom.xml 语法**

```bash
mvn validate -pl billing-v3-spring-boot-starter
```

Expected: BUILD SUCCESS

- [ ] **Step 3: 提交更改**

```bash
git add billing-v3-spring-boot-starter/pom.xml
git commit -m "[claude-code|glm-5|superpowers] refactor: 重命名 starter 为 billing-v3-spring-boot-starter"
```

---

## Task 3: 更新父 pom.xml 的 modules

**Files:**
- Modify: `pom.xml` (根目录)

- [ ] **Step 1: 更新 modules 列表**

将第 16 行的 `<module>billing-spring-boot-starter</module>` 替换为 v3 和 v4 模块：

```xml
<modules>
    <module>core</module>
    <module>billing-api</module>
    <module>billing-v3-spring-boot-starter</module>
    <module>billing-v4-spring-boot-starter</module>
    <module>bill-test</module>
</modules>
```

- [ ] **Step 2: 验证父 pom 语法**

```bash
mvn validate
```

Expected: BUILD SUCCESS

- [ ] **Step 3: 提交更改**

```bash
git add pom.xml
git commit -m "[claude-code|glm-5|superpowers] feat: 更新父 pom 模块列表"
```

---

## Task 4: 创建 billing-v4-spring-boot-starter 目录结构

**Files:**
- Create: `billing-v4-spring-boot-starter/` 目录结构

- [ ] **Step 1: 创建目录结构**

```bash
mkdir -p billing-v4-spring-boot-starter/src/main/java/cn/shang/charging/spring/boot/autoconfigure
mkdir -p billing-v4-spring-boot-starter/src/main/resources/META-INF/spring
```

Expected: 目录创建成功

---

## Task 5: 创建 v4 starter 的 pom.xml

**Files:**
- Create: `billing-v4-spring-boot-starter/pom.xml`

- [ ] **Step 1: 创建 pom.xml 文件**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">

    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>cn.shang</groupId>
        <artifactId>charge</artifactId>
        <version>0.0.1-SNAPSHOT</version>
    </parent>

    <artifactId>billing-v4-spring-boot-starter</artifactId>
    <name>billing-v4-spring-boot-starter</name>
    <description>Spring Boot 3.5/4.x Starter for billing engine (3.5.x - 4.x)</description>

    <properties>
        <spring-boot.version>4.0.0</spring-boot.version>
    </properties>

    <dependencies>
        <dependency>
            <groupId>cn.shang</groupId>
            <artifactId>billing-api</artifactId>
            <version>${project.version}</version>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-autoconfigure</artifactId>
            <version>${spring-boot.version}</version>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-configuration-processor</artifactId>
            <version>${spring-boot.version}</version>
            <optional>true</optional>
        </dependency>
    </dependencies>

</project>
```

- [ ] **Step 2: 验证 pom.xml 语法**

```bash
mvn validate -pl billing-v4-spring-boot-starter
```

Expected: BUILD SUCCESS

---

## Task 6: 创建 v4 starter 的 BillingAutoConfiguration.java

**Files:**
- Create: `billing-v4-spring-boot-starter/src/main/java/cn/shang/charging/spring/boot/autoconfigure/BillingAutoConfiguration.java`

- [ ] **Step 1: 创建 BillingAutoConfiguration.java**

```java
package cn.shang.charging.spring.boot.autoconfigure;

import cn.shang.charging.billing.BillingCalculator;
import cn.shang.charging.billing.BillingConfigResolver;
import cn.shang.charging.billing.BillingService;
import cn.shang.charging.billing.SegmentBuilder;
import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.charge.rules.BillingRuleRegistry;
import cn.shang.charging.charge.rules.compositetime.CompositeTimeRule;
import cn.shang.charging.charge.rules.daynight.DayNightRule;
import cn.shang.charging.charge.rules.relativetime.RelativeTimeRule;
import cn.shang.charging.promotion.FreeMinuteAllocator;
import cn.shang.charging.promotion.FreeTimeRangeMerger;
import cn.shang.charging.promotion.PromotionEngine;
import cn.shang.charging.promotion.rules.minutes.FreeMinutesPromotionRule;
import cn.shang.charging.promotion.rules.PromotionRuleRegistry;
import cn.shang.charging.settlement.ResultAssembler;
import cn.shang.charging.wrapper.BillingTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 计费引擎自动配置
 */
@Configuration
@ConditionalOnClass(BillingService.class)
@EnableConfigurationProperties(BillingProperties.class)
public class BillingAutoConfiguration {

    /**
     * 计费规则注册表（自动注册内置规则）
     */
    @Bean
    @ConditionalOnMissingBean
    public BillingRuleRegistry billingRuleRegistry() {
        BillingRuleRegistry registry = new BillingRuleRegistry();
        // 注册内置规则
        registry.register(BConstants.ChargeRuleType.RELATIVE_TIME, new RelativeTimeRule());
        // DAY_NIGHT 和 COMPOSITE_TIME 已在 BillingRuleRegistry 构造函数中注册
        return registry;
    }

    /**
     * 优惠规则注册表（自动注册内置规则）
     */
    @Bean
    @ConditionalOnMissingBean
    public PromotionRuleRegistry promotionRuleRegistry() {
        PromotionRuleRegistry registry = new PromotionRuleRegistry();
        registry.register(BConstants.PromotionRuleType.FREE_MINUTES, new FreeMinutesPromotionRule());
        return registry;
    }

    /**
     * 优惠引擎
     */
    @Bean
    @ConditionalOnMissingBean
    public PromotionEngine promotionEngine(
            BillingConfigResolver configResolver,
            FreeTimeRangeMerger freeTimeRangeMerger,
            FreeMinuteAllocator freeMinuteAllocator,
            PromotionRuleRegistry promotionRuleRegistry) {
        return new PromotionEngine(configResolver, freeTimeRangeMerger, freeMinuteAllocator, promotionRuleRegistry);
    }

    /**
     * 免费时段合并器
     */
    @Bean
    @ConditionalOnMissingBean
    public FreeTimeRangeMerger freeTimeRangeMerger() {
        return new FreeTimeRangeMerger();
    }

    /**
     * 免费分钟分配器
     */
    @Bean
    @ConditionalOnMissingBean
    public FreeMinuteAllocator freeMinuteAllocator() {
        return new FreeMinuteAllocator();
    }

    /**
     * 计费服务
     */
    @Bean
    @ConditionalOnMissingBean
    public BillingService billingService(
            BillingConfigResolver configResolver,
            BillingRuleRegistry billingRuleRegistry,
            PromotionEngine promotionEngine) {
        return new BillingService(
                new SegmentBuilder(),
                configResolver,
                promotionEngine,
                new BillingCalculator(billingRuleRegistry),
                new ResultAssembler());
    }

    /**
     * 计费模板（便捷 API）
     */
    @Bean
    @ConditionalOnMissingBean
    public BillingTemplate billingTemplate(
            BillingService billingService,
            BillingConfigResolver configResolver) {
        return new BillingTemplate(billingService, configResolver);
    }
}
```

---

## Task 7: 创建 v4 starter 的 BillingProperties.java

**Files:**
- Create: `billing-v4-spring-boot-starter/src/main/java/cn/shang/charging/spring/boot/autoconfigure/BillingProperties.java`

- [ ] **Step 1: 创建 BillingProperties.java**

```java
package cn.shang.charging.spring.boot.autoconfigure;

import cn.shang.charging.billing.pojo.BConstants;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 计费配置属性
 * 仅包含 scheme 元信息，具体规则参数由调用方通过 BillingConfigResolver 实现
 */
@Data
@ConfigurationProperties(prefix = "billing")
public class BillingProperties {

    /**
     * schemeId → scheme 元信息
     */
    private Map<String, SchemeMeta> schemes = new LinkedHashMap<>();

    /**
     * Scheme 元信息
     */
    @Data
    public static class SchemeMeta {
        /**
         * 规则类型: DAY_NIGHT, RELATIVE_TIME, COMPOSITE_TIME
         */
        private String ruleType;

        /**
         * 计费模式: CONTINUOUS, UNIT_BASED
         */
        private BConstants.BillingMode billingMode;

        /**
         * 简化计算阈值（连续无优惠周期数超过此值时启用简化，0 表示禁用）
         */
        private int simplifiedThreshold = 0;
    }
}
```

---

## Task 8: 创建 v4 starter 的 AutoConfiguration.imports

**Files:**
- Create: `billing-v4-spring-boot-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`

- [ ] **Step 1: 创建 AutoConfiguration.imports 文件**

```
cn.shang.charging.spring.boot.autoconfigure.BillingAutoConfiguration
```

---

## Task 9: 验证项目构建

**Files:**
- Verify: 整个项目构建

- [ ] **Step 1: 运行完整构建**

```bash
mvn clean install
```

Expected: BUILD SUCCESS，所有模块编译通过

- [ ] **Step 2: 检查生成的 jar 文件**

```bash
ls -la billing-v3-spring-boot-starter/target/*.jar
ls -la billing-v4-spring-boot-starter/target/*.jar
```

Expected: 两个 starter 的 jar 文件都已生成

- [ ] **Step 3: 提交 v4 starter 代码**

```bash
git add billing-v4-spring-boot-starter/
git commit -m "[claude-code|glm-5|superpowers] feat: 新增 billing-v4-spring-boot-starter 模块"
```

---

## Task 10: 更新 CLAUDE.md 文档

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: 在 Architecture 部分添加 starter 说明**

在 `## Architecture` 部分的模块表格后添加：

```markdown
### Spring Boot Starters

提供两个独立的 Spring Boot Starter，支持不同版本的 Spring Boot：

| Starter | Spring Boot | JDK | 状态 |
|---------|-------------|-----|------|
| billing-v3-spring-boot-starter | 3.0.x - 3.4.x | 21 | 活跃维护 |
| billing-v4-spring-boot-starter | 3.5.x - 4.x | 21/23/25 | 活跃维护 |

**使用方式：**

```xml
<!-- v3 版本 (Spring Boot 3.0.x - 3.4.x) -->
<dependency>
    <groupId>cn.shang</groupId>
    <artifactId>billing-v3-spring-boot-starter</artifactId>
</dependency>

<!-- v4 版本 (Spring Boot 3.5.x - 4.x) -->
<dependency>
    <groupId>cn.shang</groupId>
    <artifactId>billing-v4-spring-boot-starter</artifactId>
</dependency>
```

**迁移说明：** 两个 starter API 完全相同，迁移只需更换 Maven 依赖的 artifactId。
```

- [ ] **Step 2: 提交文档更新**

```bash
git add CLAUDE.md
git commit -m "[claude-code|glm-5|superpowers] docs: 添加 Spring Boot Starter 多版本文档"
```

---

## Task 11: 最终验证

- [ ] **Step 1: 运行完整测试**

```bash
mvn clean install
```

Expected: BUILD SUCCESS

- [ ] **Step 2: 检查 git 状态**

```bash
git status
git log --oneline -5
```

Expected: 工作区干净，所有更改已提交

---

## 提交记录汇总

| 序号 | 提交信息 |
|------|---------|
| 1 | `[claude-code|glm-5|superpowers] refactor: 重命名 starter 为 billing-v3-spring-boot-starter` |
| 2 | `[claude-code|glm-5|superpowers] feat: 更新父 pom 模块列表` |
| 3 | `[claude-code|glm-5|superpowers] feat: 新增 billing-v4-spring-boot-starter 模块` |
| 4 | `[claude-code|glm-5|superpowers] docs: 添加 Spring Boot Starter 多版本文档` |