package cn.shang.charge.util;


import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Map;

public class JacksonUtils {


    /**
     * 注入统一的配置
     * 因配置在core模块 所以本工具类也需要在core模块   因为core模块不依赖utils模块
     */
    private static final ObjectMapper MAPPER = createMapper();

    // 默认时间格式
    private static final DateFormat DATETIME_FORMATTER = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    private static ObjectMapper createMapper() {
        return JsonMapper.builder()
                .findAndAddModules()
                .defaultDateFormat(DATETIME_FORMATTER).build();
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
