package com.company.codeinsight.modules.prompt.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.company.codeinsight.common.exception.BusinessException;
import com.company.codeinsight.modules.model.entity.AiModel;
import com.company.codeinsight.modules.prompt.dto.PromptTestResultDto;
import com.company.codeinsight.modules.prompt.dto.PromptTestStreamEventDto;
import com.company.codeinsight.modules.prompt.dto.SyncPromptFromResourceResultDto;
import com.company.codeinsight.modules.prompt.entity.DecompilePrompt;
import com.company.codeinsight.modules.prompt.mapper.DecompilePromptMapper;
import com.company.codeinsight.modules.prompt.service.DecompilePromptService;
import com.company.codeinsight.modules.repository.entity.CodeRepository;
import com.company.codeinsight.modules.repository.mapper.CodeRepositoryMapper;
import com.company.codeinsight.modules.model.mapper.AiModelMapper;
import com.company.codeinsight.modules.token.service.TokenAuditService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.DigestUtils;
import org.springframework.util.StringUtils;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * AI 提示词模板管理服务实现类
 * 负责模板的查询、克隆备份、启动状态切换、大括号占位符渲染以及在线模型试跑测试（包含防网络抖动的 Mock 归纳生成器降级机制）。
 */
@Slf4j
@Service
public class DecompilePromptServiceImpl extends ServiceImpl<DecompilePromptMapper, DecompilePrompt> implements DecompilePromptService {

    private static final String DEFAULT_PROMPT_TYPE = "MODULARIZE";

    @Value("${code-insight.ai.mock:true}")
    private boolean isMockAi;

    @Value("${code-insight.ai.api-key:}")
    private String apiKey;

    @Value("${code-insight.ai.api-url:https://api.minimax.io/v1}")
    private String apiUrl;

    @Value("${code-insight.ai.model-name:MiniMax-M3}")
    private String modelNameProp;

    @Autowired
    private AiModelMapper aiModelMapper;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TokenAuditService tokenAuditService;

    @Autowired
    private CodeRepositoryMapper codeRepositoryMapper;

    private final HttpClient httpClient = HttpClient.newBuilder().build();

    /**
     * 分页查询提示词模板，支持按名称/状态/用途/生命周期/分类/scope 过滤，按创建时间倒序排列
     * <p>category + scopeId 过滤规则:</p>
     * <ul>
     *     <li>category=DEFAULT(或不传) → 不按 scope 过滤(全局可见,基础配置 → 提示词页用)</li>
     *     <li>category=USER + scopeId=N → 只看 scope_id=N 的 USER 提示词(系统/仓库配置用,互不可见)</li>
     *     <li>category=USER 不传 scopeId → 查所有 USER(罕见,调试用)</li>
     * </ul>
     */
    @Override
    public Page<DecompilePrompt> listPromptsPage(int current, int size, String name,
                                                 String promptType, String lifecycle, String category,
                                                 Long scopeId, Integer isDefault) {
        Page<DecompilePrompt> page = new Page<>(current, size);
        LambdaQueryWrapper<DecompilePrompt> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.like(StringUtils.hasText(name), DecompilePrompt::getName, name)
                .eq(StringUtils.hasText(promptType), DecompilePrompt::getPromptType, promptType)
                .eq(StringUtils.hasText(lifecycle), DecompilePrompt::getLifecycle, lifecycle);
        // category 过滤
        if (StringUtils.hasText(category)) {
            queryWrapper.eq(DecompilePrompt::getCategory, category);
            // USER 类且指定 scopeId 时,只看该 scope 的
            if ("USER".equalsIgnoreCase(category) && scopeId != null) {
                queryWrapper.eq(DecompilePrompt::getScopeId, scopeId);
            }
        }
        if (isDefault != null) {
            queryWrapper.eq(DecompilePrompt::getIsDefault, isDefault);
        }
        queryWrapper.orderByDesc(DecompilePrompt::getCreatedAt);
        return this.page(page, queryWrapper);
    }

