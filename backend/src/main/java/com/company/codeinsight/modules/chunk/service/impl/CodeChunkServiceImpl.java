package com.company.codeinsight.modules.chunk.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.company.codeinsight.common.exception.BusinessException;
import com.company.codeinsight.modules.chunk.entity.CodeChunk;
import com.company.codeinsight.modules.chunk.mapper.CodeChunkMapper;
import com.company.codeinsight.modules.chunk.service.CodeChunkService;
import com.company.codeinsight.modules.parser.model.ParsedClassInfo;
import com.company.codeinsight.modules.parser.service.JavaParserService;
import com.company.codeinsight.modules.scanner.entity.CodeFileSnapshot;
import com.company.codeinsight.modules.scanner.model.IncrementalContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;
import org.springframework.util.StringUtils;

import java.io.File;
import java.net.URI;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 代码切片管理服务实现类
 * 负责将扫描到的文件，按“文件级别”、“类级别”和“方法级别”切分成更细粒度的逻辑代码片段（Chunks），
 * 评估每个片段的 Token 占用，并在片段超限时自动拆分子分片，以适配大模型上下文窗口的大小限制。
 */
@Slf4j
@Service
public class CodeChunkServiceImpl implements CodeChunkService {

    // 单个切片的最大物理行数限制，防止单个请求文本过长
    private static final int MAX_CHUNK_LINES = 80;
    
    // 单个切片的最大预估 Token 限制（以防注释较多，字符量大）
    private static final int MAX_CHUNK_TOKENS = 1200;
    
    // 每一个切片的最低 Token 估算占位
    private static final int MIN_TOKEN_ESTIMATE = 5;

    // 不参与文本切片分析的已知二进制扩展名
    private static final Set<String> NON_TEXT_EXTENSIONS = Set.of(
            "jar", "class", "zip", "gz", "tar", "war", "ear",
            "png", "jpg", "jpeg", "gif", "webp", "ico", "bmp",
            "pdf", "exe", "dll", "so", "dylib", "bin", "o"
    );

    @Autowired
    private CodeChunkMapper chunkMapper;

    @Autowired
    private JavaParserService javaParserService;

    /**
     * 对拉取的代码进行切片并估算 Token
     * 1. 物理清空当前任务关联的所有旧切片
     * 2. 遍历每个快照文件，生成 "FILE" 文件级别切片
     * 3. 针对 Java 文件，提取类元数据生成 "CLASS" 切片
     * 4. 解析 Java 文件中所有的方法，准确定位行区间生成 "METHOD" 切片
     *
     * @param taskId    任务 ID
     * @param snapshots 本次任务拉取并扫描生成的文件快照集
     */
    @Override
    public void chunkAndEstimate(Long taskId, List<CodeFileSnapshot> snapshots) {
        chunkAndEstimate(taskId, snapshots, IncrementalContext.fullScan());
    }

    @Override
    public void chunkAndEstimate(Long taskId, List<CodeFileSnapshot> snapshots, IncrementalContext ctx) {
        if (snapshots == null || snapshots.isEmpty()) {
            return;
        }
        IncrementalContext effective = ctx == null ? IncrementalContext.fullScan() : ctx;

        if (!effective.isIncremental()) {
            // 全量：清空当前任务所有旧 chunk，再走全部文件
            chunkMapper.delete(new LambdaQueryWrapper<CodeChunk>().eq(CodeChunk::getTaskId, taskId));
            for (CodeFileSnapshot snapshot : snapshots) {
                chunkOneSnapshot(taskId, snapshot);
            }
            return;
        }

        // 增量：仅删掉「变更文件 + 删除文件」的历史 chunk；其余原样保留
        Set<String> toEvict = new HashSet<>();
        toEvict.addAll(effective.getChangedPaths());
        toEvict.addAll(effective.getDeletedPaths());
        if (!toEvict.isEmpty()) {
            chunkMapper.delete(
                    new LambdaQueryWrapper<CodeChunk>()
                            .eq(CodeChunk::getTaskId, taskId)
                            .in(CodeChunk::getFilePath, toEvict)
            );
        }
        if (effective.getChangedPaths().isEmpty()) {
            log.info("增量切片 — 本次无变更文件, taskId={}", taskId);
            return;
        }
        for (CodeFileSnapshot snapshot : snapshots) {
            if (effective.isPathChanged(snapshot.getFilePath())) {
                chunkOneSnapshot(taskId, snapshot);
            }
        }
        log.info("增量切片完成, taskId={}, 重切 {} 个文件, 已删 {} 个文件, ctx={}",
                taskId, effective.getChangedPaths().size(), effective.getDeletedPaths().size(), effective);
    }

