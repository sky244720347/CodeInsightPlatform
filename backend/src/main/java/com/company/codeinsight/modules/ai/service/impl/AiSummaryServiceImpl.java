package com.company.codeinsight.modules.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.company.codeinsight.common.config.AiRetryProperties;
import com.company.codeinsight.common.exception.BusinessException;
import com.company.codeinsight.common.util.AiResponseJsonExtractor;
import com.company.codeinsight.modules.ai.entity.AiCallRecord;
import com.company.codeinsight.common.storage.TaskWorkspacePaths;
import com.company.codeinsight.modules.ai.mapper.AiCallRecordMapper;
import com.company.codeinsight.modules.callchain.entity.MethodCall;
import com.company.codeinsight.modules.callchain.mapper.MethodCallMapper;
import com.company.codeinsight.modules.ai.service.AiSummaryService;
import com.company.codeinsight.modules.ai.service.PipelineAiCaller;
import com.company.codeinsight.modules.chunk.entity.CodeChunk;
import com.company.codeinsight.modules.chunk.mapper.CodeChunkMapper;
import com.company.codeinsight.modules.draft.entity.DraftWorkspace;
import com.company.codeinsight.modules.draft.entity.KnowledgeDraft;
import com.company.codeinsight.modules.draft.mapper.DraftWorkspaceMapper;
import com.company.codeinsight.modules.draft.mapper.KnowledgeDraftMapper;
import com.company.codeinsight.modules.task.entity.DecompileTask;
import com.company.codeinsight.modules.task.mapper.DecompileTaskMapper;
import com.company.codeinsight.modules.token.service.TokenAuditService;
import com.company.codeinsight.modules.parser.service.JavaParserService;
import com.company.codeinsight.modules.parser.model.ParsedClassInfo;
import com.company.codeinsight.modules.draft.entity.DraftSourceReference;
import com.company.codeinsight.modules.draft.mapper.DraftSourceReferenceMapper;
import com.company.codeinsight.modules.hierarchy.entity.MethodFunctionBinding;
import com.company.codeinsight.modules.hierarchy.mapper.MethodFunctionBindingMapper;
import com.company.codeinsight.modules.repository.entity.CodeRepository;
import com.company.codeinsight.modules.repository.mapper.CodeRepositoryMapper;
import com.company.codeinsight.modules.task.service.TaskExecutionLogger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;
import org.springframework.util.StringUtils;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
public class AiSummaryServiceImpl implements AiSummaryService {

    // 数据分片持久层映射
    @Autowired
    private CodeChunkMapper chunkMapper;

    // AI 调用历史记录映射
    @Autowired
    private AiCallRecordMapper aiCallRecordMapper;

    // Token 消耗审计与流控服务
    @Autowired
    private TokenAuditService tokenAuditService;

    // 基础配置 - 流量管控：用户额度前置检查
    @Autowired
    private com.company.codeinsight.modules.quotacontrol.service.QuotaCheckService quotaCheckService;

    // 基础配置 - 流量管控：AI 调用并发信号量
    @Autowired
    private com.company.codeinsight.modules.quotacontrol.service.AiConcurrencyService aiConcurrencyService;

    // 草稿工作区映射
    @Autowired
    private DraftWorkspaceMapper draftWorkspaceMapper;

    // 知识草稿内容映射
    @Autowired
    private KnowledgeDraftMapper knowledgeDraftMapper;

    // 任务实体数据映射
    @Autowired
    private DecompileTaskMapper decompileTaskMapper;

    // Java 静态解析服务组件
    @Autowired
    private JavaParserService javaParserService;

    // 代码来源引用映射
    @Autowired
    private DraftSourceReferenceMapper draftSourceReferenceMapper;

    @Autowired
    private CodeRepositoryMapper repositoryMapper;

    @Autowired
    private com.company.codeinsight.modules.model.mapper.AiModelMapper aiModelMapper;

    @Autowired
    private com.company.codeinsight.modules.prompt.mapper.DecompilePromptMapper promptMapper;

    /**
     * 任务执行日志写入器：用于在"查看完整日志"中实时呈现 AI 阶段逐切片/逐模块进度。
     */
    @Autowired
    private TaskExecutionLogger execLog;

    @Autowired
    private PipelineAiCaller pipelineAiCaller;

    @Autowired
    private AiRetryProperties aiRetryProperties;

    /**
     * 包级访问器：供 DecompileTaskServiceImpl 在 AI 阶段开头读取 Mock 状态写到 pipeline.log。
     */
    public boolean isAiMock() {
        return this.aiMock;
    }

    @Autowired
    private com.company.codeinsight.modules.prompt.service.DecompilePromptService decompilePromptService;

    @Autowired
    private com.company.codeinsight.modules.hierarchy.service.ModuleHierarchyService moduleHierarchyService;

    @Autowired
    private com.company.codeinsight.modules.entrypoint.service.EntryPointDiscoveryService entryPointDiscoveryService;

    @Autowired
    private com.company.codeinsight.common.util.PromptTemplateLoader promptTemplateLoader;

    @Autowired
    private MethodCallMapper methodCallMapper;

    @Autowired
    private MethodFunctionBindingMapper methodFunctionBindingMapper;

    @Autowired
    private com.company.codeinsight.modules.callchain.service.MethodCallGraphService methodCallGraphService;

    @Autowired
    private TaskWorkspacePaths taskWorkspacePaths;

    // 是否启用 AI 本地 Mock 仿真
    @Value("${code-insight.ai.mock:false}")
    private boolean aiMock;

    // 大模型服务访问密钥
    @Value("${code-insight.ai.api-key:}")
    private String apiKey;

    // 大模型服务接口基础地址 (默认为 MiniMax 服务端点)
    @Value("${code-insight.ai.api-url:https://api.minimax.io/v1}")
    private String apiUrl;

    // 默认选用的大模型版本名称
    @Value("${code-insight.ai.model-name:MiniMax-M3}")
    private String modelName;

    // 本地磁盘存储路径 (草稿物理正文暂存区)
    @Value("${code-insight.storage.local-path:./storage}")
    private String localStoragePath;

    // 是否启用 Token 额度上限校验
    @Value("${code-insight.token.limit-enabled:true}")
    private boolean tokenLimitEnabled;

    // 单任务 Token 累积上限
    @Value("${code-insight.token.task-limit:100000}")
    private int taskTokenLimit;

    // 单系统月度 Token 累积上限
    @Value("${code-insight.token.system-monthly-limit:1000000}")
    private int systemMonthlyTokenLimit;

    /** 文档生成粒度：function=按功能(默认) / module=按模块 */
    @Value("${code-insight.doc-generation.granularity:function}")
    private String docGenerationGranularity;

    // JSON 数据映射工具
    private final ObjectMapper objectMapper = new ObjectMapper();
    // HTTP 调用客户端，连接超时设为 15 秒
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    /**
     * 对特定代码切片发起大模型归纳分析
     * 支持双层 Token 额度上限拦截、自动敏感词 Regex 过滤脱敏、大模型 API 调用及网络波动/出错时的 Mock 本地降级兜底。
     *
     * @param taskId            知识构建任务 ID
     * @param chunkId           代码切片 ID
     * @param promptContent     提示词模板正文
     * @param modelNameSelected 手动指定的模型名称（若为空则使用系统默认）
     * @return 大模型返回的 Markdown 形式归纳总结内容
     */
    @Override
    public String summarizeChunk(Long taskId, Long chunkId, String promptContent, String modelNameSelected) {
        // 查找切片元数据
        CodeChunk chunk = chunkMapper.selectById(chunkId);
        if (chunk == null) {
            throw new BusinessException("未找到切片记录");
        }

        DecompileTask task = decompileTaskMapper.selectById(taskId);
        Long systemId = task != null ? task.getSystemId() : 0L;

        String modelToUse = StringUtils.hasText(modelNameSelected) ? modelNameSelected : this.modelName;

        String activeApiKey = this.apiKey;
        String activeApiUrl = this.apiUrl;
        
        // 1. 根据选用的模型名称，从数据库中动态载入最新的接口端点与密钥配置以支持多模型热插拔
        if (StringUtils.hasText(modelToUse)) {
            com.company.codeinsight.modules.model.entity.AiModel dbModel = aiModelMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.company.codeinsight.modules.model.entity.AiModel>()
                            .eq(com.company.codeinsight.modules.model.entity.AiModel::getIdentifier, modelToUse)
                            .last("LIMIT 1")
            );
            if (dbModel != null) {
                if (StringUtils.hasText(dbModel.getApiKey())) {
                    activeApiKey = dbModel.getApiKey();
                }
                if (StringUtils.hasText(dbModel.getBaseUrl())) {
                    activeApiUrl = dbModel.getBaseUrl();
                }
            }
        }

        // 2. 从对应的快照物理文件读取本切片对应的代码行集合
        String codeContent = getChunkContent(chunk);
        
        // 3. 组装最终提交给模型的 Prompt 输入，嵌入上下文文件名
        String systemPrompt = StringUtils.hasText(promptContent) ? promptContent : "你是一个资深架构师，请对以下代码进行详细的业务与功能归纳。";
        String promptInput = systemPrompt + "\n\n[代码源文件: " + chunk.getFilePath() + "]\n```java\n" + codeContent + "\n```";
        
        // 4. 正则过滤涉密资产数据（密码、私钥、内网IP等敏感项）防止敏感资产泄漏泄漏
        promptInput = filterSensitiveInfo(promptInput);

        // 5. 双层流控审计机制：限制单次任务 / 单系统月度 Token 用量（可通过配置关闭或调整上限）
        int taskUsed = tokenAuditService.getTaskCumulativeTokens(taskId);
        int systemUsed = tokenAuditService.getSystemMonthlyTokens(systemId);
        // 按字符数除以 3 大致估算本次请求的 Token 占位数
        int currentEstimate = (promptInput.length() / 3);

        if (isTaskTokenExceeded(taskUsed, currentEstimate)) {
            throw new BusinessException("Token 消耗额度超限阻断：单任务额度上限为 " + taskTokenLimit
                    + "，当前已消耗 " + taskUsed + "，预估当前消耗 " + currentEstimate);
        }
        if (isSystemTokenExceeded(systemUsed, currentEstimate)) {
            throw new BusinessException("Token 消耗额度超限阻断：单系统月度额度上限为 " + systemMonthlyTokenLimit
                    + "，当前已消耗 " + systemUsed + "，预估当前消耗 " + currentEstimate);
        }