    /**
     * 复制/克隆提示词模板
     * 无论源是 DRAFT/RELEASED/ARCHIVED, 复制都生成 version=源版本+1、is_default=0、lifecycle=DRAFT 的草稿;
     * 分类(category) 和 scope_id 与源一致 — 保持同 scope 内可继续编辑/试跑/发布。
     */
    @Override
    public DecompilePrompt clonePrompt(Long id) {
        DecompilePrompt original = this.getById(id);
        if (original == null) {
            throw new BusinessException("提示词不存在");
        }
        DecompilePrompt cloned = new DecompilePrompt();
        cloned.setName(original.getName() + " - 副本");
        cloned.setContent(original.getContent());
        // 版本号：在源版本基础上 +1（源为 null 时从 1 开始）
        cloned.setVersion(original.getVersion() == null ? 1 : original.getVersion() + 1);
        cloned.setIsDefault(0); // 默认非默认
        cloned.setPromptType(normalizePromptType(original.getPromptType()));
        cloned.setLifecycle(DecompilePrompt.LIFECYCLE_DRAFT); // 副本为草稿
        cloned.setCategory(original.getCategory()); // 保持同分类
        cloned.setScopeId(original.getScopeId()); // 保持同 scope
        this.save(cloned);
        return cloned;
    }

    /**
     * 发布：将 DRAFT 改为 RELEASED(锁定,不可再直接编辑)
     */
    @Override
    public DecompilePrompt publishPrompt(Long id) {
        DecompilePrompt prompt = this.getById(id);
        if (prompt == null) {
            throw new BusinessException("提示词不存在");
        }
        if (!DecompilePrompt.LIFECYCLE_DRAFT.equals(prompt.getLifecycle())) {
            throw new BusinessException("仅 DRAFT 状态可发布,当前状态: " + prompt.getLifecycle());
        }
        prompt.setLifecycle(DecompilePrompt.LIFECYCLE_RELEASED);
        // 发布时版本号 +1
        prompt.setVersion(prompt.getVersion() == null ? 1 : prompt.getVersion() + 1);
        this.updateById(prompt);
        return prompt;
    }

    /**
     * 归档：将 RELEASED 改为 ARCHIVED(历史保留,不再出现于默认池)
     */
    @Override
    public DecompilePrompt archivePrompt(Long id) {
        DecompilePrompt prompt = this.getById(id);
        if (prompt == null) {
            throw new BusinessException("提示词不存在");
        }
        if (!DecompilePrompt.LIFECYCLE_RELEASED.equals(prompt.getLifecycle())) {
            throw new BusinessException("仅 RELEASED 状态可归档,当前状态: " + prompt.getLifecycle());
        }
        // 若当前是默认,先取消默认(让位后再归档)
        if (prompt.getIsDefault() != null && prompt.getIsDefault() == 1) {
            prompt.setIsDefault(0);
            this.clearDefaultPrompts(prompt.getPromptType(), id, 0);
        }
        prompt.setLifecycle(DecompilePrompt.LIFECYCLE_ARCHIVED);
        this.updateById(prompt);
        return prompt;
    }

    /**
     * 同 promptType 下其他 DEFAULT 提示词的 is_default 批量清零(为新默认让位)。
     * <p>仅作用于 DEFAULT 类别;USER 提示词的 is_default 永远不会被清零。</p>
     */
    private void clearDefaultPrompts(String promptType, Long excludeId, Integer isDefault) {
        if (isDefault == null || isDefault != 1) {
            return;
        }
        LambdaUpdateWrapper<DecompilePrompt> updateWrapper = new LambdaUpdateWrapper<DecompilePrompt>()
                .eq(DecompilePrompt::getPromptType, normalizePromptType(promptType))
                .eq(DecompilePrompt::getCategory, "DEFAULT")  // 仅清零 DEFAULT 类别
                .set(DecompilePrompt::getIsDefault, 0);
        if (excludeId != null) {
            updateWrapper.ne(DecompilePrompt::getId, excludeId);
        }
        this.update(updateWrapper);
    }

