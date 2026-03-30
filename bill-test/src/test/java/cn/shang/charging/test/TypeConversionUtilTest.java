package cn.shang.charging.test;

import cn.shang.charging.util.TypeConversionUtil;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TypeConversionUtil 单元测试
 * 验证序列化后类型转换的正确性
 */
class TypeConversionUtilTest {

    @Test
    void testToLocalDateTime_fromString() {
        // ISO 格式
        LocalDateTime result = TypeConversionUtil.toLocalDateTime("2023-09-19T10:30:00");
        assertNotNull(result);
        assertEquals(2023, result.getYear());
        assertEquals(9, result.getMonthValue());
        assertEquals(19, result.getDayOfMonth());
        assertEquals(10, result.getHour());
        assertEquals(30, result.getMinute());
    }

    @Test
    void testToLocalDateTime_fromLocalDateTime() {
        LocalDateTime input = LocalDateTime.of(2023, 9, 19, 10, 30, 0);
        LocalDateTime result = TypeConversionUtil.toLocalDateTime(input);
        assertSame(input, result);
    }

    @Test
    void testToLocalDateTime_fromNumber() {
        // 毫秒时间戳
        LocalDateTime input = LocalDateTime.of(2023, 9, 19, 10, 30, 0);
        long timestamp = input.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        LocalDateTime result = TypeConversionUtil.toLocalDateTime(timestamp);
        assertNotNull(result);
        assertEquals(2023, result.getYear());
    }

    @Test
    void testToLocalDateTime_fromNull() {
        assertNull(TypeConversionUtil.toLocalDateTime(null));
    }

    @Test
    void testToBigDecimal_fromString() {
        BigDecimal result = TypeConversionUtil.toBigDecimal("123.456");
        assertNotNull(result);
        assertEquals(0, new BigDecimal("123.456").compareTo(result));
    }

    @Test
    void testToBigDecimal_fromDouble() {
        BigDecimal result = TypeConversionUtil.toBigDecimal(123.456);
        assertNotNull(result);
        // 注意：Double 转 BigDecimal 可能有精度问题，检查接近的值
        assertEquals(123.456, result.doubleValue(), 0.0001);
    }

    @Test
    void testToBigDecimal_fromBigDecimal() {
        BigDecimal input = new BigDecimal("123.456");
        BigDecimal result = TypeConversionUtil.toBigDecimal(input);
        assertSame(input, result);
    }

    @Test
    void testToBigDecimal_fromLong() {
        BigDecimal result = TypeConversionUtil.toBigDecimal(123456L);
        assertNotNull(result);
        assertEquals(0, new BigDecimal("123456").compareTo(result));
    }

    @Test
    void testToBigDecimal_fromNull() {
        assertNull(TypeConversionUtil.toBigDecimal(null));
    }

    @Test
    void testToInteger_fromNumber() {
        assertEquals(123, TypeConversionUtil.toInteger(123));
        assertEquals(123, TypeConversionUtil.toInteger(123L));
        assertEquals(123, TypeConversionUtil.toInteger(123.5)); // 截断
    }

    @Test
    void testToInteger_fromString() {
        assertEquals(123, TypeConversionUtil.toInteger("123"));
    }

    @Test
    void testToInteger_fromNull() {
        assertNull(TypeConversionUtil.toInteger(null));
    }

    @Test
    void testMapDeserializationScenario() {
        // 模拟 JSON 反序列化后的 Map 结构
        // LocalDateTime 变成 String, BigDecimal 变成 String/Double
        java.util.Map<String, Object> serializedMap = new java.util.HashMap<>();
        serializedMap.put("cycleIndex", 5);           // Integer → Number
        serializedMap.put("cycleAccumulated", "123.45"); // BigDecimal → String
        serializedMap.put("cycleBoundary", "2023-09-19T10:30:00"); // LocalDateTime → String

        // 使用 TypeConversionUtil 恢复类型
        Integer cycleIndex = TypeConversionUtil.toInteger(serializedMap.get("cycleIndex"));
        BigDecimal cycleAccumulated = TypeConversionUtil.toBigDecimal(serializedMap.get("cycleAccumulated"));
        LocalDateTime cycleBoundary = TypeConversionUtil.toLocalDateTime(serializedMap.get("cycleBoundary"));

        assertEquals(5, cycleIndex);
        assertEquals(0, new BigDecimal("123.45").compareTo(cycleAccumulated));
        assertEquals(LocalDateTime.of(2023, 9, 19, 10, 30, 0), cycleBoundary);
    }

    @Test
    void testMapDeserializationScenario_withDouble() {
        // 模拟 JSON 反序列化后 BigDecimal 变成 Double 的情况
        java.util.Map<String, Object> serializedMap = new java.util.HashMap<>();
        serializedMap.put("cycleAccumulated", 123.45); // BigDecimal → Double

        BigDecimal cycleAccumulated = TypeConversionUtil.toBigDecimal(serializedMap.get("cycleAccumulated"));

        assertNotNull(cycleAccumulated);
        assertEquals(123.45, cycleAccumulated.doubleValue(), 0.0001);
    }
}