    /**
     * 对单个快照执行 FILE/CLASS/METHOD 三级切片。
     * 公用全量与增量两个入口。
     */
    private void chunkOneSnapshot(Long taskId, CodeFileSnapshot snapshot) {
        try {
            String uri = snapshot.getContentUri();
            File file = (uri.startsWith("file:") || uri.startsWith("FILE:"))
                    ? new File(URI.create(uri))
                    : new File(uri);
            if (!file.exists()) {
                saveFailedChunk(taskId, snapshot.getFilePath(), "Snapshot file does not exist: " + snapshot.getContentUri());
                return;
            }

            if (shouldSkipNonTextFile(snapshot)) {
                log.debug("Skip non-text file for chunking: {}", snapshot.getFilePath());
                return;
            }

            List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
            String fullContent = String.join("\n", lines);

            // 保存文件级切片 (FILE)
            saveChunk(taskId, snapshot.getFilePath(), null, null, "FILE",
                    fullContent, 1, lines.size());

            // 如果是 Java 源文件，则做更精细的 AST 解析以提取类与方法
            if ("java".equalsIgnoreCase(snapshot.getFileType())) {
                ParsedClassInfo classInfo = javaParserService.parseFile(file);
                if (classInfo != null && classInfo.getClassName() != null) {
                    // 保存类级切片 (CLASS)
                    saveChunk(taskId, snapshot.getFilePath(), classInfo.getClassName(), null, "CLASS",
                            fullContent, 1, lines.size());

                    // 循环解析各个方法
                    for (ParsedClassInfo.MethodInfo method : classInfo.getMethods()) {
                        // 定位方法在 Java 文件中的起始行和结束行
                        int[] range = locateMethodRange(lines, method.getName());
                        int start = range[0];
                        int end = Math.min(range[1], lines.size());
                        String methodCode = joinLines(lines, start, end);

                        // 保存方法级切片 (METHOD)
                        saveChunk(taskId, snapshot.getFilePath(), classInfo.getClassName(), method.getName(), "METHOD",
                                methodCode, start, end);
                    }
                }
            }
        } catch (MalformedInputException e) {
            log.debug("Skip binary file for chunking: {}", snapshot.getFilePath());
        } catch (Exception e) {
            log.error("Failed to chunk file: {}", snapshot.getFilePath(), e);
            saveFailedChunk(taskId, snapshot.getFilePath(), e.getMessage());
        }
    }

    /**
     * 获取指定任务的切片集合
     */
    @Override
    public List<CodeChunk> getChunksByTaskId(Long taskId) {
        return chunkMapper.selectList(new LambdaQueryWrapper<CodeChunk>()
                .eq(CodeChunk::getTaskId, taskId)
                .orderByAsc(CodeChunk::getId));
    }

    /**
     * 将特定分片标记为失败状态
     */
    @Override
    public void markChunkFailed(Long chunkId, String reason) {
        CodeChunk chunk = chunkMapper.selectById(chunkId);
        if (chunk == null) {
            throw new BusinessException("Code chunk does not exist");
        }
        chunk.setStatus("FAILED");
        chunk.setErrorReason(StringUtils.hasText(reason) ? reason : "Chunk analysis failed");
        chunkMapper.updateById(chunk);
    }