    /**
     * 把 classpath 上指定 .md 资源的内容同步为该 {@code promptType} 的默认提示词。
     * <p>详细业务背景见接口注释。本方法处于事务边界内：
     * 内容比对 → 若变更则新插入 + 旧默认归档 → 一次性提交。</p>
     *
     * <p>典型用法：</p>
     * <pre>
     *   syncFromResource("MODULARIZE", "analyze_prompt.md");
     *   syncFromResource("DOCUMENT_GENERATION", "module_doc_prompt.md");
     * </pre>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public SyncPromptFromResourceResultDto syncFromResource(String promptType, String resourcePath) {
        String normalized = normalizePromptType(promptType);
        if (!DecompilePrompt.TYPE_MODULARIZE.equals(normalized)
                && !DecompilePrompt.TYPE_DOCUMENT_GENERATION.equals(normalized)) {
            throw new BusinessException("仅支持 MODULARIZE / DOCUMENT_GENERATION 两类提示词的资源同步");
        }
        if (!StringUtils.hasText(resourcePath)) {
            throw new BusinessException("resourcePath 不能为空（约定相对于 classpath 根）");
        }
        // 1. 读取 classpath 资源
        String resourceContent;
        try {
            ClassPathResource res = new ClassPathResource(resourcePath);
            if (!res.exists()) {
                throw new BusinessException("classpath 资源不存在: " + resourcePath);
            }
            resourceContent = new String(res.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("读取 classpath 资源失败: " + resourcePath + " — " + e.getMessage());
        }
        String resourceMd5 = DigestUtils.md5DigestAsHex(resourceContent.getBytes(StandardCharsets.UTF_8));
        // 2. 查现有默认提示词（按 promptType + isDefault=1）
        DecompilePrompt oldDefault = this.getOne(
                new LambdaQueryWrapper<DecompilePrompt>()
                        .eq(DecompilePrompt::getPromptType, normalized)
                        .eq(DecompilePrompt::getIsDefault, 1)
                        .eq(DecompilePrompt::getCategory, "DEFAULT")
                        .last("LIMIT 1"));
        // 3. 内容比对：MD5 一致则跳过
        if (oldDefault != null) {
            String oldMd5 = DigestUtils.md5DigestAsHex(
                    (oldDefault.getContent() == null ? "" : oldDefault.getContent()).getBytes(StandardCharsets.UTF_8));
            if (oldMd5.equals(resourceMd5)) {
                log.info("syncFromResource 内容一致, 跳过: promptType={}, resource={}", normalized, resourcePath);
                return new SyncPromptFromResourceResultDto(false, null, oldDefault.getId(),
                        "content identical", oldDefault.getVersion(), resourcePath);
            }
        }
        // 4. 内容不一致：新建版本 + 切换默认 + 归档旧默认
        int newVersion;
        String defaultName;
        if (oldDefault != null) {
            newVersion = (oldDefault.getVersion() == null ? 1 : oldDefault.getVersion()) + 1;
            defaultName = oldDefault.getName();
        } else {
            newVersion = 1;
            defaultName = "默认" + (DecompilePrompt.TYPE_MODULARIZE.equals(normalized) ? "模块提取" : "文档生成") + "提示词";
        }
        // 同一 promptType 下其他 DEFAULT 行的 is_default 清零（让位）
        clearDefaultPrompts(normalized, oldDefault == null ? null : oldDefault.getId(), 1);

        DecompilePrompt fresh = new DecompilePrompt();
        fresh.setName(defaultName);
        fresh.setContent(resourceContent);
        fresh.setVersion(newVersion);
        fresh.setPromptType(normalized);
        fresh.setLifecycle(DecompilePrompt.LIFECYCLE_RELEASED);
        fresh.setIsDefault(1);
        fresh.setCategory("DEFAULT");
        fresh.setScopeId(null);
        fresh.setCreatedAt(LocalDateTime.now());
        fresh.setUpdatedAt(LocalDateTime.now());
        this.save(fresh);
        // 5. 归档旧默认（如有）
        Long oldId = null;
        if (oldDefault != null) {
            try {
                archivePrompt(oldDefault.getId());
                oldId = oldDefault.getId();
            } catch (Exception e) {
                log.warn("归档旧默认提示词失败，但新默认已生效: oldId={}, err={}", oldDefault.getId(), e.getMessage());
                oldId = oldDefault.getId();
            }
        }
        log.info("syncFromResource 同步成功: promptType={}, resource={}, newPromptId={}, version={}, oldPromptId={}",
                normalized, resourcePath, fresh.getId(), newVersion, oldId);
        String reason = oldDefault == null ? "first seed" : "content changed";
        return new SyncPromptFromResourceResultDto(true, fresh.getId(), oldId, reason, newVersion, resourcePath);
    }

    /**
     * 对提示词模板中的 ${variable_name} 变量进行检索替换
     */
    @Override
    public String replaceVariables(String template, Map<String, String> variables) {
        if (!StringUtils.hasText(template) || variables == null) {
            return template;
        }
        String result = template;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            String key = "${" + entry.getKey() + "}";
            String value = entry.getValue() != null ? entry.getValue() : "";
            result = result.replace(key, value);
        }
        return result;
    }

    @Override
    public String resolveTaskPromptContent(com.company.codeinsight.modules.task.entity.DecompileTask task, String promptType) {
        try {
            return requireTaskPromptContent(task, promptType);
        } catch (BusinessException ex) {
            log.warn("任务 {} 解析 {} 提示词失败: {}", task != null ? task.getId() : null, promptType, ex.getMessage());
            return null;
        }
    }

    @Override
    public String requireTaskPromptContent(com.company.codeinsight.modules.task.entity.DecompileTask task, String promptType) {
        if (task == null || !StringUtils.hasText(promptType)) {
            throw new BusinessException("任务提示词配置缺失");
        }
        Long explicitId = DecompilePrompt.TYPE_MODULARIZE.equals(promptType)
                ? task.getModularizePromptId()
                : task.getDocumentPromptId();
        String label = DecompilePrompt.TYPE_MODULARIZE.equals(promptType) ? "模块提取" : "文档生成";
        if (explicitId == null) {
            throw new BusinessException("任务未绑定" + label + "提示词，无法执行流水线。请前往「系统与仓库」完成提示词绑定后重新创建任务。");
        }
        DecompilePrompt hit = this.baseMapper.selectById(explicitId);
        if (hit == null || !hit.isReleased() || !promptType.equals(hit.getPromptType())) {
            throw new BusinessException("任务绑定的" + label + "提示词无效或未发布，无法执行流水线");
        }
        if (!StringUtils.hasText(hit.getContent())) {
            throw new BusinessException("任务绑定的" + label + "提示词内容为空，无法执行流水线");
        }
        return hit.getContent();
    }

    @Override
    public void validateRepositoryPromptBinding(Long repositoryId) {
        if (repositoryId == null) {
            throw new BusinessException("代码库不存在");
        }
        CodeRepository repo = codeRepositoryMapper.selectById(repositoryId);
        if (repo == null) {
            throw new BusinessException("代码库不存在");
        }
        validatePromptPair(repo.getModularizePromptId(), repo.getDocumentPromptId(), "仓库");
    }

    @Override
    public void validateTaskPromptBinding(Long modularizePromptId, Long documentPromptId) {
        validatePromptPair(modularizePromptId, documentPromptId, "任务");
    }

    @Override
    public boolean isRepositoryPromptsConfigured(Long repositoryId) {
        return getRepositoryPromptsConfigurationMessage(repositoryId) == null;
    }

    @Override
    public String getRepositoryPromptsConfigurationMessage(Long repositoryId) {
        try {
            validateRepositoryPromptBinding(repositoryId);
            return null;
        } catch (BusinessException ex) {
            return ex.getMessage();
        }
    }

    private void validatePromptPair(Long modularizeId, Long documentId, String scope) {
        if (modularizeId == null) {
            throw new BusinessException(scope + "未绑定模块提取提示词，请前往「系统与仓库」完成提示词绑定");
        }
        if (documentId == null) {
            throw new BusinessException(scope + "未绑定文档生成提示词，请前往「系统与仓库」完成提示词绑定");
        }
        DecompilePrompt modularize = this.baseMapper.selectById(modularizeId);
        if (modularize == null || !modularize.isReleased()
                || !DecompilePrompt.TYPE_MODULARIZE.equals(modularize.getPromptType())) {
            throw new BusinessException(scope + "绑定的模块提取提示词无效或未发布，请前往「系统与仓库」重新绑定");
        }
        DecompilePrompt document = this.baseMapper.selectById(documentId);
        if (document == null || !document.isReleased()
                || !DecompilePrompt.TYPE_DOCUMENT_GENERATION.equals(document.getPromptType())) {
            throw new BusinessException(scope + "绑定的文档生成提示词无效或未发布，请前往「系统与仓库」重新绑定");
        }
        if (!StringUtils.hasText(modularize.getContent()) || !StringUtils.hasText(document.getContent())) {
            throw new BusinessException(scope + "绑定的提示词内容为空，请前往「系统与仓库」重新绑定");
        }
    }

    /**
     * 在线测试运行提示词模板
     * 用一段示例代码和所选模型参数，调用大模型（或使用内置的高保真测试 MOCK 生成器降级流程）。
     *
     * @param resolvedContent 前端已替换占位符的最终 prompt 正文。若非空,直接使用,跳过占位符替换。
     */
    @Override
    public PromptTestResultDto testRun(Long id, String sampleCode, Long modelId, String resolvedContent) {
        String filledPrompt = resolveTrialFilledPrompt(id, sampleCode, resolvedContent);
        String parsedClass = parseClassName(sampleCode);
        String parsedMethod = parseMethodName(sampleCode);

        // 3. 获取大模型配置
        AiModel model = resolveTrialModel(modelId);

        String modelName = model != null ? model.getIdentifier() : this.modelNameProp;
        String activeApiKey = (model != null && StringUtils.hasText(model.getApiKey())) ? model.getApiKey() : this.apiKey;
        String activeApiUrl = (model != null && StringUtils.hasText(model.getBaseUrl())) ? model.getBaseUrl() : this.apiUrl;

        long start = System.currentTimeMillis();
        PromptTestResultDto result = new PromptTestResultDto();

        // 4. 判定是否满足使用 Mock 降级条件
        boolean shouldMock = this.isMockAi;
        if (!shouldMock) {
            if (!StringUtils.hasText(activeApiKey) || activeApiKey.startsWith("test-key") || "mock".equalsIgnoreCase(activeApiKey)) {
                shouldMock = true;
            }
        }

        if (shouldMock) {
            // 4.1 Mock 降级 - 使用高保真仿真分析器模拟文档生成
            result.setInputTokens(filledPrompt.length() / 4);
            String mockReply = generateMockTestResult(parsedClass, parsedMethod, sampleCode, modelName);
            result.setOutputTokens(mockReply.length() / 4);
            result.setDurationMs(System.currentTimeMillis() - start);
            result.setResult(mockReply);
            result.setErrorReason(null);

            // 记录审计日志
            tokenAuditService.logTokenUsage(null, null, modelName, result.getInputTokens(), result.getOutputTokens(), "TEST", true);
        } else {
            // 4.2 真实大模型调用，拼装标准 OpenAI 协议
            try {
                Map<String, Object> reqBody = new HashMap<>();
                reqBody.put("model", modelName);
                reqBody.put("stream", false);

                List<Map<String, String>> messages = new ArrayList<>();
                Map<String, String> userMsg = new HashMap<>();
                userMsg.put("role", "user");
                userMsg.put("content", filledPrompt);
                messages.add(userMsg);
                reqBody.put("messages", messages);

                String jsonPayload = objectMapper.writeValueAsString(reqBody);

                String requestUrl = activeApiUrl;
                if (!requestUrl.endsWith("/chat/completions")) {
                    requestUrl = requestUrl.replaceAll("/+$", "") + "/chat/completions";
                }

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(requestUrl))
                        .header("Authorization", "Bearer " + activeApiKey)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                        .timeout(Duration.ofSeconds(45))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                long duration = System.currentTimeMillis() - start;

                if (response.statusCode() == 200) {
                    com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(response.body());
                    String aiText = root.path("choices").get(0).path("message").path("content").asText();
                    int inTokens = root.path("usage").path("prompt_tokens").asInt();
                    int outTokens = root.path("usage").path("completion_tokens").asInt();

                    result.setInputTokens(inTokens);
                    result.setOutputTokens(outTokens);
                    result.setResult(aiText);
                    result.setDurationMs(duration);
                    result.setErrorReason(null);

                    tokenAuditService.logTokenUsage(null, null, modelName, inTokens, outTokens, "TEST", true);
                } else {
                    String errMsg = "HTTP 错误码: " + response.statusCode() + ", 详情: " + response.body();
                    result.setInputTokens(filledPrompt.length() / 4);
                    result.setOutputTokens(0);
                    result.setResult("真实模型调用失败: " + errMsg);
                    result.setDurationMs(duration);
                    result.setErrorReason(errMsg);

                    tokenAuditService.logTokenUsage(null, null, modelName, result.getInputTokens(), 0, "TEST", false);
                }
            } catch (Exception e) {
                long duration = System.currentTimeMillis() - start;
                result.setInputTokens(filledPrompt.length() / 4);
                result.setOutputTokens(0);
                result.setResult("调用大模型网络异常: " + e.getMessage());
                result.setDurationMs(duration);
                result.setErrorReason(e.getMessage());

                tokenAuditService.logTokenUsage(null, null, modelName, result.getInputTokens(), 0, "TEST", false);
            }
        }

        return result;
    }

    /**
     * 试跑正文：前端传 {@code resolvedContent} 时可直接试跑未落库的自定义草稿（id 可为占位负值）；
     * 否则按 id 加载已存模板并做占位符替换。
     */
    private String resolveTrialFilledPrompt(Long id, String sampleCode, String resolvedContent) {
        if (StringUtils.hasText(resolvedContent)) {
            return resolvedContent;
        }
        DecompilePrompt prompt = this.getById(id);
        if (prompt == null) {
            throw new BusinessException("提示词模板不存在");
        }
        Map<String, String> vars = new HashMap<>();
        vars.put("class_name", parseClassName(sampleCode));
        vars.put("method_name", parseMethodName(sampleCode));
        vars.put("source_code", sampleCode != null ? sampleCode : "public class MockTestClass { public void mockExecute() {} }");
        return replaceVariables(prompt.getContent(), vars);
    }

    @Override
    public void testRunStream(Long id, String sampleCode, Long modelId, String resolvedContent,
                              Consumer<PromptTestStreamEventDto> eventConsumer) {
        String filledPrompt = resolveTrialFilledPrompt(id, sampleCode, resolvedContent);
        String parsedClass = parseClassName(sampleCode);
        String parsedMethod = parseMethodName(sampleCode);

        AiModel model = resolveTrialModel(modelId);
        String modelName = model != null ? model.getIdentifier() : this.modelNameProp;
        String activeApiKey = (model != null && StringUtils.hasText(model.getApiKey())) ? model.getApiKey() : this.apiKey;
        String activeApiUrl = (model != null && StringUtils.hasText(model.getBaseUrl())) ? model.getBaseUrl() : this.apiUrl;

        long start = System.currentTimeMillis();
        int inputTokens = Math.max(1, filledPrompt.length() / 4);
        boolean shouldMock = this.isMockAi;
        if (!shouldMock && (!StringUtils.hasText(activeApiKey) || activeApiKey.startsWith("test-key") || "mock".equalsIgnoreCase(activeApiKey))) {
            shouldMock = true;
        }

        if (shouldMock) {
            String mockReply = generateMockTestResult(parsedClass, parsedMethod, sampleCode, modelName);
            emitTextChunks(mockReply, eventConsumer);
            int outputTokens = Math.max(1, mockReply.length() / 4);
            long duration = System.currentTimeMillis() - start;
            eventConsumer.accept(PromptTestStreamEventDto.done(inputTokens, outputTokens, duration));
            tokenAuditService.logTokenUsage(null, null, modelName, inputTokens, outputTokens, "TEST", true);
            return;
        }

        StringBuilder streamedText = new StringBuilder();
        int outputTokens = 0;
        try {
            Map<String, Object> reqBody = new HashMap<>();
            reqBody.put("model", modelName);
            reqBody.put("stream", true);
            reqBody.put("stream_options", Map.of("include_usage", true));

            List<Map<String, String>> messages = new ArrayList<>();
            Map<String, String> userMsg = new HashMap<>();
            userMsg.put("role", "user");
            userMsg.put("content", filledPrompt);
            messages.add(userMsg);
            reqBody.put("messages", messages);

            String requestUrl = activeApiUrl;
            if (!requestUrl.endsWith("/chat/completions")) {
                requestUrl = requestUrl.replaceAll("/+$", "") + "/chat/completions";
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(requestUrl))
                    .header("Authorization", "Bearer " + activeApiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(reqBody)))
                    .timeout(Duration.ofSeconds(60))
                    .build();

            HttpResponse<java.util.stream.Stream<String>> response = httpClient.send(request, HttpResponse.BodyHandlers.ofLines());
            if (response.statusCode() != 200) {
                String body = response.body().limit(20).collect(Collectors.joining("\n"));
                String errorReason = "HTTP 错误码: " + response.statusCode() + ", 详情: " + body;
                long duration = System.currentTimeMillis() - start;
                eventConsumer.accept(PromptTestStreamEventDto.error(errorReason, inputTokens, duration));
                tokenAuditService.logTokenUsage(null, null, modelName, inputTokens, 0, "TEST", false);
                return;
            }

            try (java.util.stream.Stream<String> lines = response.body()) {
                for (String line : (Iterable<String>) lines::iterator) {
                    if (!StringUtils.hasText(line) || !line.startsWith("data:")) {
                        continue;
                    }
                    String data = line.substring("data:".length()).trim();
                    if ("[DONE]".equals(data)) {
                        break;
                    }
                    JsonNode root = objectMapper.readTree(data);
                    JsonNode usage = root.path("usage");
                    if (!usage.isMissingNode() && !usage.isNull()) {
                        int usageInputTokens = usage.path("prompt_tokens").asInt(inputTokens);
                        int usageOutputTokens = usage.path("completion_tokens").asInt(outputTokens);
                        inputTokens = usageInputTokens > 0 ? usageInputTokens : inputTokens;
                        outputTokens = usageOutputTokens > 0 ? usageOutputTokens : outputTokens;
                    }
                    JsonNode choices = root.path("choices");
                    if (!choices.isArray() || choices.isEmpty()) {
                        continue;
                    }
                    String delta = choices.get(0).path("delta").path("content").asText("");
                    if (StringUtils.hasText(delta)) {
                        streamedText.append(delta);
                        eventConsumer.accept(PromptTestStreamEventDto.content(delta));
                    }
                }
            }

            if (outputTokens <= 0) {
                outputTokens = Math.max(1, streamedText.length() / 4);
            }
            long duration = System.currentTimeMillis() - start;
            eventConsumer.accept(PromptTestStreamEventDto.done(inputTokens, outputTokens, duration));
            tokenAuditService.logTokenUsage(null, null, modelName, inputTokens, outputTokens, "TEST", true);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            eventConsumer.accept(PromptTestStreamEventDto.error(e.getMessage(), inputTokens, duration));
            tokenAuditService.logTokenUsage(null, null, modelName, inputTokens, outputTokens, "TEST", false);
        }
    }

    /**
     * 正则提取测试代码中的类名
     */
    private String parseClassName(String sampleCode) {
        if (!StringUtils.hasText(sampleCode)) {
            return "MockTestClass";
        }
        Pattern pattern = Pattern.compile("(?:public\\s+)?(?:abstract\\s+|final\\s+)?(?:class|interface|enum)\\s+(\\w+)");
        Matcher matcher = pattern.matcher(sampleCode);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "MockTestClass";
    }

    /**
     * 正则提取测试代码中的核心方法名
     */
    private String parseMethodName(String sampleCode) {
        if (!StringUtils.hasText(sampleCode)) {
            return "mockExecute";
        }
        Pattern pattern = Pattern.compile("(?:public|protected|private|static|final|synchronized|\\s)+\\s+[\\w<>\\[\\],.?\\s]+\\s+(\\w+)\\s*\\(");
        Matcher matcher = pattern.matcher(sampleCode);
        while (matcher.find()) {
            String mName = matcher.group(1).trim();
            if (!List.of("if", "for", "while", "switch", "class", "synchronized", "catch").contains(mName)) {
                return mName;
            }
        }
        return "mockExecute";
    }

    private String normalizePromptType(String promptType) {
        return StringUtils.hasText(promptType) ? promptType : DEFAULT_PROMPT_TYPE;
    }

    private AiModel resolveTrialModel(Long modelId) {
        if (modelId != null) {
            return aiModelMapper.selectById(modelId);
        }

        AiModel defaultModel = aiModelMapper.selectOne(
                new LambdaQueryWrapper<AiModel>()
                        .eq(AiModel::getIsDefault, "true")
                        .eq(AiModel::getStatus, 1)
                        .isNotNull(AiModel::getApiKey)
                        .ne(AiModel::getApiKey, "")
                        .last("LIMIT 1")
        );
        if (defaultModel != null) {
            return defaultModel;
        }

        return aiModelMapper.selectOne(
                new LambdaQueryWrapper<AiModel>()
                        .eq(AiModel::getStatus, 1)
                        .isNotNull(AiModel::getApiKey)
                        .ne(AiModel::getApiKey, "")
                        .orderByAsc(AiModel::getSortOrder)
                        .orderByDesc(AiModel::getId)
                        .last("LIMIT 1")
        );
    }

    private void emitTextChunks(String text, Consumer<PromptTestStreamEventDto> eventConsumer) {
        if (!StringUtils.hasText(text)) {
            return;
        }
        int chunkSize = 24;
        for (int start = 0; start < text.length(); start += chunkSize) {
            int end = Math.min(text.length(), start + chunkSize);
            eventConsumer.accept(PromptTestStreamEventDto.content(text.substring(start, end)));
        }
    }

    /**
     * 高逼真仿真测试结果生成器
     */
    private String generateMockTestResult(String className, String methodName, String sampleCode, String modelName) {
        StringBuilder sb = new StringBuilder();
        
        sb.append("## 1. 职责概述\n");
        if (className.endsWith("Controller")) {
            sb.append("`").append(className).append("` 是一个接口访问控制层控制器。定义了针对相关资源的 REST 路由接口，负责前端请求参数校验、数据分发和统一响应格式的封装输出。\n\n");
        } else if (className.endsWith("Service")) {
            sb.append("`").append(className).append("` 是系统的核心业务逻辑服务类。负责处理业务决策、状态机转换和数据处理的事务边界。协调各个数据操作的 Mapper 接口以提供完整的业务支持。\n\n");
        } else if (className.endsWith("Mapper")) {
            sb.append("`").append(className).append("` 是 MyBatis 数据访问持久层接口。通过注解或 XML 配置底层的 SQL 语句，实现与数据库表的映射，为业务层提供原子级的数据查询与存取支持。\n\n");
        } else {
            sb.append("`").append(className).append("` 是一个业务处理类，定义了核心业务流的骨架，负责组织 and 执行对应的数据处理逻辑。\n\n");
        }
        
        sb.append("## 2. 核心流程\n");
        if (StringUtils.hasText(methodName) && !"mockExecute".equals(methodName)) {
            sb.append("该类的核心业务方法为 `").append(methodName).append("`。主要执行以下步骤：\n");
            if (methodName.startsWith("get") || methodName.startsWith("select") || methodName.startsWith("list")) {
                sb.append("1. 接收查询条件及分页/排序参数。\n");
                sb.append("2. 校验参数有效性，若不合法则抛出业务异常。\n");
                sb.append("3. 调用底层的持久层接口，执行只读 SQL 查询，获取匹配的实体记录。\n");
                sb.append("4. 将实体记录转换为 DTO/VO 传输对象并返回。\n\n");
            } else if (methodName.startsWith("save") || methodName.startsWith("create") || methodName.startsWith("insert")) {
                sb.append("1. 接收需要保存的业务实体数据。\n");
                sb.append("2. 进行前置业务校验（如重复性校验、字段长度校验等）。\n");
                sb.append("3. 开启本地事务，调用持久层向数据库插入/更新记录。\n");
                sb.append("4. 记录操作流水日志，在成功后返回对应的主键 ID。\n\n");
            } else {
                sb.append("1. 接收输入参数，加载所需的业务上下文。\n");
                sb.append("2. 执行核心状态判断和业务逻辑计算。\n");
                sb.append("3. 协调相关组件/持久层，更新数据库状态。\n");
                sb.append("4. 记录审计日志，返回执行状态或处理结果。\n\n");
            }
        } else {
            sb.append("1. 解析输入数据，提取关键字段。\n");
            sb.append("2. 调用依赖的业务组件，按序处理核心节点流程。\n");
            sb.append("3. 保存状态变更，记录操作日志。\n\n");
        }
        
        sb.append("## 3. 重要依赖\n");
        List<String> deps = new ArrayList<>();
        Pattern depPattern = Pattern.compile("(?:private|protected|public)\\s+(?:final\\s+)?([A-Z][\\w]*(?:<[^>]+>)?)\\s+(\\w+)\\s*(?:=|;)");
        Matcher depMatcher = depPattern.matcher(sampleCode != null ? sampleCode : "");
        while (depMatcher.find()) {
            String depType = depMatcher.group(1);
            String depName = depMatcher.group(2);
            deps.add("`" + depName + "` (" + depType + ")");
        }
        if (!deps.isEmpty()) {
            for (String dep : deps) {
                sb.append("- 依赖组件: ").append(dep).append("\n");
            }
            sb.append("\n");
        } else {
            sb.append("- 暂无明显外部依赖（或仅依赖基础类库）。\n\n");
        }
        
        sb.append("## 4. 待复核事项\n");
        sb.append("- [ ] 该类在处理高并发场景时的事务隔离级别是否符合业务预期？\n");
        sb.append("- [ ] 异常情况下的熔断/降级策略是否完整配置并在前端呈现？\n\n");

        sb.append("> *(注：当前试跑使用的是仿真 Mock 生成器，模拟模型：").append(modelName).append(")*");
        
        return sb.toString();
    }
}
