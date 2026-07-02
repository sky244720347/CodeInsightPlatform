package com.company.codeinsight.modules.knowledge.remediation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.company.codeinsight.common.exception.BusinessException;
import com.company.codeinsight.common.storage.TaskWorkspacePaths;
import com.company.codeinsight.modules.callchain.entity.MethodCall;
import com.company.codeinsight.modules.callchain.mapper.MethodCallMapper;
import com.company.codeinsight.modules.chunk.entity.CodeChunk;
import com.company.codeinsight.modules.chunk.mapper.CodeChunkMapper;
import com.company.codeinsight.modules.entrypoint.entity.EntrypointEntity;
import com.company.codeinsight.modules.entrypoint.mapper.EntrypointMapper;
import com.company.codeinsight.modules.repository.publish.entity.RepositoryEntrypointEntity;
import com.company.codeinsight.modules.repository.publish.service.RepositoryArtifactService;
import com.company.codeinsight.modules.scanner.entity.CodeFileSnapshot;
import com.company.codeinsight.modules.scanner.mapper.CodeFileSnapshotMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class TaskArtifactCloneService {

    private final TaskWorkspacePaths taskWorkspacePaths;
    private final CodeFileSnapshotMapper snapshotMapper;
    private final MethodCallMapper methodCallMapper;
    private final CodeChunkMapper chunkMapper;
    private final EntrypointMapper entrypointMapper;
    private final RepositoryArtifactService artifactService;

    public void cloneWorkspace(Long baseTaskId, Long newTaskId) {
        Path source = taskWorkspacePaths.taskProjectPath(baseTaskId);
        Path target = taskWorkspacePaths.taskProjectPath(newTaskId);
        if (!Files.isDirectory(source)) {
            throw new BusinessException("来源任务工作区不存在，无法纠错克隆: " + source);
        }
        try {
            if (Files.exists(target)) {
                throw new BusinessException("目标任务工作区已存在，无法克隆: " + target);
            }
            Files.walk(source, FileVisitOption.FOLLOW_LINKS).forEach(src -> {
                try {
                    Path dest = target.resolve(source.relativize(src));
                    if (Files.isDirectory(src)) {
                        Files.createDirectories(dest);
                    } else {
                        Files.createDirectories(dest.getParent());
                        Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    throw new BusinessException("克隆工作区失败: " + e.getMessage());
                }
            });
        } catch (IOException e) {
            throw new BusinessException("克隆工作区失败: " + e.getMessage());
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void cloneTaskArtifacts(Long baseTaskId, Long newTaskId) {
        cloneWorkspace(baseTaskId, newTaskId);
        copySnapshots(baseTaskId, newTaskId);
        copyMethodCalls(baseTaskId, newTaskId);
        copyChunks(baseTaskId, newTaskId);
    }

    @Transactional(rollbackFor = Exception.class)
    public void seedEntrypointsFromRepository(Long repositoryId, Long taskId, Long systemId) {
        entrypointMapper.deleteByTaskId(taskId);
        List<RepositoryEntrypointEntity> rows = artifactService.listPublishedEntrypoints(repositoryId);
        LocalDateTime now = LocalDateTime.now();
        for (RepositoryEntrypointEntity src : rows) {
            EntrypointEntity dst = new EntrypointEntity();
            dst.setTaskId(taskId);
            dst.setSystemId(systemId);
            dst.setClassName(src.getClassName());
            dst.setFilePath(src.getFilePath());
            dst.setEntryType(src.getEntryType());
            dst.setAnnotation(src.getAnnotation());
            dst.setRemark(src.getRemark());
            dst.setMethodsJson(src.getMethodsJson());
            dst.setSortOrder(src.getSortOrder() != null ? src.getSortOrder() : 0);
            dst.setCreatedAt(now);
            dst.setUpdatedAt(now);
            entrypointMapper.insert(dst);
        }
    }

    private void copySnapshots(Long baseTaskId, Long newTaskId) {
        snapshotMapper.delete(new LambdaQueryWrapper<CodeFileSnapshot>().eq(CodeFileSnapshot::getTaskId, newTaskId));
        List<CodeFileSnapshot> rows = snapshotMapper.selectList(
                new LambdaQueryWrapper<CodeFileSnapshot>().eq(CodeFileSnapshot::getTaskId, baseTaskId));
        LocalDateTime now = LocalDateTime.now();
        for (CodeFileSnapshot src : rows) {
            CodeFileSnapshot dst = new CodeFileSnapshot();
            dst.setTaskId(newTaskId);
            dst.setFilePath(src.getFilePath());
            dst.setFileType(src.getFileType());
            dst.setLineCount(src.getLineCount());
            dst.setFileHash(src.getFileHash());
            dst.setContentUri(src.getContentUri());
            dst.setCreatedAt(now);
            snapshotMapper.insert(dst);
        }
    }

    private void copyMethodCalls(Long baseTaskId, Long newTaskId) {
        methodCallMapper.delete(new LambdaQueryWrapper<MethodCall>().eq(MethodCall::getTaskId, newTaskId));
        List<MethodCall> rows = methodCallMapper.selectList(
                new LambdaQueryWrapper<MethodCall>().eq(MethodCall::getTaskId, baseTaskId));
        LocalDateTime now = LocalDateTime.now();
        for (MethodCall src : rows) {
            MethodCall dst = new MethodCall();
            dst.setTaskId(newTaskId);
            dst.setFilePath(src.getFilePath());
            dst.setClassName(src.getClassName());
            dst.setCallerMethod(src.getCallerMethod());
            dst.setDependencyName(src.getDependencyName());
            dst.setTargetMethod(src.getTargetMethod());
            dst.setExpression(src.getExpression());
            dst.setLineNumber(src.getLineNumber());
            dst.setCallerSignature(src.getCallerSignature());
            dst.setTargetSignature(src.getTargetSignature());
            dst.setCreatedAt(now);
            methodCallMapper.insert(dst);
        }
    }

    private void copyChunks(Long baseTaskId, Long newTaskId) {
        chunkMapper.delete(new LambdaQueryWrapper<CodeChunk>().eq(CodeChunk::getTaskId, newTaskId));
        List<CodeChunk> rows = chunkMapper.selectList(
                new LambdaQueryWrapper<CodeChunk>().eq(CodeChunk::getTaskId, baseTaskId));
        LocalDateTime now = LocalDateTime.now();
        for (CodeChunk src : rows) {
            CodeChunk dst = new CodeChunk();
            dst.setTaskId(newTaskId);
            dst.setFilePath(src.getFilePath());
            dst.setClassName(src.getClassName());
            dst.setMethodName(src.getMethodName());
            dst.setChunkType(src.getChunkType());
            dst.setContentHash(src.getContentHash());
            dst.setStartLine(src.getStartLine());
            dst.setEndLine(src.getEndLine());
            dst.setTokenEstimate(src.getTokenEstimate());
            dst.setStatus(src.getStatus());
            dst.setErrorReason(src.getErrorReason());
            dst.setCreatedAt(now);
            chunkMapper.insert(dst);
        }
    }
}