    /**
     * 重试特定分片（将其状态置回 PENDING，清除错误原因）
     */
    @Override
    public void retryChunk(Long chunkId) {
        CodeChunk chunk = chunkMapper.selectById(chunkId);
        if (chunk == null) {
            throw new BusinessException("Code chunk does not exist");
        }
        chunk.setStatus("PENDING");
        chunk.setErrorReason(null);
        chunkMapper.update(null, new LambdaUpdateWrapper<CodeChunk>()
                .eq(CodeChunk::getId, chunkId)
                .set(CodeChunk::getStatus, "PENDING")
                .set(CodeChunk::getErrorReason, null));
    }

    /**
     * 保存切片。若切片过大（超过行数或 Token 上限），则调用 saveSplitChunks 将其自动拆分存放。
     */
    private void saveChunk(Long taskId, String filePath, String className, String methodName, String type,
                           String content, int startLine, int endLine) {
        String normalizedContent = StringUtils.hasText(content) ? content : "";
        int tokenEstimate = estimateTokens(normalizedContent);
        int lineCount = Math.max(1, endLine - startLine + 1);

        // 如果单段代码超出 80 行，或者预估 Token 超过 1200，触发自动分页逻辑
        if (lineCount > MAX_CHUNK_LINES || tokenEstimate > MAX_CHUNK_TOKENS) {
            saveSplitChunks(taskId, filePath, className, methodName, type, normalizedContent, startLine);
            return;
        }

        insertChunk(taskId, filePath, className, methodName, type, normalizedContent, startLine, endLine, "PENDING", null);
    }

    /**
     * 自动拆分切片方法
     * 逐行读取大代码段，通过动态滚动容器保存子片段，一旦加入新的一行导致子片段越界，立即切出子片段，并标注后缀如 #part1, #part2 录入数据库。
     */
    private void saveSplitChunks(Long taskId, String filePath, String className, String methodName, String type,
                                 String content, int startLine) {
        List<String> lines = List.of(content.split("\\R", -1));
        List<String> partLines = new ArrayList<>();
        int partStartLine = startLine;
        int partIndex = 1;

        for (int i = 0; i < lines.size(); i++) {
            String nextLine = lines.get(i);
            // 拼装临时候选段
            String candidate = partLines.isEmpty()
                    ? nextLine
                    : String.join("\n", partLines) + "\n" + nextLine;
            // 判断如果加入当前行是否会触发超限越界
            boolean candidateTooLarge = !partLines.isEmpty()
                    && (partLines.size() + 1 > MAX_CHUNK_LINES || estimateTokens(candidate) > MAX_CHUNK_TOKENS);

            if (candidateTooLarge) {
                // 如果越界，先将当前已累积的 partLines 写入数据库作为一个 Part 分片
                String partContent = String.join("\n", partLines);
                int partEndLine = partStartLine + partLines.size() - 1;
                String partMethodName = methodName == null ? null : methodName + "#part" + partIndex;
                insertChunk(taskId, filePath, className, partMethodName, type + "_PART", partContent,
                        partStartLine, partEndLine, "PENDING", null);
                
                // 重置容器，开启下一个 Part 的扫描
                partLines.clear();
                partStartLine = partEndLine + 1;
                partIndex++;
            }

            partLines.add(nextLine);

            // 如果是最后一行，强制将当前积累的剩余代码行作为一个 Part 录入
            boolean lastLine = i == lines.size() - 1;
            if (lastLine) {
                String partContent = String.join("\n", partLines);
                int partEndLine = partStartLine + partLines.size() - 1;
                String partMethodName = methodName == null ? null : methodName + "#part" + partIndex;
                insertChunk(taskId, filePath, className, partMethodName, type + "_PART", partContent,
                        partStartLine, partEndLine, "PENDING", null);
            }
        }
    }

