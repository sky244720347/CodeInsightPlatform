package com.company.codeinsight.common.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class AiResponseJsonExtractorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    public void stripsRedactedThinkingAndParsesTrailingJson() throws Exception {
        String raw = "<think>analyze controller</think>\n"
                + "{\"modules\":{\"m00001\":{\"module_name\":\"问答\"}}}";
        String json = AiResponseJsonExtractor.extractJsonPayload(raw);
        JsonNode node = objectMapper.readTree(json);
        Assertions.assertTrue(node.path("modules").has("m00001"));
    }

    @Test
    public void stripsThinkingBlockInsideCodeFence() throws Exception {
        String raw = "```json\n<think>plan</think>\n"
                + "{\"modules\":{}}\n```";
        String json = AiResponseJsonExtractor.extractJsonPayload(raw);
        JsonNode node = objectMapper.readTree(json);
        Assertions.assertTrue(node.has("modules"));
    }

    @Test
    public void extractsJsonEmbeddedInProse() throws Exception {
        String raw = "Here is the result:\n{\"modules\":{\"m00002\":{\"module_name\":\"标注\"}}}\nDone.";
        String json = AiResponseJsonExtractor.extractJsonPayload(raw);
        JsonNode node = objectMapper.readTree(json);
        Assertions.assertEquals("标注", node.path("modules").path("m00002").path("module_name").asText());
    }

    @Test
    public void stripModelArtifactsKeepsMarkdownBody() {
        String raw = "<think>draft</think>\n# Title\n\ncontent";
        Assertions.assertEquals("# Title\n\ncontent", AiResponseJsonExtractor.stripModelArtifacts(raw));
    }
}
