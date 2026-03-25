package cn.shang.charging.charge.util;


import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.datatype.jsr310.JavaTimeModule;
import tools.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

public class JacksonUtils {

    /**
     * LocalDateTime 序列化格式
     */
    private static final DateTimeFormatter DATETIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 注入统一的配置
     * 因配置在core模块 所以本工具类也需要在core模块   因为core模块不依赖utils模块
     */
    private static final ObjectMapper MAPPER = createMapper();

    private static ObjectMapper createMapper() {
        // 创建自定义模块配置 LocalDateTime 序列化格式
        SimpleModule customModule = new SimpleModule();
        customModule.addSerializer(LocalDateTime.class, new LocalDateTimeSerializer(DATETIME_FORMATTER));

        return JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .addModule(customModule)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
    }

    public static String toJsonString(Object obj) {
        return MAPPER.writeValueAsString(obj);
    }

    public static <T> T parse(String json, Class<T> clazz) {
        try {
            return MAPPER.readValue(json, clazz);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static <T> T parse(String json, TypeReference<T> ref) {
        try {
            return MAPPER.readValue(json, ref);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static <T> List<T> fromJsonList(String json, Class<T> clazz) {
        try {
            return MAPPER.readValue(
                    json,
                    MAPPER.getTypeFactory().constructCollectionType(List.class, clazz)
            );
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static <K, V> Map<K, V> fromJsonMap(String json, Class<K> key, Class<V> val) {
        try {
            return MAPPER.readValue(
                    json,
                    MAPPER.getTypeFactory().constructMapType(Map.class, key, val)
            );
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

}
