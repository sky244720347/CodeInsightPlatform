package com.company.codeinsight.modules.scanner.service;

import com.company.codeinsight.modules.scanner.entity.CodeFileSnapshot;
import com.company.codeinsight.modules.scanner.model.ScanResult;
import java.util.List;

/**
 * 代码拉取与静态文件扫描服务接口
 * 负责定义克隆 Git 仓库、递归遍历工程目录、执行黑白名单过滤以提取核心源文件快照的任务逻辑。
 */
public interface CodeScannerService {

    /**
     * 拉取代码库、扫描目录、过滤无效文件（如图片、第三方包），计算 MD5 指纹并生成快照存入 ci_file_snapshot 数据库表中
     *
     * @param taskId       关联的任务 ID
     * @param repositoryId 关联的代码库配置 ID
     * @param taskType     任务类型：INITIAL-全量（默认） / INCREMENTAL-基于 git diff 的增量。
     *                     增量任务需要仓库已存在已发布基线 {@code lastCommitId}，否则降级为全量扫描。
     * @return {@link ScanResult} 包含本地项目目录与本次扫描的增量上下文（变更/删除文件清单），
     *         下游 AST/Chunk/Hierarchy/AI 阶段通过 {@code ScanResult.getIncrementalContext()} 判断走全量还是增量。
     */
    ScanResult pullAndScan(Long taskId, Long repositoryId, String taskType);

    /**
     * 获取指定分析任务下生成的全部代码文件快照元数据列表
     *
     * @param taskId 任务 ID
     * @return 文件快照列表
     */
    List<CodeFileSnapshot> getSnapshotsByTaskId(Long taskId);
}

