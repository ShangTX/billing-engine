# Spring Boot Starter 多版本支持设计

## 概述

创建两个独立的 Spring Boot Starter 模块，分别支持不同版本的 Spring Boot，长期并行维护。

---

## 模块结构

```
charge/
├── core/                              # 核心计费引擎（不变）
├── billing-api/                       # 便捷 API（不变）
├── billing-v3-spring-boot-starter/    # 重命名自 billing-spring-boot-starter
│   ├── pom.xml                        # Spring Boot 3.x（3.5 以下），JDK 21
│   └── src/main/java/.../autoconfigure/
│       ├── BillingAutoConfiguration.java
│       └── BillingProperties.java
├── billing-v4-spring-boot-starter/    # 新建
│   ├── pom.xml                        # Spring Boot 3.5/4.x，JDK 21/23/25
│   └── src/main/java/.../autoconfigure/
│       ├── BillingAutoConfiguration.java
│       └── BillingProperties.java
└── bill-test/                         # 测试模块（不变）
```

---

## 版本范围

| Starter | Spring Boot | JDK | 状态 |
|---------|-------------|-----|------|
| billing-v3-spring-boot-starter | 3.x（3.5 以下） | 21 | 活跃维护 |
| billing-v4-spring-boot-starter | 3.5/4.x | 21/23/25 | 活跃维护 |

---

## 设计决策

### 1. 共享核心，独立 starter

两个 starter 都依赖 `billing-api` 模块，各自只包含 Spring Boot 版本相关的自动配置代码。

**优势：**
- 保证功能一致性
- 避免代码重复
- 降低维护成本

### 2. 包名保持一致

两个 starter 使用相同的包名 `cn.shang.charging.spring.boot.autoconfigure`。

**用户迁移：** 只需更换 Maven 依赖的 artifactId，代码无需修改。

### 3. 长期并行维护

两个版本都会持续迭代，修复 bug、添加新功能。

---

## 依赖配置

### billing-v3-spring-boot-starter

```xml
<artifactId>billing-v3-spring-boot-starter</artifactId>
<properties>
    <spring-boot.version>3.2.0</spring-boot.version>
    <java.version>21</java.version>
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
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-configuration-processor</artifactId>
        <optional>true</optional>
    </dependency>
</dependencies>
```

### billing-v4-spring-boot-starter

```xml
<artifactId>billing-v4-spring-boot-starter</artifactId>
<properties>
    <spring-boot.version>4.0.0</spring-boot.version>
    <java.version>21</java.version>
    <maven.compiler.source>21</maven.compiler.source>
    <maven.compiler.target>21</maven.compiler.target>
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
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-configuration-processor</artifactId>
        <optional>true</optional>
    </dependency>
</dependencies>
```

---

## 自动配置类

两个 starter 的 `BillingAutoConfiguration` 和 `BillingProperties` 功能相同，代码基本一致。

### BillingAutoConfiguration

主要功能：
- 注册 `BillingRuleRegistry`（内置计费规则）
- 注册 `PromotionRuleRegistry`（内置优惠规则）
- 注册 `PromotionEngine`、`FreeTimeRangeMerger`、`FreeMinuteAllocator`
- 注册 `BillingService`
- 注册 `BillingTemplate`

### BillingProperties

主要功能：
- Scheme 元配置（未来扩展）
- 目前保持与现有实现一致

---

## 改动清单

| 操作 | 文件/目录 | 说明 |
|------|----------|------|
| 重命名 | `billing-spring-boot-starter/` → `billing-v3-spring-boot-starter/` | 现有模块改名 |
| 修改 | `billing-v3-spring-boot-starter/pom.xml` | 更新 artifactId |
| 新建 | `billing-v4-spring-boot-starter/` | 新 starter 模块 |
| 新建 | `billing-v4-spring-boot-starter/pom.xml` | Spring Boot 4.x 配置 |
| 新建 | `billing-v4-spring-boot-starter/src/.../BillingAutoConfiguration.java` | 自动配置类 |
| 新建 | `billing-v4-spring-boot-starter/src/.../BillingProperties.java` | 属性配置类 |
| 新建 | `billing-v4-spring-boot-starter/src/.../META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` | 自动配置注册 |
| 修改 | `pom.xml`（根目录） | 添加新模块 |
| 修改 | `CLAUDE.md` | 添加两个 starter 的文档说明 |

---

## 用户迁移说明

两个 starter API 完全相同，迁移只需更换 Maven 依赖：

```xml
<!-- v3 版本 -->
<dependency>
    <groupId>cn.shang</groupId>
    <artifactId>billing-v3-spring-boot-starter</artifactId>
</dependency>

<!-- v4 版本 -->
<dependency>
    <groupId>cn.shang</groupId>
    <artifactId>billing-v4-spring-boot-starter</artifactId>
</dependency>
```