package cn.shang.charging.util;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * 类型转换工具类
 * 用于处理 Map<String, Object> 序列化后的类型恢复问题
 * <p>
 * 当 Map 通过 JSON 序列化/反序列化后，原始类型信息丢失：
 * - LocalDateTime → String (如 "2023-09-19T10:00:00")
 * - BigDecimal → String 或 Double
 * - Integer → Number
 * <p>
 * 本工具类提供自适应转换，兼容多种输入格式
 */
public final class TypeConversionUtil {

    private static final DateTimeFormatter ISO_DATETIME_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private TypeConversionUtil() {
        // 工具类，禁止实例化
    }

    /**
     * 转换为 LocalDateTime
     * 支持：String (ISO格式)、Long (毫秒时间戳)、LocalDateTime
     *
     * @param value 输入值
     * @return LocalDateTime，如果无法转换返回 null
     */
    public static LocalDateTime toLocalDateTime(Object value) {
        if (value == null) {
            return null;
        }

        // 已经是目标类型
        if (value instanceof LocalDateTime) {
            return (LocalDateTime) value;
        }

        // String 类型：尝试 ISO 格式解析
        if (value instanceof String) {
            String str = (String) value;
            try {
                return LocalDateTime.parse(str, ISO_DATETIME_FORMATTER);
            } catch (DateTimeParseException e) {
                // 尝试其他常见格式
                try {
                    // 兼容 "yyyy-MM-dd HH:mm:ss" 格式
                    return LocalDateTime.parse(str.replace(" ", "T"));
                } catch (DateTimeParseException e2) {
                    return null;
                }
            }
        }

        // Long/Number 类型：作为毫秒时间戳
        if (value instanceof Number) {
            long timestamp = ((Number) value).longValue();
            return LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneId.systemDefault());
        }

        return null;
    }

    /**
     * 转换为 BigDecimal
     * 支持：String、Double、Float、Long、Integer、BigDecimal
     *
     * @param value 输入值
     * @return BigDecimal，如果无法转换返回 null
     */
    public static BigDecimal toBigDecimal(Object value) {
        if (value == null) {
            return null;
        }

        // 已经是目标类型
        if (value instanceof BigDecimal) {
            return (BigDecimal) value;
        }

        // String 类型
        if (value instanceof String) {
            String str = (String) value;
            if (str.isEmpty()) {
                return null;
            }
            try {
                return new BigDecimal(str);
            } catch (NumberFormatException e) {
                return null;
            }
        }

        // Number 类型（Double、Float、Long、Integer 等）
        if (value instanceof Number) {
            // 注意：Double/Float 转 BigDecimal 可能丢失精度
            // 建议使用 String 构造，但对于序列化后的数据，直接转换通常足够
            return BigDecimal.valueOf(((Number) value).doubleValue());
        }

        return null;
    }

    /**
     * 转换为 Integer
     * 支持：String、Number、Integer
     *
     * @param value 输入值
     * @return Integer，如果无法转换返回 null
     */
    public static Integer toInteger(Object value) {
        if (value == null) {
            return null;
        }

        // 已经是目标类型
        if (value instanceof Integer) {
            return (Integer) value;
        }

        // String 类型
        if (value instanceof String) {
            String str = (String) value;
            if (str.isEmpty()) {
                return null;
            }
            try {
                return Integer.parseInt(str);
            } catch (NumberFormatException e) {
                return null;
            }
        }

        // Number 类型
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }

        return null;
    }

    /**
     * 转换为 Long
     * 支持：String、Number、Long
     *
     * @param value 输入值
     * @return Long，如果无法转换返回 null
     */
    public static Long toLong(Object value) {
        if (value == null) {
            return null;
        }

        // 已经是目标类型
        if (value instanceof Long) {
            return (Long) value;
        }

        // String 类型
        if (value instanceof String) {
            String str = (String) value;
            if (str.isEmpty()) {
                return null;
            }
            try {
                return Long.parseLong(str);
            } catch (NumberFormatException e) {
                return null;
            }
        }

        // Number 类型
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }

        return null;
    }
}