    /**
     * 写入一个已彻底解析失败的文件分片标志
     */
    private void saveFailedChunk(Long taskId, String filePath, String reason) {
        insertChunk(taskId, filePath, null, null, "FILE", "FAILED:" + reason,
                1, 1, "FAILED", StringUtils.hasText(reason) ? reason : "Unknown chunk failure");
    }

    /**
     * 执行底层数据库 Insert 写入，并自动统计 Content MD5 签名和预估 Token
     */
    private void insertChunk(Long taskId, String filePath, String className, String methodName, String type,
                             String content, int startLine, int endLine, String status, String errorReason) {
        String normalizedContent = StringUtils.hasText(content) ? content : "";
        CodeChunk chunk = new CodeChunk();
        chunk.setTaskId(taskId);
        chunk.setFilePath(filePath);
        chunk.setClassName(className);
        chunk.setMethodName(methodName);
        chunk.setChunkType(type);
        chunk.setContentHash(DigestUtils.md5DigestAsHex(normalizedContent.getBytes()));
        chunk.setStartLine(startLine);
        chunk.setEndLine(endLine);
        chunk.setTokenEstimate(estimateTokens(normalizedContent));
        chunk.setStatus(status);
        chunk.setErrorReason(errorReason);
        chunk.setCreatedAt(LocalDateTime.now());
        chunkMapper.insert(chunk);
    }

    /**
     * 辅助拼接指定行范围内的代码文本
     */
    private String joinLines(List<String> lines, int start, int end) {
        StringBuilder builder = new StringBuilder();
        for (int i = start - 1; i < end && i < lines.size(); i++) {
            builder.append(lines.get(i)).append("\n");
        }
        return builder.toString();
    }

    /**
     * 轻量级 Token 数量估算公式
     * 核心计算规则：按平均每 3 个英文字符（含标点与空格）折算为 1 个大模型 Token，设定最低占位。
     */
    private int estimateTokens(String content) {
        int tokenEstimate = (int) Math.ceil((StringUtils.hasText(content) ? content.length() : 0) / 3.0);
        return Math.max(MIN_TOKEN_ESTIMATE, tokenEstimate);
    }

    /**
     * AST 辅助：通过文本行扫描，基于大括号平衡定位 Java 方法的起止行范围
     *
     * @param lines      Java 源文件的所有行文本
     * @param methodName 方法名称
     * @return 返回包含起始行（1-indexed）和结束行（1-indexed）的 int 数组 [start, end]
     */
    private int[] locateMethodRange(List<String> lines, String methodName) {
        int start = 1;
        int end = 1;

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            // 匹配包含方法名与括号的行 (排除了控制流等干扰项后)
            if (line.contains(methodName) && line.contains("(")) {
                // 如果是接口中的抽象方法声明（直接分号结尾），直接定位为当前行
                if (line.endsWith(";")) {
                    return new int[]{i + 1, i + 1};
                }
                start = i + 1;

                // 基于大括号深度匹配查找方法结尾
                int braceCount = 0;
                boolean foundFirstBrace = false;
                for (int j = i; j < lines.size(); j++) {
                    String scanLine = lines.get(j);
                    for (char ch : scanLine.toCharArray()) {
                        if (ch == '{') {
                            braceCount++;
                            foundFirstBrace = true;
                        } else if (ch == '}') {
                            braceCount--;
                        }
                    }
                    // 当括号完全闭合（braceCount 重归 0），定位到方法的右大括号所在行
                    if (foundFirstBrace && braceCount <= 0) {
                        end = j + 1;
                        break;
                    }
                }
                if (end < start) {
                    end = Math.min(lines.size(), start + 3);
                }
                break;
            }
        }
        return new int[]{start, end};
    }

    private boolean shouldSkipNonTextFile(CodeFileSnapshot snapshot) {
        String ext = snapshot.getFileType();
        return StringUtils.hasText(ext) && NON_TEXT_EXTENSIONS.contains(ext.toLowerCase());
    }
}

