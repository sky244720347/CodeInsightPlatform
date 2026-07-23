package com.company.codeinsight.modules.entrypoint.model;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * EntryPointConfig JSON 编解码。
 * <ul>
 *   <li>只序列化真实字段；{@code isEmpty}/{@code getEffective*} 等派生方法由模型侧 {@code @JsonIgnore}</li>
 *   <li>反序列化忽略未知字段，兼容历史上误写入的 {@code empty}/{@code effective*}</li>
 *   <li>旧版 flat JSON（根级 includeAnnotations）视为无效，回退四套默认预置</li>
 * </ul>
 */
public final class EntryPointConfigCodec {

    private static final Logger log = LoggerFactory.getLogger(EntryPointConfigCodec.class);

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private EntryPointConfigCodec() {
    }

    public static String encode(EntryPointConfig config) {
        if (config == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(EntryPointConfig.normalize(config));
        } catch (Exception e) {
            log.warn("EntryPointConfig encode failed: {}", e.toString());
            return null;
        }
    }

    public static EntryPointConfig decode(String json) {
        if (json == null || json.isBlank()) {
            return EntryPointConfig.defaults();
        }
        try {
            JsonNode root = MAPPER.readTree(json);
            if (root.isObject() && root.has("includeAnnotations") && !root.has("includesByType")) {
                return EntryPointConfig.defaults();
            }
            EntryPointConfig cfg = MAPPER.treeToValue(root, EntryPointConfig.class);
            return EntryPointConfig.normalize(cfg);
        } catch (Exception e) {
            log.warn("EntryPointConfig decode failed, fallback to defaults: {}", e.toString());
            return EntryPointConfig.defaults();
        }
    }
}