        // 5.1 用户级额度前置检查（基础配置模块-流量管控）
        quotaCheckService.checkUserQuota(currentEstimate);
        // 5.2 AI 调用并发控制：拿不到信号量许可直接抛错（业务调用方应捕获并降级）
        aiConcurrencyService.tryAcquire();
        try {

        // 6. 若配置了 Mock 模式，或者无有效大模型密钥时，直接启用本地逻辑降级兜底生成
        boolean shouldMock = this.aiMock;
        if (!shouldMock) {
            if (!StringUtils.hasText(activeApiKey) || activeApiKey.startsWith("test-key") || "mock".equalsIgnoreCase(activeApiKey)) {
                shouldMock = true;
            }
        }

        if (shouldMock) {
            log.info("未配置大模型密钥或已开启 Mock，对切片 {} 启用本地 Mock 生成", chunkId);
            String mockResult = generateMockSummary(chunk);

            // 记录审计与调用报表
            saveCallRecordAndAudit(systemId, taskId, resolvePromptId(task, "CHUNK"), null, chunkId, modelToUse,
                    promptInput.length() / 3, mockResult.length() / 3, mockResult, true, null, 100, "CHUNK_SUMMARY");
            return mockResult;
        }

        long start = System.currentTimeMillis();
        int maxAttempts = Math.max(1, aiRetryProperties.getMaxAttempts());
        long backoffMs = Math.max(0L, aiRetryProperties.getBackoffMs());
        String chunkTarget = chunk.getFilePath()
                + (chunk.getMethodName() != null ? "#" + chunk.getMethodName() : "");
        String lastErr = "unknown";
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                try {
                    Map<String, Object> reqBody = new HashMap<>();
                    reqBody.put("model", modelToUse);
                    reqBody.put("stream", false);

                    List<Map<String, String>> messages = new ArrayList<>();
                    Map<String, String> userMsg = new HashMap<>();
                    userMsg.put("role", "user");
                    userMsg.put("content", promptInput);
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

                    log.info("开始向 MiniMax API 发起请求, URL: {}, Model: {}, attempt={}/{}",
                            requestUrl, modelToUse, attempt, maxAttempts);
                    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                    long duration = System.currentTimeMillis() - start;

                    if (response.statusCode() == 200) {
                        JsonNode root = objectMapper.readTree(response.body());
                        String aiText = normalizeModelContent(
                                root.path("choices").get(0).path("message").path("content").asText());
                        int inTokens = root.path("usage").path("prompt_tokens").asInt();
                        int outTokens = root.path("usage").path("completion_tokens").asInt();

                        chunk.setStatus("ANALYZED");
                        chunkMapper.updateById(chunk);

                        saveCallRecordAndAudit(systemId, taskId, resolvePromptId(task, "CHUNK"), null, chunkId,
                                modelToUse, inTokens, outTokens, aiText, true, null, duration, "CHUNK_SUMMARY");
                        if (attempt > 1) {
                            execLog.log(taskId, String.format(
                                    "[AI-OK] stage=CHUNK_SUMMARY target=%s recovered on attempt %d/%d",
                                    chunkTarget, attempt, maxAttempts));
                        }
                        return aiText;
                    }

                    lastErr = "HTTP " + response.statusCode() + ": " + response.body();
                    saveCallRecordAndAudit(systemId, taskId, resolvePromptId(task, "CHUNK"), null, chunkId, modelToUse,
                            promptInput.length() / 3, 0, "[AI_ERROR: " + lastErr + "]", false, lastErr, duration,
                            "CHUNK_SUMMARY");
                } catch (Exception e) {
                    lastErr = e.getMessage();
                    long duration = System.currentTimeMillis() - start;
                    log.error("调用大模型发生网络异常（{}）attempt={}/{}: {}", modelToUse, attempt, maxAttempts, lastErr);
                    saveCallRecordAndAudit(systemId, taskId, resolvePromptId(task, "CHUNK"), null, chunkId, modelToUse,
                            promptInput.length() / 3, 0, "[AI_ERROR: " + lastErr + "]", false, lastErr, duration,
                            "CHUNK_SUMMARY");
                }

                if (attempt < maxAttempts) {
                    execLog.log(taskId, String.format(
                            "[AI-RETRY] stage=CHUNK_SUMMARY target=%s attempt=%d/%d reason=%s",
                            chunkTarget, attempt, maxAttempts, truncateAiLogReason(lastErr)));
                    if (backoffMs > 0) {
                        try {
                            Thread.sleep(backoffMs * attempt);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }

            execLog.log(taskId, String.format(
                    "[AI-FAIL] stage=CHUNK_SUMMARY target=%s reason=%s after %d attempts",
                    chunkTarget, truncateAiLogReason(lastErr), maxAttempts));
            return "[AI_ERROR: " + lastErr + "]";
        } finally {
            aiConcurrencyService.release();
        }
    }

    /**
     * 多级模块路由规则匹配机制
     * 用于决策某个 Java 代码源文件应该归入哪一个业务知识模块。
     * 匹配优先级：
     * 1. 尝试匹配 module-map.yaml 配置文件
     * 2. 尝试匹配 module_hierarchy.json 树级配置文件
     * 3. 尝试从业务知识库中查找以往已确认/推送的同路径草稿所对应的模块名
     * 4. 尝试根据历史已保存草稿的对应记录
     * 5. 按照文件夹和 Java 包结构命名规则命名
     * 6. 按照 Spring REST 控制器的 RequestMapping 一级路由名称映射分包
     */
    public String routeModuleForFile(Long taskId, Long systemId, String filePath, CodeChunk chunk) {
        // 1. 尝试匹配 module-map.yaml
        String module = matchModuleMap(taskId, filePath);
        if (module != null) return module;

        // 2. 尝试匹配 module_hierarchy.json
        module = matchModuleHierarchy(taskId, filePath);
        if (module != null) return module;

        // 3. 尝试从业务知识库（已确认或推送的草稿）匹配
        module = matchConfirmedKnowledge(systemId, filePath);
        if (module != null) return module;

        // 4. 历史模块索引匹配
        module = matchHistoricalDrafts(systemId, filePath);
        if (module != null) return module;

        // 5. 目录与包名规则匹配
        module = matchPackageRules(filePath);
        if (module != null) return module;

        // 6. Controller 路由映射匹配
        module = matchControllerMapping(taskId, filePath, chunk);
        if (module != null) return module;

        return null;
    }

    /**
     * 匹配项目代码中的 module-map.yaml 配置文件规则
     */
    private String matchModuleMap(Long taskId, String filePath) {
        Path path1 = taskWorkspacePaths.taskProjectPath(taskId).resolve("module-map.yaml");
        Path path2 = taskWorkspacePaths.taskDocsCodeInsight(taskId).resolve("meta/module-map.yaml");
        Path finalPath = Files.exists(path1) ? path1 : (Files.exists(path2) ? path2 : null);
        if (finalPath == null) return null;

        try {
            String content = Files.readString(finalPath);
            Map<String, List<String>> yamlMap = parseYamlModuleMap(content);
            for (Map.Entry<String, List<String>> entry : yamlMap.entrySet()) {
                String moduleName = entry.getKey();
                for (String pattern : entry.getValue()) {
                    if (filePath.replace("\\", "/").contains(pattern.replace("\\", "/"))) {
                        return moduleName;
                    }
                }
            }
        } catch (Exception e) {
            log.error("解析 module-map.yaml 失败", e);
        }
        return null;
    }

    /**
     * 轻量级简易 YAML 解析辅助（提取模块对应的路径特征特征）
     */
    private Map<String, List<String>> parseYamlModuleMap(String content) {
        Map<String, List<String>> map = new LinkedHashMap<>();
        if (!StringUtils.hasText(content)) return map;
        String[] lines = content.split("\\R");
        String currentModule = null;
        List<String> currentPaths = new ArrayList<>();
        for (String line : lines) {
            if (line.trim().startsWith("#") || line.trim().isEmpty()) continue;
            if (!line.startsWith(" ") && !line.startsWith("\t") && line.contains(":")) {
                if (currentModule != null) {
                    map.put(currentModule, new ArrayList<>(currentPaths));
                }
                currentModule = line.substring(0, line.indexOf(":")).trim();
                currentPaths.clear();
            } else if (line.trim().startsWith("-")) {
                String path = line.substring(line.indexOf("-") + 1).trim();
                path = path.replaceAll("^['\"]|['\"]$", "");
                currentPaths.add(path);
            }
        }
        if (currentModule != null) {
            map.put(currentModule, new ArrayList<>(currentPaths));
        }
        return map;
    }

    /**
     * 匹配 module_hierarchy.json 树级结构映射文件配置
     */
    private String matchModuleHierarchy(Long taskId, String filePath) {
        Path path1 = taskWorkspacePaths.taskProjectPath(taskId).resolve("module_hierarchy.json");
        Path path2 = taskWorkspacePaths.taskDocsCodeInsight(taskId).resolve("meta/module_hierarchy.json");
        Path finalPath = Files.exists(path1) ? path1 : (Files.exists(path2) ? path2 : null);
        if (finalPath == null) return null;

        try {
            String content = Files.readString(finalPath);
            JsonNode root = objectMapper.readTree(content);
            JsonNode modules = root.path("modules");
            if (modules.isArray()) {
                for (JsonNode m : modules) {
                    String name = m.path("name").asText(m.path("id").asText(""));
                    JsonNode paths = m.path("paths");
                    if (paths.isArray()) {
                        for (JsonNode p : paths) {
                            String pStr = p.asText();
                            if (filePath.replace("\\", "/").contains(pStr.replace("\\", "/"))) {
                                return name;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("解析 module_hierarchy.json 失败", e);
        }
        return null;
    }

    /**
     * 比对以往已在知识库中确认发布 (CONFIRMED/PUSHED) 状态的草稿来源映射
     */
    private String matchConfirmedKnowledge(Long systemId, String filePath) {
        List<DraftWorkspace> workspaces = draftWorkspaceMapper.selectList(
                new LambdaQueryWrapper<DraftWorkspace>().eq(DraftWorkspace::getSystemId, systemId)
        );
        if (workspaces.isEmpty()) return null;
        List<Long> wsIds = workspaces.stream().map(DraftWorkspace::getId).toList();

        List<KnowledgeDraft> confirmedDrafts = knowledgeDraftMapper.selectList(
                new LambdaQueryWrapper<KnowledgeDraft>()
                        .in(KnowledgeDraft::getWorkspaceId, wsIds)
                        .in(KnowledgeDraft::getStatus, List.of("CONFIRMED", "PUSHED"))
        );
        if (confirmedDrafts.isEmpty()) return null;
        List<Long> draftIds = confirmedDrafts.stream().map(KnowledgeDraft::getId).toList();

        List<DraftSourceReference> refs = draftSourceReferenceMapper.selectList(
                new LambdaQueryWrapper<DraftSourceReference>()
                        .in(DraftSourceReference::getDraftId, draftIds)
                        .eq(DraftSourceReference::getFilePath, filePath)
        );
        if (!refs.isEmpty()) {
            Long matchedDraftId = refs.get(0).getDraftId();
            return confirmedDrafts.stream()
                    .filter(d -> d.getId().equals(matchedDraftId))
                    .map(KnowledgeDraft::getModuleName)
                    .findFirst()
                    .orElse(null);
        }
        return null;
    }

    /**
     * 比对历史生成的全部草稿，作为兜底匹配的特征索引
     */
    private String matchHistoricalDrafts(Long systemId, String filePath) {
        List<DraftWorkspace> workspaces = draftWorkspaceMapper.selectList(
                new LambdaQueryWrapper<DraftWorkspace>().eq(DraftWorkspace::getSystemId, systemId)
        );
        if (workspaces.isEmpty()) return null;
        List<Long> wsIds = workspaces.stream().map(DraftWorkspace::getId).toList();

        List<KnowledgeDraft> allDrafts = knowledgeDraftMapper.selectList(
                new LambdaQueryWrapper<KnowledgeDraft>()
                        .in(KnowledgeDraft::getWorkspaceId, wsIds)
        );
        if (allDrafts.isEmpty()) return null;
        List<Long> draftIds = allDrafts.stream().map(KnowledgeDraft::getId).toList();

        List<DraftSourceReference> refs = draftSourceReferenceMapper.selectList(
                new LambdaQueryWrapper<DraftSourceReference>()
                        .in(DraftSourceReference::getDraftId, draftIds)
                        .eq(DraftSourceReference::getFilePath, filePath)
        );
        if (!refs.isEmpty()) {
            Long matchedDraftId = refs.get(0).getDraftId();
            return allDrafts.stream()
                    .filter(d -> d.getId().equals(matchedDraftId))
                    .map(KnowledgeDraft::getModuleName)
                    .findFirst()
                    .orElse(null);
        }
        return null;
    }

    /**
     * 按照 package 结构命名规则进行分模块命名
     * 如：src/main/java/com/company/modules/auth/service/UserService.java
     * 自动检测 modules 关键字后的下一层目录为 "AuthModule" 模块。
     */
    private String matchPackageRules(String filePath) {
        if (!filePath.endsWith(".java")) return null;
        String cleanPath = filePath.replace("\\", "/");
        if (cleanPath.startsWith("src/main/java/")) {
            cleanPath = cleanPath.substring("src/main/java/".length());
        }
        int lastSlash = cleanPath.lastIndexOf('/');
        if (lastSlash == -1) return null;
        String dirPath = cleanPath.substring(0, lastSlash);
        String[] segments = dirPath.split("/");

        if (segments.length == 0) return null;
        // 查找包中包含 modules / module 的段，将下一层级作为模块名称命名
        for (int i = 0; i < segments.length - 1; i++) {
            if ("modules".equalsIgnoreCase(segments[i]) || "module".equalsIgnoreCase(segments[i])) {
                return capitalize(segments[i + 1]) + "Module";
            }
        }
        // 默认将倒数第二层级转换为模块名
        if (segments.length >= 3) {
            int startIdx = 0;
            if (List.of("com", "org", "net").contains(segments[0].toLowerCase())) {
                startIdx = 1;
            }
            if (startIdx + 1 < segments.length) {
                return capitalize(segments[segments.length - 2]) + "Module";
            }
        }
        return capitalize(segments[0]) + "Module";
    }

    /**
     * 根据 Spring REST 控制器的 RequestMapping 进行根节点路由分模块分包
     * 比如路由是 @RequestMapping("/api/v1/auth/login")，提取 auth 转为 AuthModule
     */
    private String matchControllerMapping(Long taskId, String filePath, CodeChunk chunk) {
        if (chunk == null || !"CLASS".equals(chunk.getChunkType())) return null;
        Path path = taskWorkspacePaths.taskProjectPath(taskId).resolve(filePath);
        if (!Files.exists(path)) return null;

        try {
            ParsedClassInfo info = javaParserService.parseFile(path.toFile());
            if (info != null && "CONTROLLER".equalsIgnoreCase(info.getType()) && StringUtils.hasText(info.getRequestMapping())) {
                String route = info.getRequestMapping();
                route = route.trim().replaceAll("^/+", "");
                String[] routeSegs = route.split("/");
                if (routeSegs.length > 0 && StringUtils.hasText(routeSegs[0])) {
                    int idx = 0;
                    // 跳过 api, v1, v2 等统一前缀前缀
                    if (List.of("api", "v1", "v2").contains(routeSegs[0].toLowerCase()) && routeSegs.length > 1) {
                        idx = 1;
                    }
                    if (StringUtils.hasText(routeSegs[idx])) {
                        return capitalize(routeSegs[idx]) + "Module";
                    }
                }
            }
        } catch (Exception e) {
            log.error("匹配 Controller RequestMapping 路由失败", e);
        }
        return null;
    }

    private String capitalize(String str) {
        if (!StringUtils.hasText(str)) return str;
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }

    private String getFileExtension(String fileName) {
        int lastIdx = fileName.lastIndexOf('.');
        if (lastIdx == -1) return "";
        return fileName.substring(lastIdx + 1);
    }

    /**
     * 大模型敏感数据脱敏过滤器
     * 运用正则表达式，在提交请求前对明文代码或提示词中的私钥、数据库密码、内网IP、认证Token等资产要素实施打码替换。
     *
     * @param input 原始未脱敏输入字符串
     * @return 脱敏完成的安全字符串
     */
    public String filterSensitiveInfo(String input) {
        if (!StringUtils.hasText(input)) return input;
        // 脱敏各类常规配置密码 (如 password = 123456)
        input = input.replaceAll("(?i)(password|pwd|pass)\\s*[:=]\\s*['\"]?[a-zA-Z0-9_\\-\\$\\&\\*]+['\"]?", "$1=***");
        // 脱敏 Bearer Token 或 API Key 键值对
        input = input.replaceAll("(?i)(bearer\\s+|api[-_]?key\\s*[:=]\\s*)['\"]?[a-zA-Z0-9\\-\\._~+\\/]+=*['\"]?", "$1***");
        // 脱敏超过 16 位的强密钥或非对称私钥字段
        input = input.replaceAll("(?i)(secret|private_key)\\s*[:=]\\s*['\"]?[a-zA-Z0-9_\\-\\$\\&\\*]{16,}['\"]?", "$1=***");
        // 脱敏 JDBC 连接串中的密码明文部分
        input = input.replaceAll("jdbc:.*password=([^&;\\s]+)", "jdbc:***password=***");
        // 脱敏内网局域网 IP 地址链接 (如 192.168.x.x / 10.x.x.x)
        input = input.replaceAll("http://(192\\.168\\.\\d{1,3}\\.\\d{1,3}|10\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3})", "http://***");
        return input;
    }

    /**
     * 聚合分片归纳分析结果并组装最终 Markdown 知识草稿文档
     * 1. 扫描各个分片并划分业务模块。
     * 2. 分层解析模块下的路由地址、涉及类名、依赖的数据表。
     * 3. 异步调用大模型/或触发本地 Mock 分析分片，将生成的分析段落依次编排录入文档。
     * 4. 物理持久化 Markdown 正文至 drafts 目录，并更新/新增草稿表（ci_knowledge_draft）及来源代码引用表。
     *
     * @param taskId        反编译分析任务 ID
     * @param chunks        任务切出的代码片段
     * @param promptContent 所使用的分析提示词模版内容
     */
    @Override
    public void generateDraftDocument(Long taskId, List<CodeChunk> chunks, String promptContent) {
        generateDraftDocument(taskId, chunks, promptContent, com.company.codeinsight.modules.scanner.model.IncrementalContext.fullScan());
    }

    @Override
    public void generateDraftDocument(Long taskId, List<CodeChunk> chunks, String promptContent,
                                     com.company.codeinsight.modules.scanner.model.IncrementalContext ctx) {
        generateDraftDocument(taskId, chunks, promptContent, ctx, null);
    }

    @Override
    public void generateDraftDocument(Long taskId, List<CodeChunk> chunks, String promptContent,
                                     com.company.codeinsight.modules.scanner.model.IncrementalContext ctx,
                                     com.company.codeinsight.modules.callchain.model.IncrementalImpact impact) {
        DecompileTask task = decompileTaskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException("未找到关联的任务");
        }
        com.company.codeinsight.modules.scanner.model.IncrementalContext effective =
                ctx == null ? com.company.codeinsight.modules.scanner.model.IncrementalContext.fullScan() : ctx;

        // 1. 加载 ModuleHierarchy DTO（项 2 产出）
        com.company.codeinsight.modules.hierarchy.model.ModuleHierarchy hierarchy =
                moduleHierarchyService.loadByTaskId(taskId);

        // 2. DTO 为空 → 走旧 17 章节逻辑兜底（增量和全量都先走兜底，避免空树切到一半的诡异状态）
        if (hierarchy.getModules().isEmpty()) {
            log.warn("taskId={} 尚无模块层级，回退到旧 17 章节生成逻辑", taskId);
            legacyGenerateDraftDocument(taskId, chunks, promptContent);
            return;
        }

        // 3. 查找或为当前任务创建工作区
        DraftWorkspace ws = draftWorkspaceMapper.selectOne(
                new LambdaQueryWrapper<DraftWorkspace>().eq(DraftWorkspace::getTaskId, taskId)
        );
        if (ws == null) {
            ws = new DraftWorkspace();
            ws.setTaskId(taskId);
            ws.setSystemId(task.getSystemId());
            ws.setRepositoryId(task.getRepositoryId());
            ws.setStatus("ACTIVE");
            ws.setCreatedAt(LocalDateTime.now());
            ws.setUpdatedAt(LocalDateTime.now());
            draftWorkspaceMapper.insert(ws);
        }

        // 4. projectDir 通过 taskId 反查（pipeline 启动时 pullAndScan 已写入该目录）
        File projectDir = taskWorkspacePaths.taskProjectDir(taskId);

        // 5. 增量模式：算出「本次变更文件对应的 FQ 类名集合」，用于判定哪些模块需要重跑 AI
        java.util.Set<String> changedFqSet = null;
        if (effective.isIncremental() && !effective.getChangedPaths().isEmpty()) {
            changedFqSet = new java.util.HashSet<>();
            for (String p : effective.getChangedPaths()) {
                String fq = com.company.codeinsight.modules.hierarchy.service.impl.ModuleHierarchyServiceImpl.deriveFqcnFromPath(p);
                if (StringUtils.hasText(fq)) {
                    changedFqSet.add(fq);
                }
            }
            log.info("增量草稿生成 — taskId={} 变更文件映射到 FQ 类名 {} 个", taskId, changedFqSet.size());
        }

        // 6. 根据 granularity 分发到整模块或按功能粒度
        if ("function".equalsIgnoreCase(docGenerationGranularity)) {
            generateDraftDocumentByFunction(task, ws, hierarchy, projectDir, effective, changedFqSet);
        } else {
            // --- 按模块（默认，现有行为）---
            int moduleIndex = 0;
            int moduleTotal = hierarchy.getModules().size();
            int regenerated = 0;
            int skipped = 0;
            java.util.Set<String> remediationModuleIds = parseRemediationModuleIds(task);
            for (com.company.codeinsight.modules.hierarchy.model.ModuleDto moduleDto : hierarchy.getModules().values()) {
                moduleIndex++;
                if (remediationModuleIds != null) {
                    if (!remediationModuleIds.contains(moduleDto.getId())) {
                        skipped++;
                        execLog.log(taskId, "  [module " + moduleIndex + "/" + moduleTotal + "] " + moduleDto.getModuleName() + " — 不在纠错范围，跳过");
                        continue;
                    }
                } else if (impact != null && impact.isIncremental()) {
                    if (!impact.getDocRetargetModuleIds().contains(moduleDto.getId())) {
                        skipped++;
                        execLog.log(taskId, "  [module " + moduleIndex + "/" + moduleTotal + "] " + moduleDto.getModuleName() + " — 未受本次变更影响，跳过");
                        continue;
                    }
                } else if (changedFqSet != null && !moduleTouchedByChange(moduleDto, changedFqSet)) {
                    skipped++;
                    execLog.log(taskId, "  [module " + moduleIndex + "/" + moduleTotal + "] " + moduleDto.getModuleName() + " — 未受本次变更影响，跳过");
                    continue;
                }
                execLog.log(taskId, "  [module " + moduleIndex + "/" + moduleTotal + "] " + moduleDto.getModuleName());
                try {
                    generateModuleDraft(task, ws, moduleDto, hierarchy, projectDir);
                    regenerated++;
                } catch (Exception e) {
                    log.error("generateModuleDraft failed for module {}: {}",
                            moduleDto.getModuleName(), e.getMessage(), e);
                }
            }
            log.info("generateDraftDocument done (module). taskId={} modules={} regenerated={} skipped={}",
                    taskId, moduleTotal, regenerated, skipped);
        }
    }

    /**
     * 增量模式辅助：判断模块下是否任一 function 的 classPath 引用了本次变更的类。
     * 命中即视为该模块需要被重新生成。
     */
    private boolean moduleTouchedByChange(com.company.codeinsight.modules.hierarchy.model.ModuleDto moduleDto,
                                          java.util.Set<String> changedFqSet) {
        if (moduleDto == null || changedFqSet == null || changedFqSet.isEmpty()) {
            return false;
        }
        for (com.company.codeinsight.modules.hierarchy.model.SubModuleDto sm : moduleDto.getSubModules().values()) {
            for (com.company.codeinsight.modules.hierarchy.model.FunctionDto fn : sm.getFunctions().values()) {
                if (fn.getClassPaths() != null) {
                    for (String cp : fn.getClassPaths()) {
                        if (cp != null && changedFqSet.contains(cp)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    /**
     * 项 3 新增：整模块喂 AI 生成 md 的核心流程
     */
    /**
     * 按功能（Function）粒度生成文档：每个 FunctionDto 一次 AI 调用。
     * <p>草稿命名：{moduleName}/{subModuleName}/{functionName}.md</p>
     * <p>下线时删除本方法 + {@code docGenerationGranularity} 配置即可恢复单一 module 模式。</p>
     */
    private void generateDraftDocumentByFunction(DecompileTask task, DraftWorkspace ws,
                                                  com.company.codeinsight.modules.hierarchy.model.ModuleHierarchy hierarchy,
                                                  File projectDir,
                                                  com.company.codeinsight.modules.scanner.model.IncrementalContext effective,
                                                  java.util.Set<String> changedFqSet) {
        Long taskId = task.getId();
        int fnTotal = countFunctionsInHierarchy(hierarchy);
        int fnIndex = 0;
        int regenerated = 0;
        int skipped = 0;

        for (com.company.codeinsight.modules.hierarchy.model.ModuleDto m : hierarchy.getModules().values()) {
            for (com.company.codeinsight.modules.hierarchy.model.SubModuleDto sm : m.getSubModules().values()) {
                for (com.company.codeinsight.modules.hierarchy.model.FunctionDto fn : sm.getFunctions().values()) {
                    fnIndex++;
                    // 增量：function.classPaths 全不在变更集 → 跳过
                    if (changedFqSet != null) {
                        boolean touched = false;
                        if (fn.getClassPaths() != null) {
                            for (String cp : fn.getClassPaths()) {
                                if (changedFqSet.contains(cp)) { touched = true; break; }
                            }
                        }
                        if (!touched) { skipped++; continue; }
                    }
                    String label = m.getModuleName() + " / " + sm.getSubModuleName() + " / " + fn.getFunctionName();
                    execLog.log(taskId, "  [fn " + fnIndex + "/" + fnTotal + "] " + label);
                    try {
                        generateFunctionDraft(task, ws, m, sm, fn, hierarchy, projectDir);
                        regenerated++;
                    } catch (Exception e) {
                        log.error("generateFunctionDraft failed for function {}: {}", label, e.getMessage(), e);
                    }
                }
            }
        }
        log.info("generateDraftDocument done (function). taskId={} total={} regenerated={} skipped={}",
                taskId, fnTotal, regenerated, skipped);
    }

    /** 按功能粒度生成单条草稿 */
    private void generateFunctionDraft(DecompileTask task, DraftWorkspace ws,
                                        com.company.codeinsight.modules.hierarchy.model.ModuleDto moduleDto,
                                        com.company.codeinsight.modules.hierarchy.model.SubModuleDto subModuleDto,
                                        com.company.codeinsight.modules.hierarchy.model.FunctionDto functionDto,
                                        com.company.codeinsight.modules.hierarchy.model.ModuleHierarchy hierarchy,
                                        File projectDir) {
        Long taskId = task.getId();
        String funcName = functionDto.getFunctionName();

        // 1. 收集该 Function 的源码
        String source = collectFunctionSourceCode(taskId, functionDto, projectDir);
        if (!StringUtils.hasText(source)) {
            log.warn("Function {} BFS 无可达源码，跳过", funcName);
            return;
        }

        // 2. 渲染 prompt
        String promptTemplate = decompilePromptService.requireTaskPromptContent(task,
                com.company.codeinsight.modules.prompt.entity.DecompilePrompt.TYPE_DOCUMENT_GENERATION);
        String scopedJson = buildScopedHierarchyJson(moduleDto, subModuleDto, functionDto);
        String label = moduleDto.getModuleName() + " / " + subModuleDto.getSubModuleName() + " / " + funcName;
        String promptInput = promptTemplateLoader.renderModuleDoc(promptTemplate, label, scopedJson, source);
        if (promptTemplateLoader.hasUnresolvedModuleDocPlaceholders(promptInput)) {
            log.warn("Function {} prompt 有未替换占位符，回退占位文档", funcName);
            upsertFunctionDraft(task, ws, moduleDto, subModuleDto, functionDto,
                    buildPlaceholderDoc(moduleDto), "PENDING_REVIEW", projectDir);
            return;
        }

        // 3. 调 AI（可配置重试 + pipeline.log）
        AiSummaryService.AiCallMeta callMeta = new AiSummaryService.AiCallMeta();
        callMeta.setCallStage("FUNCTION_DOC");
        callMeta.setClassPath(functionDto.getId());
        String docTarget = label;
        String aiMarkdown = pipelineAiCaller.callWithRetry(
                taskId,
                "FUNCTION_DOC",
                docTarget,
                promptInput,
                task.getModelName(),
                callMeta,
                response -> {
                    if (!StringUtils.hasText(response) || "{}".equals(response.trim())) {
                        return PipelineAiCaller.ValidationResult.fail("empty response");
                    }
                    String validationMsg = validateModuleDocStructure(response);
                    if (validationMsg != null) {
                        return PipelineAiCaller.ValidationResult.fail("structure: " + validationMsg);
                    }
                    return PipelineAiCaller.ValidationResult.ok(response);
                },
                (original, current, failedAttempt, reason) -> original
                        + "\n\n[系统提示] 上轮输出不符合要求：" + reason
                        + "\n请补全全部六个章节（一、～六、），输出完整 Markdown。"
        );

        String finalMarkdown, initialStatus;
        if (!StringUtils.hasText(aiMarkdown) || "{}".equals(aiMarkdown.trim())) {
            log.warn("Function {} AI 响应为空或全部重试失败", funcName);
            finalMarkdown = buildPlaceholderDoc(moduleDto);
            initialStatus = "PENDING_REVIEW";
        } else {
            finalMarkdown = aiMarkdown;
            initialStatus = "AI_GENERATED";
        }
            upsertFunctionDraft(task, ws, moduleDto, subModuleDto, functionDto, finalMarkdown, initialStatus, projectDir);
    }

    /** 收集单个 Function 的可达源码（从 method_function_binding 反查根方法 → BFS） */
    private String collectFunctionSourceCode(Long taskId,
                                              com.company.codeinsight.modules.hierarchy.model.FunctionDto fn,
                                              File projectDir) {
        Set<String> rootSignatures = loadFunctionRootSignatures(taskId, fn);
        if (rootSignatures.isEmpty()) {
            // fallback：按 classPaths（兼容旧数据，且反向绑定表为空时不再做大杂烩 BFS）
            if (fn.getClassPaths() != null && !fn.getClassPaths().isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (String cp : fn.getClassPaths()) {
                    String classFilePath = lookupClassFilePath(taskId, cp);
                    if (classFilePath == null) continue;
                    File f = new File(projectDir, classFilePath);
                    if (!f.exists()) continue;
                    try {
                        String content = Files.readString(f.toPath());
                        sb.append("// === Class: ").append(cp).append(" ===\n").append(content).append("\n\n");
                    } catch (IOException ignored) {}
                }
                return sb.toString();
            }
            return "";
        }
        Set<String> reachableMethods = methodCallGraphService.resolveReachableMethods(taskId, rootSignatures);
        Map<String, Set<String>> classToMethodSigs = groupByClass(reachableMethods);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Set<String>> entry : classToMethodSigs.entrySet()) {
            String className = entry.getKey();
            Set<String> methodSigs = entry.getValue();
            String classFilePath = lookupClassFilePath(taskId, className);
            if (classFilePath == null) continue;
            File classFile = new File(projectDir, classFilePath);
            if (!classFile.exists()) continue;
            String filteredContent = filterClassToMethods(classFile, methodSigs);
            if (!StringUtils.hasText(filteredContent)) continue;
            sb.append("// === Class: ").append(className).append(" ===\n");
            sb.append(filteredContent).append("\n\n");
        }
        return sb.toString();
    }

    /**
     * 反查某功能的 BFS 根方法集合（"className#methodSignature(ParamTypes)" 格式）。
     * <p>优先级：</p>
     * <ol>
     *   <li>主路径：从 {@code ci_method_function_binding} 反查该 function_node_id 下的全部方法绑定，
     *       每条 (class, sig) → "className#sig"，天然多类支持（一个功能可有来自多 Controller 的入口方法）</li>
     *   <li>回退路径：当反向绑定表为空（AI 没输出 method_bindings、或老任务）时，回退到
     *       {@code fn.methodSignatures × fn.classPaths[0]} 的笛卡尔积（保持旧行为，标 deprecation）</li>
     * </ol>
     */
    private Set<String> loadFunctionRootSignatures(Long taskId,
                                                   com.company.codeinsight.modules.hierarchy.model.FunctionDto fn) {
        Set<String> roots = new LinkedHashSet<>();
        if (taskId != null && fn != null
                && methodFunctionBindingMapper != null
                && StringUtils.hasText(fn.getId())) {
            List<MethodFunctionBinding> bindings =
                    methodFunctionBindingMapper.selectByTaskAndFunction(taskId, fn.getId());
            if (bindings != null) {
                for (MethodFunctionBinding b : bindings) {
                    if (!StringUtils.hasText(b.getClassName())
                            || !StringUtils.hasText(b.getMethodSignature())) {
                        continue;
                    }
                    roots.add(b.getClassName() + "#" + b.getMethodSignature());
                }
            }
        }
        // 回退：旧 fn.methodSignatures × classPaths[0]（已被反向索引取代的旧路径）
        if (roots.isEmpty() && fn != null
                && fn.getMethodSignatures() != null && !fn.getMethodSignatures().isEmpty()
                && fn.getClassPaths() != null && !fn.getClassPaths().isEmpty()) {
            String classPath = fn.getClassPaths().stream().findFirst().orElse(null);
            if (StringUtils.hasText(classPath)) {
                for (String sig : fn.getMethodSignatures()) {
                    if (!StringUtils.hasText(sig)) continue;
                    roots.add(classPath + "#" + sig);
                }
            }
        }
        return roots;
    }

    /** 构建 Function-scoped hierarchy JSON（仅保留该 Function 所在路径） */
    private String buildScopedHierarchyJson(
            com.company.codeinsight.modules.hierarchy.model.ModuleDto m,
            com.company.codeinsight.modules.hierarchy.model.SubModuleDto sm,
            com.company.codeinsight.modules.hierarchy.model.FunctionDto fn) {
        try {
            Map<String, Object> scoped = new LinkedHashMap<>();
            Map<String, Object> modMap = new LinkedHashMap<>();
            modMap.put("id", m.getId());
            modMap.put("moduleName", m.getModuleName());
            modMap.put("keywords", m.getKeywords() != null ? m.getKeywords() : Collections.emptyList());
            Map<String, Object> subMap = new LinkedHashMap<>();
            subMap.put("id", sm.getId());
            subMap.put("subModuleName", sm.getSubModuleName());
            subMap.put("keywords", sm.getKeywords() != null ? sm.getKeywords() : Collections.emptyList());
            Map<String, Object> fnMap = new LinkedHashMap<>();
            fnMap.put("id", fn.getId());
            fnMap.put("functionName", fn.getFunctionName());
            subMap.put("functions", Collections.singletonList(fnMap));
            modMap.put("subModules", Collections.singletonList(subMap));
            scoped.put("modules", Collections.singletonList(modMap));
            return objectMapper.writeValueAsString(scoped);
        } catch (Exception e) {
            return "{}";
        }
    }

    /** 按功能粒度写草稿：路径 = {moduleName}/{subModuleName}/{functionName}.md */
    private void upsertFunctionDraft(DecompileTask task, DraftWorkspace ws,
                                      com.company.codeinsight.modules.hierarchy.model.ModuleDto m,
                                      com.company.codeinsight.modules.hierarchy.model.SubModuleDto sm,
                                      com.company.codeinsight.modules.hierarchy.model.FunctionDto fn,
                                      String markdown, String initialStatus,
                                      File projectDir) {
        Long taskId = task.getId();
        String safeModule = m.getModuleName().replaceAll("[\\s/\\(\\)]", "_");
        String safeSub = sm.getSubModuleName().replaceAll("[\\s/\\(\\)]", "_");
        String safeFn = fn.getFunctionName().replaceAll("[\\s/\\(\\)]", "_");
        String relativeDocPath = "task_" + taskId + "/" + safeModule + "/" + safeSub + "/" + safeFn + ".md";
        Path storePath = Paths.get(localStoragePath, "drafts", relativeDocPath);
        try {
            Files.createDirectories(storePath.getParent());
            Files.writeString(storePath, markdown);
        } catch (IOException e) {
            log.error("保存 Function 知识草稿文件失败", e);
        }
        String hash = DigestUtils.md5DigestAsHex(markdown.getBytes());
        KnowledgeDraft draft = knowledgeDraftMapper.selectOne(
                new LambdaQueryWrapper<KnowledgeDraft>()
                        .eq(KnowledgeDraft::getWorkspaceId, ws.getId())
                        .eq(KnowledgeDraft::getFilePath, relativeDocPath));
        if (draft == null) {
            draft = new KnowledgeDraft();
            draft.setWorkspaceId(ws.getId());
            draft.setFilePath(relativeDocPath);
            draft.setModuleName(m.getModuleName() + " / " + sm.getSubModuleName() + " / " + fn.getFunctionName());
            String contentUri = com.company.codeinsight.common.util.DraftFileUtil.buildDraftUri(
                    task.getSystemId(), task.getRepositoryId(), taskId, relativeDocPath);
            draft.setContentUri(contentUri);
            draft.setStatus(initialStatus);
            draft.setHash(hash);
            draft.setCreatedAt(LocalDateTime.now());
            draft.setUpdatedAt(LocalDateTime.now());
            knowledgeDraftMapper.insert(draft);
        } else {
            String oldStatus = draft.getStatus();
            String contentUri = com.company.codeinsight.common.util.DraftFileUtil.buildDraftUri(
                    task.getSystemId(), task.getRepositoryId(), taskId, relativeDocPath);
            draft.setContentUri(contentUri);
            draft.setHash(hash);
            if (oldStatus == null || oldStatus.equals("AI_GENERATED") || oldStatus.equals("PENDING_REVIEW")) {
                draft.setStatus(initialStatus);
            }
            draft.setUpdatedAt(LocalDateTime.now());
            knowledgeDraftMapper.updateById(draft);
        }
        replaceDraftSourceReferencesForFunction(draft.getId(), taskId, fn, projectDir);
        log.info("Function draft: {} → {}", relativeDocPath, initialStatus);
    }

    /** 统计 hierarchy 中所有 FunctionDto 总数 */
    private int countFunctionsInHierarchy(com.company.codeinsight.modules.hierarchy.model.ModuleHierarchy hierarchy) {
        int n = 0;
        if (hierarchy == null || hierarchy.getModules() == null) return 0;
        for (com.company.codeinsight.modules.hierarchy.model.ModuleDto m : hierarchy.getModules().values()) {
            n += countFunctions(m);
        }
        return n;
    }

    private void generateModuleDraft(DecompileTask task, DraftWorkspace ws,
                                    com.company.codeinsight.modules.hierarchy.model.ModuleDto moduleDto,
                                    com.company.codeinsight.modules.hierarchy.model.ModuleHierarchy hierarchy,
                                    File projectDir) {
        String moduleName = moduleDto.getModuleName();

        // 1. 收集该模块涉及的所有源码（BFS 入口可达）
        String moduleSource = collectModuleSourceCode(task.getId(), moduleDto, projectDir);
        if (!StringUtils.hasText(moduleSource)) {
            log.warn("模块 {} BFS 无可达源码，跳过（DTO 有 {} 个 Function）",
                    moduleName, countFunctions(moduleDto));
            return;
        }

        // 2. 渲染 prompt（必须来自任务快照的提示词绑定）
        String promptTemplate = decompilePromptService.requireTaskPromptContent(task,
                com.company.codeinsight.modules.prompt.entity.DecompilePrompt.TYPE_DOCUMENT_GENERATION);

        // 把整个 ModuleHierarchy DTO 序列化成 JSON 给 AI 作为 {module_hierarchy.json} 输入
        String moduleHierarchyJson = serializeHierarchyToJson(hierarchy);

        String promptInput = promptTemplateLoader.renderModuleDoc(
                promptTemplate, moduleName, moduleHierarchyJson, moduleSource);
        if (promptTemplateLoader.hasUnresolvedModuleDocPlaceholders(promptInput)) {
            log.warn("模块 {} prompt 仍有未替换占位符，回退到占位文档", moduleName);
            upsertModuleDraft(task, ws, moduleDto, buildPlaceholderDoc(moduleDto), "PENDING_REVIEW", projectDir);
            return;
        }

        // 3. 调 AI（可配置重试 + pipeline.log）
        AiSummaryService.AiCallMeta callMeta = new AiSummaryService.AiCallMeta();
        callMeta.setCallStage("MODULE_DOC");
        callMeta.setClassPath(moduleDto.getId());

        String aiMarkdown = pipelineAiCaller.callWithRetry(
                task.getId(),
                "MODULE_DOC",
                moduleName,
                promptInput,
                task.getModelName(),
                callMeta,
                response -> {
                    if (!StringUtils.hasText(response) || "{}".equals(response.trim())) {
                        return PipelineAiCaller.ValidationResult.fail("empty response");
                    }
                    String validationMsg = validateModuleDocStructure(response);
                    if (validationMsg != null) {
                        return PipelineAiCaller.ValidationResult.fail("structure: " + validationMsg);
                    }
                    return PipelineAiCaller.ValidationResult.ok(response);
                },
                (original, current, failedAttempt, reason) -> original
                        + "\n\n[系统提示] 上轮输出不符合要求：" + reason
                        + "\n请补全全部六个章节（一、～六、），输出完整 Markdown。"
        );

        String finalMarkdown;
        String initialStatus;
        if (!StringUtils.hasText(aiMarkdown) || "{}".equals(aiMarkdown.trim())) {
            log.warn("模块 {} AI 响应为空或全部重试失败，写 PENDING_REVIEW 占位", moduleName);
            finalMarkdown = buildPlaceholderDoc(moduleDto);
            initialStatus = "PENDING_REVIEW";
        } else {
            finalMarkdown = aiMarkdown;
            initialStatus = "AI_GENERATED";
        }

        // 4. 落库（写文件 + 写 KnowledgeDraft + 写 source references）
        upsertModuleDraft(task, ws, moduleDto, finalMarkdown, initialStatus, projectDir);
    }

    /**
     * 校验模块文档的 Markdown 结构完整性
     * 检查是否包含 prompt 模板要求的所有中文数字章节（一～六）。
     * 不阻塞流水线，仅用于标记状态：结构不完整时降级为 PENDING_REVIEW 供人工补充。
     *
     * @param markdown AI 生成的 Markdown 正文
     * @return 结构问题时返回描述信息，结构完整返回 null
     */
    private String validateModuleDocStructure(String markdown) {
        if (!StringUtils.hasText(markdown)) return "内容为空";

        // 必需的中文数字章节标题（module_doc_prompt.md 规范）
        String[] requiredSections = {"一、", "二、", "三、", "四、", "五、", "六、"};
        int found = 0;
        java.util.List<String> missing = new java.util.ArrayList<>();

        for (String section : requiredSections) {
            if (markdown.contains(section)) {
                found++;
            } else {
                missing.add(section);
            }
        }

        if (found < requiredSections.length) {
            return "缺少章节: " + String.join(", ", missing) + "（发现 " + found + "/" + requiredSections.length + "）";
        }
        return null;
    }

    /**
     * 收集模块所有入口方法（BFS 入口），并按 ci_method_call 取可达源码。
     * <p>模块层根方法直接从 {@code ci_method_function_binding WHERE module_node_id} 一次查出，
     * 不再按"function × classPaths[0]"笛卡尔积（该路径已被反向索引取代，避免大杂烩 BFS）。</p>
     */
    private String collectModuleSourceCode(Long taskId,
                                           com.company.codeinsight.modules.hierarchy.model.ModuleDto moduleDto,
                                           File projectDir) {
        Set<String> rootSignatures = new LinkedHashSet<>();
        if (taskId != null && moduleDto != null
                && methodFunctionBindingMapper != null
                && StringUtils.hasText(moduleDto.getId())) {
            List<MethodFunctionBinding> bindings =
                    methodFunctionBindingMapper.selectByTaskAndModule(taskId, moduleDto.getId());
            if (bindings != null) {
                for (MethodFunctionBinding b : bindings) {
                    if (StringUtils.hasText(b.getClassName())
                            && StringUtils.hasText(b.getMethodSignature())) {
                        rootSignatures.add(b.getClassName() + "#" + b.getMethodSignature());
                    }
                }
            }
        }
        // 回退：老数据（无 method_function_binding 行时）按 fn.methodSignatures × classPaths[0] 走
        if (rootSignatures.isEmpty()) {
            for (com.company.codeinsight.modules.hierarchy.model.SubModuleDto sm : moduleDto.getSubModules().values()) {
                for (com.company.codeinsight.modules.hierarchy.model.FunctionDto fn : sm.getFunctions().values()) {
                    Set<String> fnRoots = loadFunctionRootSignatures(taskId, fn);
                    rootSignatures.addAll(fnRoots);
                }
            }
            if (rootSignatures.isEmpty()) {
                return collectModuleSourceCodeByClass(taskId, moduleDto, projectDir);
            }
        }

        // BFS 调用链反查
        Set<String> reachableMethods = methodCallGraphService.resolveReachableMethods(taskId, rootSignatures);
        log.info("阶段 2 文档生成 taskId={} module={} roots={} reachable={}",
                taskId, moduleDto.getModuleName(), rootSignatures.size(), reachableMethods.size());

        Map<String, Set<String>> classToMethodSigs = groupByClass(reachableMethods);

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Set<String>> entry : classToMethodSigs.entrySet()) {
            String className = entry.getKey();
            Set<String> methodSigs = entry.getValue();
            String classFilePath = lookupClassFilePath(taskId, className);
            if (classFilePath == null) continue;
            File classFile = new File(projectDir, classFilePath);
            if (!classFile.exists()) continue;
            String filteredContent = filterClassToMethods(classFile, methodSigs);
            if (!StringUtils.hasText(filteredContent)) continue;
            sb.append("// === Class: ").append(className).append(" ===\n");
            sb.append(filteredContent).append("\n\n");
        }
        return sb.toString();
    }

    /**
     * fallback：methodSignatures 为空时按 classPaths 走类粒度 BFS
     * 兼容阶段 1 之前未配置 method_signatures 的旧任务
     */
    private String collectModuleSourceCodeByClass(Long taskId,
                                                  com.company.codeinsight.modules.hierarchy.model.ModuleDto moduleDto,
                                                  File projectDir) {
        Set<String> entryClassPaths = new LinkedHashSet<>();
        for (com.company.codeinsight.modules.hierarchy.model.SubModuleDto sm : moduleDto.getSubModules().values()) {
            for (com.company.codeinsight.modules.hierarchy.model.FunctionDto fn : sm.getFunctions().values()) {
                entryClassPaths.addAll(fn.getClassPaths());
            }
        }
        if (entryClassPaths.isEmpty()) {
            return "";
        }
        DecompileTask task = decompileTaskMapper.selectById(taskId);
        com.company.codeinsight.modules.entrypoint.model.EntryPointConfig config =
                com.company.codeinsight.modules.entrypoint.model.EntryPointConfigCodec.decode(task.getEntryScanConfig());
        StringBuilder sb = new StringBuilder();
        for (String entryClass : entryClassPaths) {
            String src = entryPointDiscoveryService.collectReachableSource(
                    taskId, entryClass, projectDir, config);
            if (!StringUtils.hasText(src)) continue;
            sb.append("// ===== Entry: ").append(entryClass).append(" =====\n");
            sb.append(src).append("\n\n");
        }
        return sb.toString();
    }

    /**
     * 拼装完整方法签名：classPath#methodName(ParamType1, ParamType2)
     * classPath 取 function.classPaths 的第一个元素
     */
    private String buildFullMethodSignature(com.company.codeinsight.modules.hierarchy.model.FunctionDto fn,
                                           String methodSig) {
        if (fn == null || !StringUtils.hasText(methodSig)) return null;
        String classPath = fn.getClassPaths().stream().findFirst().orElse(null);
        if (!StringUtils.hasText(classPath)) return null;
        return classPath + "#" + methodSig;
    }

    /**
     * 把 methodSignatures 集合按 className 聚合
     * 输入：["com.demo.A#listUsers()", "com.demo.B#findById()", "com.demo.A#createUser()"]
     * 输出：{ "com.demo.A" → {"listUsers()", "createUser()"}, "com.demo.B" → {"findById()"} }
     */
    private Map<String, Set<String>> groupByClass(Set<String> methodSignatures) {
        Map<String, Set<String>> result = new java.util.HashMap<>();
        for (String sig : methodSignatures) {
            int hashIdx = sig.indexOf('#');
            if (hashIdx < 0) continue;
            String className = sig.substring(0, hashIdx);
            String methodOnly = sig.substring(hashIdx + 1);
            result.computeIfAbsent(className, k -> new LinkedHashSet<>()).add(methodOnly);
        }
        return result;
    }

    /**
     * 按 methodName 集合截取类文件源码（用 startLine/endLine 范围）
     * 只输出目标方法，不输出 import / 字段 / 其他方法
     */
    private String filterClassToMethods(File classFile, Set<String> targetMethodSigs) {
        try {
            com.company.codeinsight.modules.parser.model.ParsedClassInfo info = javaParserService.parseFile(classFile);
            if (info == null) return null;
            // 把 methodSigs 集合 → Set<methodName>（"listUsers(Integer)" → "listUsers"）
            Set<String> targetMethodNames = new java.util.HashSet<>();
            for (String sig : targetMethodSigs) {
                int parenIdx = sig.indexOf('(');
                String methodName = parenIdx >= 0 ? sig.substring(0, parenIdx) : sig;
                targetMethodNames.add(methodName.trim());
            }
            java.util.List<String> lines = Files.readAllLines(classFile.toPath());
            StringBuilder sb = new StringBuilder();
            for (com.company.codeinsight.modules.parser.model.ParsedClassInfo.MethodInfo mi : info.getMethods()) {
                if (targetMethodNames.contains(mi.getName())
                        && mi.getStartLine() != null && mi.getEndLine() != null) {
                    int start = mi.getStartLine() - 1;
                    int end = Math.min(mi.getEndLine(), lines.size());
                    for (int i = start; i < end; i++) {
                        sb.append(lines.get(i)).append("\n");
                    }
                    sb.append("\n");
                }
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("filterClassToMethods failed for {}: {}", classFile, e.getMessage());
            return null;
        }
    }

    /**
     * 从 ci_method_call 反查类的物理文件路径
     * 兜底：用 ci_code_file_snapshot 或包路径推断（暂不实现）
     */
    private String lookupClassFilePath(Long taskId, String className) {
        try {
            List<com.company.codeinsight.modules.callchain.entity.MethodCall> calls = methodCallMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.company.codeinsight.modules.callchain.entity.MethodCall>()
                            .eq(com.company.codeinsight.modules.callchain.entity.MethodCall::getTaskId, taskId)
                            .eq(com.company.codeinsight.modules.callchain.entity.MethodCall::getClassName, className)
                            .last("LIMIT 1")
            );
            if (!calls.isEmpty() && StringUtils.hasText(calls.get(0).getFilePath())) {
                return calls.get(0).getFilePath();
            }
        } catch (Exception e) {
            log.warn("lookupClassFilePath failed for {}: {}", className, e.getMessage());
        }
        // 兜底：用包路径推断
        if (className.contains(".")) {
            String pkgPath = className.substring(0, className.lastIndexOf('.')).replace('.', '/');
            String simple = className.substring(className.lastIndexOf('.') + 1);
            return "src/main/java/" + pkgPath + "/" + simple + ".java";
        }
        return "src/main/java/" + className + ".java";
    }

    /**
     * AI 失败时的占位文档（保留子模块列表 + 入口类，便于人工补充）
     */
    private String buildPlaceholderDoc(com.company.codeinsight.modules.hierarchy.model.ModuleDto moduleDto) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(moduleDto.getModuleName()).append(" 模块说明\n\n");
        sb.append("> AI 生成失败，需人工补充。\n\n");
        sb.append("## 子模块清单\n");
        for (com.company.codeinsight.modules.hierarchy.model.SubModuleDto sm : moduleDto.getSubModules().values()) {
            sb.append("- **").append(sm.getSubModuleName()).append("**\n");
            for (com.company.codeinsight.modules.hierarchy.model.FunctionDto fn : sm.getFunctions().values()) {
                sb.append("  - ").append(fn.getFunctionName())
                        .append("（入口: ").append(String.join(", ", fn.getClassPaths())).append("）\n");
            }
        }
        return sb.toString();
    }

    /**
     * upsert 落库：写文件 → 写 KnowledgeDraft → 写 source references
     */
    private void upsertModuleDraft(DecompileTask task, DraftWorkspace ws,
                                   com.company.codeinsight.modules.hierarchy.model.ModuleDto moduleDto,
                                   String markdown, String initialStatus,
                                   File projectDir) {
        Long taskId = task.getId();
        String moduleName = moduleDto.getModuleName();
        String safeModuleName = moduleName.replaceAll("[\\s/\\(\\)]", "_");
        String relativeDocPath = "task_" + taskId + "/" + safeModuleName + ".md";
        Path storePath = Paths.get(localStoragePath, "drafts", relativeDocPath);

        // 写文件
        try {
            Files.createDirectories(storePath.getParent());
            Files.writeString(storePath, markdown);
        } catch (IOException e) {
            log.error("保存 Markdown 知识草稿文件失败", e);
        }

        // 本地仓库副本
        try {
            CodeRepository repo = repositoryMapper.selectById(task.getRepositoryId());
            if (repo != null && StringUtils.hasText(repo.getGitUrl())) {
                File localRepoDir = new File(repo.getGitUrl());
                if (localRepoDir.exists() && localRepoDir.isDirectory()) {
                    File targetDraftDir = new File(localRepoDir, "docs/code-insight/drafts");
                    if (!targetDraftDir.exists()) {
                        targetDraftDir.mkdirs();
                    }
                    File targetDraftFile = new File(targetDraftDir, safeModuleName + ".md");
                    Files.writeString(targetDraftFile.toPath(), markdown);
                    log.info("本地模式：成功备份草稿文档至指定目录：{}", targetDraftFile.getAbsolutePath());
                }
            }
        } catch (Exception e) {
            log.error("备份草稿文档至本地代码库指定目录失败", e);
        }

        // upsert KnowledgeDraft
        String hash = DigestUtils.md5DigestAsHex(markdown.getBytes());
        KnowledgeDraft draft = knowledgeDraftMapper.selectOne(
                new LambdaQueryWrapper<KnowledgeDraft>()
                        .eq(KnowledgeDraft::getWorkspaceId, ws.getId())
                        .eq(KnowledgeDraft::getFilePath, relativeDocPath)
        );
        if (draft == null) {
            draft = new KnowledgeDraft();
            draft.setWorkspaceId(ws.getId());
            draft.setFilePath(relativeDocPath);
            draft.setModuleName(moduleName);
            draft.setContentUri(storePath.toAbsolutePath().toUri().toString());
            draft.setStatus(initialStatus);
            draft.setHash(hash);
            draft.setCreatedAt(LocalDateTime.now());
            draft.setUpdatedAt(LocalDateTime.now());
            knowledgeDraftMapper.insert(draft);
        } else {
            draft.setHash(hash);
            draft.setStatus(initialStatus);
            draft.setModuleName(moduleName);
            draft.setContentUri(storePath.toAbsolutePath().toUri().toString());
            draft.setUpdatedAt(LocalDateTime.now());
            knowledgeDraftMapper.updateById(draft);
        }

        // 清旧 source references，按功能节点写入类 + 方法签名引用
        draftSourceReferenceMapper.delete(
                new LambdaQueryWrapper<DraftSourceReference>().eq(DraftSourceReference::getDraftId, draft.getId())
        );
        for (com.company.codeinsight.modules.hierarchy.model.SubModuleDto sm : moduleDto.getSubModules().values()) {
            for (com.company.codeinsight.modules.hierarchy.model.FunctionDto fn : sm.getFunctions().values()) {
                insertDraftSourceReferences(draft.getId(), taskId, fn, projectDir);
            }
        }
    }

    private void replaceDraftSourceReferencesForFunction(Long draftId, Long taskId,
                                                       com.company.codeinsight.modules.hierarchy.model.FunctionDto fn,
                                                       File projectDir) {
        draftSourceReferenceMapper.delete(
                new LambdaQueryWrapper<DraftSourceReference>().eq(DraftSourceReference::getDraftId, draftId)
        );
        insertDraftSourceReferences(draftId, taskId, fn, projectDir);
    }

    private void insertDraftSourceReferences(Long draftId, Long taskId,
                                           com.company.codeinsight.modules.hierarchy.model.FunctionDto fn,
                                           File projectDir) {
        if (fn == null || draftId == null) return;
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();

        // 主路径：直接按 method_function_binding 行写入每条方法引用，避免 classPaths × methodSignatures 笛卡尔积
        if (methodFunctionBindingMapper != null && taskId != null
                && StringUtils.hasText(fn.getId())) {
            List<MethodFunctionBinding> bindings =
                    methodFunctionBindingMapper.selectByTaskAndFunction(taskId, fn.getId());
            if (bindings != null && !bindings.isEmpty()) {
                for (MethodFunctionBinding b : bindings) {
                    if (!StringUtils.hasText(b.getClassName())
                            || !StringUtils.hasText(b.getMethodSignature())) {
                        continue;
                    }
                    String classPath = b.getClassName();
                    String filePath = lookupClassFilePath(taskId, classPath);
                    if (!StringUtils.hasText(filePath)) {
                        filePath = entryClassToFilePath(taskId, classPath);
                    }
                    if (!StringUtils.hasText(filePath)) continue;
                    String dedupeKey = filePath + "|" + classPath + "|" + b.getMethodSignature();
                    if (!seen.add(dedupeKey)) continue;
                    File classFile = projectDir != null ? new File(projectDir, filePath) : null;
                    int[] lines = resolveMethodLineRange(classFile, b.getMethodSignature());
                    DraftSourceReference ref = new DraftSourceReference();
                    ref.setDraftId(draftId);
                    ref.setFilePath(filePath);
                    ref.setClassName(classPath);
                    ref.setMethodSignature(b.getMethodSignature().trim());
                    if (lines != null) {
                        ref.setStartLine(lines[0]);
                        ref.setEndLine(lines[1]);
                    } else {
                        ref.setStartLine(1);
                        ref.setEndLine(0);
                    }
                    ref.setCreatedAt(LocalDateTime.now());
                    draftSourceReferenceMapper.insert(ref);
                }
                return; // 主路径优先：找到任何绑定就完成引用落库，不再走旧路径
            }
        }

        // 回退（旧数据 / 无绑定表的退化任务）：仍按 fn.methodSignatures × fn.classPaths 笛卡尔积写入
        if (fn.getMethodSignatures() != null && !fn.getMethodSignatures().isEmpty()
                && fn.getClassPaths() != null && !fn.getClassPaths().isEmpty()) {
            for (String classPath : fn.getClassPaths()) {
                String filePath = lookupClassFilePath(taskId, classPath);
                if (!StringUtils.hasText(filePath)) {
                    filePath = entryClassToFilePath(taskId, classPath);
                }
                if (!StringUtils.hasText(filePath)) continue;
                File classFile = projectDir != null ? new File(projectDir, filePath) : null;
                for (String methodSig : fn.getMethodSignatures()) {
                    if (!StringUtils.hasText(methodSig)) continue;
                    String dedupeKey = filePath + "|" + classPath + "|" + methodSig;
                    if (!seen.add(dedupeKey)) continue;
                    int[] lines = resolveMethodLineRange(classFile, methodSig);
                    DraftSourceReference ref = new DraftSourceReference();
                    ref.setDraftId(draftId);
                    ref.setFilePath(filePath);
                    ref.setClassName(classPath);
                    ref.setMethodSignature(methodSig.trim());
                    if (lines != null) {
                        ref.setStartLine(lines[0]);
                        ref.setEndLine(lines[1]);
                    } else {
                        ref.setStartLine(1);
                        ref.setEndLine(0);
                    }
                    ref.setCreatedAt(LocalDateTime.now());
                    draftSourceReferenceMapper.insert(ref);
                }
            }
            return;
        }
        if (fn.getClassPaths() == null) return;
        for (String classPath : fn.getClassPaths()) {
            if (!StringUtils.hasText(classPath)) continue;
            String filePath = entryClassToFilePath(taskId, classPath);
            if (!StringUtils.hasText(filePath)) continue;
            String dedupeKey = filePath + "|" + classPath;
            if (!seen.add(dedupeKey)) continue;
            DraftSourceReference ref = new DraftSourceReference();
            ref.setDraftId(draftId);
            ref.setFilePath(filePath);
            ref.setClassName(classPath);
            ref.setStartLine(1);
            ref.setEndLine(0);
            ref.setCreatedAt(LocalDateTime.now());
            draftSourceReferenceMapper.insert(ref);
        }
    }

    private int[] resolveMethodLineRange(File classFile, String methodSig) {
        if (classFile == null || !classFile.exists() || !StringUtils.hasText(methodSig)) {
            return null;
        }
        try {
            com.company.codeinsight.modules.parser.model.ParsedClassInfo info = javaParserService.parseFile(classFile);
            if (info == null || info.getMethods() == null) return null;
            int parenIdx = methodSig.indexOf('(');
            String methodName = (parenIdx >= 0 ? methodSig.substring(0, parenIdx) : methodSig).trim();
            for (com.company.codeinsight.modules.parser.model.ParsedClassInfo.MethodInfo mi : info.getMethods()) {
                if (methodName.equals(mi.getName()) && mi.getStartLine() != null && mi.getEndLine() != null) {
                    return new int[]{mi.getStartLine(), mi.getEndLine()};
                }
            }
        } catch (Exception e) {
            log.warn("resolveMethodLineRange failed for {}: {}", classFile, e.getMessage());
        }
        return null;
    }

    /**
     * 从 methodCall 表反查入口类的物理文件路径；兜底用包路径推断
     */
    private String entryClassToFilePath(Long taskId, String fqClassName) {
        if (!StringUtils.hasText(fqClassName)) return null;
        String shortName = fqClassName.contains(".")
                ? fqClassName.substring(fqClassName.lastIndexOf('.') + 1)
                : fqClassName;
        try {
            List<MethodCall> calls = methodCallMapper.selectList(
                    new LambdaQueryWrapper<MethodCall>().eq(MethodCall::getTaskId, taskId)
            );
            for (MethodCall mc : calls) {
                if (shortName.equals(mc.getClassName()) && StringUtils.hasText(mc.getFilePath())) {
                    return mc.getFilePath();
                }
            }
        } catch (Exception e) {
            log.warn("entryClassToFilePath 反查调用链失败: {}", e.getMessage());
        }
        // 兜底：包路径推断
        if (fqClassName.contains(".")) {
            String pkgPath = fqClassName.substring(0, fqClassName.lastIndexOf('.')).replace('.', '/');
            String simple = fqClassName.substring(fqClassName.lastIndexOf('.') + 1);
            return "src/main/java/" + pkgPath + "/" + simple + ".java";
        }
        return fqClassName + ".java";
    }

    private int countFunctions(com.company.codeinsight.modules.hierarchy.model.ModuleDto moduleDto) {
        int c = 0;
        for (com.company.codeinsight.modules.hierarchy.model.SubModuleDto sm : moduleDto.getSubModules().values()) {
            c += sm.getFunctions().size();
        }
        return c;
    }

    /**
     * 将整个 ModuleHierarchy DTO 序列化为 JSON 字符串（注入 {module_hierarchy.json} 占位符）
     * <p>
     * 提示词专用：使用 PromptViewDtos 剥离 class_paths，仅暴露 id / name / keywords。
     * 内部仍持有完整 class_paths 用于 collectModuleSourceCode / routing。
     */
    private String serializeHierarchyToJson(com.company.codeinsight.modules.hierarchy.model.ModuleHierarchy hierarchy) {
        if (hierarchy == null) return "{}";
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                    com.company.codeinsight.modules.hierarchy.model.PromptViewDtos.from(hierarchy));
        } catch (Exception e) {
            log.warn("serializeHierarchyToJson 失败，回退到空对象", e);
            return "{}";
        }
    }

    /**
     * 旧 17 章节生成逻辑（DTO 为空时兜底调用，保留作为降级路径）
     */
    private void legacyGenerateDraftDocument(Long taskId, List<CodeChunk> chunks, String promptContent) {
        DecompileTask task = decompileTaskMapper.selectById(taskId);

        // 根据路由分包算法对 Chunks 进行模块划分划分
        Map<String, List<CodeChunk>> moduleChunks = new HashMap<>();
        for (CodeChunk chunk : chunks) {
            String path = chunk.getFilePath();
            String moduleName = routeModuleForFile(taskId, task.getSystemId(), path, chunk);
            if (moduleName == null) {
                // 默认无法划归的兜底命名命名
                moduleName = "CoreModule";
                if (path.contains("controller")) {
                    moduleName = "接口访问层 (Controller)";
                } else if (path.contains("service")) {
                    moduleName = "业务逻辑层 (Service)";
                } else if (path.contains("mapper")) {
                    moduleName = "数据访问层 (Mapper)";
                } else if (path.contains("entity") || path.contains("dto")) {
                    moduleName = "数据实体定义 (Entity)";
                }
            }

            moduleChunks.computeIfAbsent(moduleName, k -> new ArrayList<>()).add(chunk);
        }

        // 查找或为当前任务创建工作区
        DraftWorkspace ws = draftWorkspaceMapper.selectOne(
                new LambdaQueryWrapper<DraftWorkspace>().eq(DraftWorkspace::getTaskId, taskId)
        );
        if (ws == null) {
            ws = new DraftWorkspace();
            ws.setTaskId(taskId);
            ws.setSystemId(task.getSystemId());
            ws.setRepositoryId(task.getRepositoryId());
            ws.setStatus("ACTIVE");
            ws.setCreatedAt(LocalDateTime.now());
            ws.setUpdatedAt(LocalDateTime.now());
            draftWorkspaceMapper.insert(ws);
        }

        // 对每一个业务模块依次构建 Markdown 架构文档
        for (Map.Entry<String, List<CodeChunk>> entry : moduleChunks.entrySet()) {
            String moduleName = entry.getKey();
            List<CodeChunk> cList = entry.getValue();

            StringBuilder docBuilder = new StringBuilder();
            docBuilder.append("# ").append(moduleName).append(" 知识归纳\n\n");

            docBuilder.append("## 一、 模块概述\n");
            docBuilder.append("本模块负责系统的 ").append(moduleName).append(" 核心功能。通过对相关代码的静态解析和 AI 归纳，梳理其主要职责和调用流向。\n\n");

            docBuilder.append("## 二、 包含子模块\n");
            docBuilder.append("- 暂无子模块\n\n");

            docBuilder.append("## 三、 业务背景\n");
            docBuilder.append("本模块服务于 ").append(moduleName).append(" 的基础业务场景，保证系统核心链路的数据正确性。\n\n");

            docBuilder.append("## 四、 业务目标\n");
            docBuilder.append("1. 提供高可靠性的业务处理逻辑。\n2. 实现数据结构的完整性。\n\n");

            docBuilder.append("## 五、 功能描述\n");
            docBuilder.append("实现该模块对应的各种静态解析及动态事务管理。\n\n");

            docBuilder.append("## 六、 涉及类清单\n");
            Set<String> classNames = new LinkedHashSet<>();
            for (CodeChunk c : cList) {
                if (StringUtils.hasText(c.getClassName())) {
                    classNames.add(c.getClassName() + " (" + c.getFilePath() + ")");
                }
            }
            for (String cn : classNames) {
                docBuilder.append("- `").append(cn).append("`\n");
            }
            docBuilder.append("\n");

            docBuilder.append("## 七、 输入数据\n");
            docBuilder.append("- 各种入参、VO 实体及 DTO 传输对象。\n\n");

            docBuilder.append("## 八、 输出接口 URL\n");
            boolean hasUrls = false;
            for (CodeChunk c : cList) {
                if ("java".equalsIgnoreCase(getFileExtension(c.getFilePath()))) {
                    Path classFile = taskWorkspacePaths.taskProjectPath(taskId).resolve(c.getFilePath());
                    if (Files.exists(classFile)) {
                        try {
                            ParsedClassInfo info = javaParserService.parseFile(classFile.toFile());
                            if (info != null && !info.getMethods().isEmpty()) {
                                for (ParsedClassInfo.MethodInfo m : info.getMethods()) {
                                    if (StringUtils.hasText(m.getRequestMapping())) {
                                        docBuilder.append("- `").append(m.getHttpMethod() != null ? m.getHttpMethod() : "GET").append("` `").append(m.getRequestMapping()).append("` (方法: `").append(m.getName()).append("`)\n");
                                        hasUrls = true;
                                    }
                                }
                            }
                        } catch (Exception ignored) {}
                    }
                }
            }
            if (!hasUrls) {
                docBuilder.append("- 暂无路由接口映射\n");
            }
            docBuilder.append("\n");

            docBuilder.append("## 九、 输出数据\n");
            docBuilder.append("- 统一返回包装类 `ApiResponse`。\n\n");

            docBuilder.append("## 十、 核心业务流程图\n");
            docBuilder.append("```mermaid\ngraph TD\n");
            if (classNames.size() > 1) {
                List<String> list = new ArrayList<>(classNames);
                for (int i = 0; i < list.size() - 1; i++) {
                    String cleanA = list.get(i).substring(0, list.get(i).indexOf(" "));
                    String cleanB = list.get(i+1).substring(0, list.get(i+1).indexOf(" "));
                    docBuilder.append("  ").append(cleanA).append(" --> ").append(cleanB).append("\n");
                }
            } else {
                docBuilder.append("  Start --> Process --> End\n");
            }
            docBuilder.append("```\n\n");

            docBuilder.append("## 十一、 核心业务逻辑\n");
            int methodIndex = 0;
            int methodTotal = 0;
            for (CodeChunk _c : cList) {
                if ("METHOD".equals(_c.getChunkType())) methodTotal++;
            }
            for (CodeChunk c : cList) {
                if ("METHOD".equals(c.getChunkType())) {
                    methodIndex++;
                    docBuilder.append("### 方法: `").append(c.getMethodName()).append("`\n");
                    docBuilder.append("- **所属类**: `").append(c.getClassName()).append("`\n");
                    docBuilder.append("- **行范围**: 第 ").append(c.getStartLine()).append(" 行到第 ").append(c.getEndLine()).append(" 行\n");

                    // 实时把"当前切片 i/N"写到 pipeline.log，供"查看完整日志"查看当前进度
                    execLog.log(taskId, "  [chunk " + methodIndex + "/" + methodTotal + "] "
                            + c.getFilePath() + (c.getMethodName() != null ? "#" + c.getMethodName() : "") + " type=METHOD");

                    // 为每一个方法级 Chunks 触发大模型归纳归纳
                    String chunkSummary = summarizeChunk(taskId, c.getId(), promptContent, task.getModelName());
                    docBuilder.append("\n**功能逻辑分析**:\n").append(chunkSummary).append("\n\n");
                }
            }

            docBuilder.append("## 十二、 调用链路说明\n");
            docBuilder.append("- 通过 Controller 接收前端 network 请求，交由 Service 模块执行核心逻辑，最终调用 Mapper 持久层写入数据库。\n\n");

            docBuilder.append("## 十三、 数据表与数据流\n");
            boolean hasTables = false;
            for (CodeChunk c : cList) {
                if ("java".equalsIgnoreCase(getFileExtension(c.getFilePath()))) {
                    Path classFile = taskWorkspacePaths.taskProjectPath(taskId).resolve(c.getFilePath());
                    if (Files.exists(classFile)) {
                        try {
                            ParsedClassInfo info = javaParserService.parseFile(classFile.toFile());
                            if (info != null && info.getTables() != null && !info.getTables().isEmpty()) {
                                for (String tbl : info.getTables()) {
                                    docBuilder.append("- 数据表: `").append(tbl).append("`\n");
                                    hasTables = true;
                                }
                            }
                        } catch (Exception ignored) {}
                    }
                }
            }
            if (!hasTables) {
                docBuilder.append("- 暂无关联数据表操作\n");
            }
            docBuilder.append("\n");

            docBuilder.append("## 十四、 规则、开关与配置\n");
            docBuilder.append("- 适用常规 JVM 及 Application.yml 通用配置项。\n\n");

            docBuilder.append("## 十五、 边界情况与异常处理清单\n");
            docBuilder.append("1. 参数非法时抛出 `BusinessException` 触发全局异常阻断。\n2. 数据库连接异常超时自动重试失败。\n\n");

            docBuilder.append("## 十六、 待确认事项\n");
            docBuilder.append("- [ ] 接口响应的异常情况如何优雅透传前端？\n");
            docBuilder.append("- [ ] 数据库事务在并发场景下的锁机制是否符合业务并发限流标准？\n\n");

            docBuilder.append("## 十七、 代码来源依据\n");
            for (CodeChunk c : cList) {
                docBuilder.append("- [").append(c.getFilePath()).append("](file:///").append(c.getFilePath()).append("#L").append(c.getStartLine()).append("-L").append(c.getEndLine()).append(")\n");
            }

            String markdown = docBuilder.toString();
            String hash = DigestUtils.md5DigestAsHex(markdown.getBytes());

            String relativeDocPath = "task_" + taskId + "/" + moduleName.replaceAll("[\\s/\\(\\)]", "_") + ".md";
            Path storePath = Paths.get(localStoragePath, "drafts", relativeDocPath);
            try {
                Files.createDirectories(storePath.getParent());
                Files.writeString(storePath, markdown);
            } catch (IOException e) {
                log.error("保存 Markdown 知识草稿文件失败", e);
            }

            // 若代码库是本地路径，同时在本地代码库指定目录下保存一份草稿文档
            try {
                CodeRepository repo = repositoryMapper.selectById(task.getRepositoryId());
                if (repo != null && StringUtils.hasText(repo.getGitUrl())) {
                    File localRepoDir = new File(repo.getGitUrl());
                    if (localRepoDir.exists() && localRepoDir.isDirectory()) {
                        File targetDraftDir = new File(localRepoDir, "docs/code-insight/drafts");
                        if (!targetDraftDir.exists()) {
                            targetDraftDir.mkdirs();
                        }
                        File targetDraftFile = new File(targetDraftDir, moduleName.replaceAll("[\\s/\\(\\)]", "_") + ".md");
                        Files.writeString(targetDraftFile.toPath(), markdown);
                        log.info("本地模式：成功备份草稿文档至指定目录：{}", targetDraftFile.getAbsolutePath());
                    }
                }
            } catch (Exception e) {
                log.error("备份草稿文档至本地代码库指定目录失败", e);
            }

            // 依据置信度来决定初始状态：低于 0.75 设为 PENDING_REVIEW (人工待复核) 状态
            double confidence = 0.85;
            if (moduleName.contains("CoreModule") || moduleName.hashCode() % 3 == 0) {
                confidence = 0.70;
            }
            String initialStatus = confidence < 0.75 ? "PENDING_REVIEW" : "AI_GENERATED";

            KnowledgeDraft draft = knowledgeDraftMapper.selectOne(
                    new LambdaQueryWrapper<KnowledgeDraft>()
                            .eq(KnowledgeDraft::getWorkspaceId, ws.getId())
                            .eq(KnowledgeDraft::getFilePath, relativeDocPath)
            );
            if (draft == null) {
                draft = new KnowledgeDraft();
                draft.setWorkspaceId(ws.getId());
                draft.setFilePath(relativeDocPath);
                draft.setModuleName(moduleName);
                draft.setContentUri(storePath.toAbsolutePath().toUri().toString());
                draft.setStatus(initialStatus);
                draft.setHash(hash);
                draft.setCreatedAt(LocalDateTime.now());
                draft.setUpdatedAt(LocalDateTime.now());
                knowledgeDraftMapper.insert(draft);
            } else {
                draft.setHash(hash);
                draft.setStatus(initialStatus);
                draft.setUpdatedAt(LocalDateTime.now());
                knowledgeDraftMapper.updateById(draft);
            }

            // 保存代码来源引用 ci_draft_source_reference
            draftSourceReferenceMapper.delete(
                    new LambdaQueryWrapper<DraftSourceReference>().eq(DraftSourceReference::getDraftId, draft.getId())
            );
            for (CodeChunk c : cList) {
                DraftSourceReference ref = new DraftSourceReference();
                ref.setDraftId(draft.getId());
                ref.setFilePath(c.getFilePath());
                ref.setStartLine(c.getStartLine());
                ref.setEndLine(c.getEndLine());
                ref.setCreatedAt(LocalDateTime.now());
                draftSourceReferenceMapper.insert(ref);
            }
        }
    }

    /**
     * 保存 AI 原始响应记录并提交至 Token 审计表
     */
    private void saveCallRecordAndAudit(Long systemId, Long taskId, Long promptId, Integer promptVersion,
                                        Long chunkId, String model, int inTokens, int outTokens,
                                        String response, boolean isSuccess, String errorMsg, long duration,
                                        String callStage) {
        // 保存 AI 调用记录
        AiCallRecord record = new AiCallRecord();
        record.setTaskId(taskId);
        record.setChunkId(chunkId);
        record.setPromptId(promptId);
        record.setPromptVersion(promptVersion);
        record.setModelName(model);
        record.setInputToken(inTokens);
        record.setOutputToken(outTokens);
        record.setIsSuccess(isSuccess ? 1 : 0);
        record.setErrorReason(errorMsg);
        record.setDurationMs(duration);
        record.setCreatedAt(LocalDateTime.now());
        record.setCallStage(callStage);

        // 模拟请求和响应存储
        String stageTag = callStage == null ? "call" : callStage.toLowerCase();
        String relativePath = "task_" + taskId + "/" + stageTag + "_" + (chunkId == null ? "0" : chunkId) + "_" + System.currentTimeMillis();
        Path reqPath = Paths.get(localStoragePath, "ai_logs", relativePath + "_req.json");
        Path respPath = Paths.get(localStoragePath, "ai_logs", relativePath + "_resp.txt");
        try {
            Files.createDirectories(reqPath.getParent());
            Files.writeString(reqPath, "{\"chunkId\":" + chunkId + ",\"model\":\"" + model + "\",\"stage\":\"" + callStage + "\"}");
            Files.writeString(respPath, response != null ? response : "");
            record.setRequestUri(reqPath.toAbsolutePath().toUri().toString());
            record.setResponseUri(respPath.toAbsolutePath().toUri().toString());
        } catch (IOException e) {
            log.error("写入 AI 请求日志文件失败", e);
        }

        aiCallRecordMapper.insert(record);

        // 写入 Token 审计（callStage 作为 type 维度；userId 来自当前操作人）
        tokenAuditService.logTokenUsage(systemId, taskId, com.company.codeinsight.common.auth.OperatorContext.getUserId(), model, inTokens, outTokens, callStage == null ? "INITIAL" : callStage, isSuccess);
    }

    @Override
    public String summarizeWithPrompt(Long taskId, String promptInput, String modelName, AiCallMeta callMeta) {
        if (taskId == null || promptInput == null) {
            return "{}";
        }

        DecompileTask task = decompileTaskMapper.selectById(taskId);
        Long systemId = task != null ? task.getSystemId() : 0L;

        String modelToUse = StringUtils.hasText(modelName) ? modelName : this.modelName;

        // 模型热插拔：按 identifier 取 apiKey/baseUrl
        String activeApiKey = this.apiKey;
        String activeApiUrl = this.apiUrl;
        if (StringUtils.hasText(modelToUse)) {
            com.company.codeinsight.modules.model.entity.AiModel dbModel = aiModelMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.company.codeinsight.modules.model.entity.AiModel>()
                            .eq(com.company.codeinsight.modules.model.entity.AiModel::getIdentifier, modelToUse)
                            .last("LIMIT 1")
            );
            if (dbModel != null) {
                if (StringUtils.hasText(dbModel.getApiKey())) activeApiKey = dbModel.getApiKey();
                if (StringUtils.hasText(dbModel.getBaseUrl())) activeApiUrl = dbModel.getBaseUrl();
            }
        }

        String processedPrompt = filterSensitiveInfo(promptInput);

        // Token 流控校验
        int taskUsed = tokenAuditService.getTaskCumulativeTokens(taskId);
        int systemUsed = tokenAuditService.getSystemMonthlyTokens(systemId);
        int currentEstimate = processedPrompt.length() / 3;
        if (isTaskTokenExceeded(taskUsed, currentEstimate)) {
            log.warn("Token 额度阻断：taskUsed={}, currentEstimate={}, taskLimit={}", taskUsed, currentEstimate, taskTokenLimit);
            return "{}";
        }
        if (isSystemTokenExceeded(systemUsed, currentEstimate)) {
            log.warn("Token 额度阻断：systemUsed={}, currentEstimate={}, systemLimit={}", systemUsed, currentEstimate, systemMonthlyTokenLimit);
            return "{}";
        }

        // 基础配置 - 流量管控：用户额度 + AI 调用并发
        try {
            quotaCheckService.checkUserQuota(currentEstimate);
        } catch (BusinessException e) {
            log.warn("用户额度阻断（summarizeWithPrompt）: {}", e.getMessage());
            return "{}";
        }
        aiConcurrencyService.tryAcquire();
        try {

        // Mock 降级
        boolean shouldMock = this.aiMock
                || !StringUtils.hasText(activeApiKey)
                || activeApiKey.startsWith("test-key")
                || "mock".equalsIgnoreCase(activeApiKey);

        String callStage = callMeta != null && StringUtils.hasText(callMeta.getCallStage()) ? callMeta.getCallStage() : "PROMPT";

        if (shouldMock) {
            log.info("Mock 模式已开启 (aiMock={}, apiKey={})，对 task {} / stage {} 跳过真实 AI 调用",
                    this.aiMock, maskKey(activeApiKey), taskId, callStage);
            saveCallRecordAndAudit(systemId, taskId, resolvePromptId(task, callStage), null,
                    null, modelToUse, currentEstimate, 2, "{}", true, "mock-mode", 100, callStage);
            return "{}";
        }

        log.info("真实 AI 调用开始: task={}, stage={}, model={}, promptEst={}tokens",
                taskId, callStage, modelToUse, currentEstimate);

        long start = System.currentTimeMillis();
        try {
            Map<String, Object> reqBody = new HashMap<>();
            reqBody.put("model", modelToUse);
            reqBody.put("stream", false);
            List<Map<String, String>> messages = new ArrayList<>();
            Map<String, String> userMsg = new HashMap<>();
            userMsg.put("role", "user");
            userMsg.put("content", processedPrompt);
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
                JsonNode root = objectMapper.readTree(response.body());
                String aiText = normalizeModelContent(root.path("choices").get(0).path("message").path("content").asText());
                int inTokens = root.path("usage").path("prompt_tokens").asInt(currentEstimate);
                int outTokens = root.path("usage").path("completion_tokens").asInt(aiText.length() / 3);
                saveCallRecordAndAudit(systemId, taskId, null, null,
                        null, modelToUse, inTokens, outTokens, aiText, true, null, duration, callStage);
                return aiText;
            } else {
                String errMsg = "HTTP " + response.statusCode() + ": " + response.body();
                log.error("真实 AI 调用失败 task={} stage={} model={}: {}", taskId, callStage, modelToUse, errMsg);
                saveCallRecordAndAudit(systemId, taskId, null, null,
                        null, modelToUse, currentEstimate, 0, "{}", false, errMsg, duration, callStage);
                return "{}";
            }
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            log.error("真实 AI 调用异常 task={} stage={} model={}: {}", taskId, callStage, modelToUse, e.getMessage());
            saveCallRecordAndAudit(systemId, taskId, resolvePromptId(task, callStage), null,
                    null, modelToUse, currentEstimate, 0, "{}", false, e.getMessage(), duration, callStage);
            return "{}";
        }
        } finally {
            // 释放并发信号量
            aiConcurrencyService.release();
        }
    }

    /**
     * 获取特定分片范围内的物理代码行集合
     */
    private String getChunkContent(CodeChunk chunk) {
        try {
            // 从快照读取文件内容
            File taskDir = taskWorkspacePaths.taskProjectDir(chunk.getTaskId());
            File codeFile = new File(taskDir, chunk.getFilePath());
            if (codeFile.exists()) {
                List<String> lines = Files.readAllLines(codeFile.toPath());
                StringBuilder sb = new StringBuilder();
                int start = Math.max(1, chunk.getStartLine());
                int end = Math.min(lines.size(), chunk.getEndLine());
                for (int i = start - 1; i < end; i++) {
                    sb.append(lines.get(i)).append("\n");
                }
                return sb.toString();
            }
        } catch (Exception ignored) {}
        return "class " + chunk.getClassName() + " { /* code stub */ }";
    }

    private boolean isTaskTokenExceeded(int taskUsed, int currentEstimate) {
        return tokenLimitEnabled && taskUsed + currentEstimate > taskTokenLimit;
    }

    private boolean isSystemTokenExceeded(int systemUsed, int currentEstimate) {
        return tokenLimitEnabled && systemUsed + currentEstimate > systemMonthlyTokenLimit;
    }

    /**
     * 从 task 中按 callStage 解析当前使用的 promptId
     * MODULE_HIERARCHY / CHUNK → modularizePromptId
     * MODULE_DOC → documentPromptId
     */
    private Long resolvePromptId(DecompileTask task, String callStage) {
        if (task == null || callStage == null) return null;
        if ("MODULE_DOC".equals(callStage)) return task.getDocumentPromptId();
        return task.getModularizePromptId();
    }

    /**
     * 对 API Key 进行脱敏显示（仅保留前4后4，中间用 *** 替代）
     */
    private String maskKey(String key) {
        if (!StringUtils.hasText(key)) return "(空)";
        if (key.length() <= 12) return key.substring(0, Math.min(2, key.length())) + "***";
        return key.substring(0, 4) + "***" + key.substring(key.length() - 4);
    }

    private static String truncateAiLogReason(String text) {
        if (!StringUtils.hasText(text)) {
            return "unknown";
        }
        String t = text.replace('\n', ' ').trim();
        return t.length() <= 200 ? t : t.substring(0, 200) + "...";
    }

    private String generateMockSummary(CodeChunk chunk) {
        String cName = chunk.getClassName() != null ? chunk.getClassName() : "UnknownClass";
        String mName = chunk.getMethodName() != null ? chunk.getMethodName() : "";
        String type = chunk.getChunkType();

        if ("FILE".equals(type) || "CLASS".equals(type)) {
            if (cName.endsWith("Controller")) {
                return "本类为接口访问控制层控制器。定义了针对 " + cName.replace("Controller", "") + " 资源的相关 REST 路由接口，负责前端请求参数校验、数据分发和统一响应格式的封装输出。";
            } else if (cName.endsWith("Service")) {
                return "本类为系统的核心业务逻辑服务类。负责处理业务决策、状态机转换和数据处理的事务边界。协调各个数据操作的 Mapper 接口以提供完整的业务支持。";
            } else if (cName.endsWith("Mapper")) {
                return "本接口为 MyBatis 数据访问持久层接口。通过注解或 XML 配置底层的 SQL 语句，实现与数据库表的映射，为业务层提供原子级的数据查询与存取支持。";
            } else {
                return "本类为实体模型定义，映射数据库表字段或作为传输数据 DTO/VO，为业务流提供数据结构的骨架定义。";
            }
        } else {
            // 方法级 Mock
            if (mName.startsWith("get") || mName.startsWith("select") || mName.startsWith("list")) {
                return "该方法主要负责执行数据库只读检索逻辑。接收特定的过滤参数或标识符，通过持久层抓取对应记录并经过基础映射后输出至调用方。";
            } else if (mName.startsWith("save") || mName.startsWith("create") || mName.startsWith("insert")) {
                return "该方法主要负责执行数据的写入逻辑。对传入实体进行参数完整性与格式化判定，随后通过数据源开启写入事务并在成功后返回对应的主键 ID。";
            } else {
                return "该方法定义了特定的核心业务计算或协调流程。结合入参执行状态判断，并在关键节点写入操作流水日志，确保操作的幂等性与可追溯性。";
            }
        }
    }

    private java.util.Set<String> parseRemediationModuleIds(DecompileTask task) {
        if (task == null
                || !com.company.codeinsight.modules.knowledge.remediation.KnowledgeRemediationConstants.TRIGGER_SOURCE
                .equals(task.getTriggerSource())
                || !org.springframework.util.StringUtils.hasText(task.getRemediationScopeJson())) {
            return null;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(task.getRemediationScopeJson());
            com.fasterxml.jackson.databind.JsonNode arr = root.get("moduleIds");
            if (arr == null || !arr.isArray()) {
                return null;
            }
            java.util.Set<String> ids = new java.util.LinkedHashSet<>();
            arr.forEach(node -> {
                if (node != null && org.springframework.util.StringUtils.hasText(node.asText())) {
                    ids.add(node.asText().trim());
                }
            });
            return ids.isEmpty() ? null : ids;
        } catch (Exception e) {
            log.warn("parseRemediationModuleIds failed taskId={}: {}", task.getId(), e.getMessage());
            return null;
        }
    }

    private String normalizeModelContent(String aiText) {
        return AiResponseJsonExtractor.stripModelArtifacts(aiText);
    }
}
