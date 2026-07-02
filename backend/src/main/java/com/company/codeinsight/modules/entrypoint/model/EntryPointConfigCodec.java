package com.company.codeinsight.modules.entrypoint.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * EntryPointConfig JSON 编解码。
 * 旧版 flat JSON（根级 includeAnnotations）视为无效，回退四套默认预置。
 */
public final class EntryPointConfigCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private EntryPointConfigCodec() {
    }

    public static String encode(EntryPointConfig config) {
        if (config == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(EntryPointConfig.normalize(config));
        } catch (Exception e) {
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
            return EntryPointConfig.defaults();
        }
    }
}
