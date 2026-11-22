package com.company.retail.common.contract;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.Response;
import feign.codec.Decoder;
import org.springframework.boot.autoconfigure.http.HttpMessageConverters;
import org.springframework.cloud.openfeign.support.SpringDecoder;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

/**
 * 门禁返回形状解码器（W5 消费侧绕行，retail-core 自有文件，不改冻结契约）。
 *
 * <p><b>为什么存在</b>：frozen {@code FeignContracts.ApiResponseShape} 被声明为
 * <b>非静态内部类</b>（无 {@code static}），Jackson 无法实例化它——任何按冻结契约
 * 声明返回类型的 Feign 消费方在解码时必然抛
 * {@code InvalidDefinitionException: non-static inner classes like this can only be
 * instantiated using default, no-argument constructor}。这是共享区缺陷（已记入 W5 报告：
 * 建议改为 {@code public static class ApiResponseShape<T>}）。</p>
 *
 * <p><b>绕行方式</b>：本类（与冻结契约同包，可命名其包私有类型）反射构造外壳实例，
 * 逐字段解码 {@code code/message/data/traceId}，其余类型委托 {@link SpringDecoder}。
 * 只影响 retail-core 自己的 Feign 客户端 bean，不触碰冻结契约一行。</p>
 */
public class GateShapeDecoder implements Decoder {

    private final SpringDecoder fallback;
    private final ObjectMapper mapper = new ObjectMapper();

    public GateShapeDecoder(HttpMessageConverters converters) {
        this.fallback = new SpringDecoder(() -> converters);
    }

    @Override
    public Object decode(Response response, Type type) throws IOException {
        if (type instanceof ParameterizedType pt
                && pt.getRawType() == FeignContracts.ApiResponseShape.class) {
            return decodeShape(response, pt);
        }
        return fallback.decode(response, type);
    }

    private Object decodeShape(Response response, ParameterizedType shapeType) throws IOException {
        Type dataType = shapeType.getActualTypeArguments()[0];
        JsonNode tree;
        try (InputStream body = response.body().asInputStream()) {
            tree = mapper.readTree(body);
        }
        try {
            // 兼容两种冻结契约形态：S2 遗留收口后 ApiResponseShape 已是 public static class
            //（无参构造）；若仍是历史非静态内部类（需外部实例）则回退旧路径。
            Constructor<?> ctor;
            Object shape;
            try {
                ctor = FeignContracts.ApiResponseShape.class.getDeclaredConstructor();
                ctor.setAccessible(true);
                shape = ctor.newInstance();
            } catch (NoSuchMethodException legacy) {
                ctor = FeignContracts.ApiResponseShape.class
                        .getDeclaredConstructor(FeignContracts.class);
                ctor.setAccessible(true);
                java.lang.reflect.Constructor<FeignContracts> outerCtor =
                        FeignContracts.class.getDeclaredConstructor();
                outerCtor.setAccessible(true);
                shape = ctor.newInstance(outerCtor.newInstance());
            }
            setField(shape, "code", textOrNull(tree, "code"));
            setField(shape, "message", textOrNull(tree, "message"));
            setField(shape, "traceId", textOrNull(tree, "traceId"));
            JavaType dataJavaType = mapper.getTypeFactory().constructType(dataType);
            JsonNode dataNode = tree.get("data");
            Object data = dataNode == null || dataNode.isNull()
                    ? null
                    : mapper.readValue(dataNode.traverse(mapper), dataJavaType);
            setField(shape, "data", data);
            return shape;
        } catch (ReflectiveOperationException e) {
            throw new IOException("门禁返回形状解码失败（FeignContracts.ApiResponseShape 内部类绕行）", e);
        }
    }

    private static String textOrNull(JsonNode tree, String field) {
        JsonNode node = tree.get(field);
        return node == null || node.isNull() ? null : node.asText();
    }

    private static void setField(Object target, String name, Object value)
            throws ReflectiveOperationException {
        java.lang.reflect.Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
