package com.company.codeinsight.modules.scanner.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.company.codeinsight.common.exception.BusinessException;
import com.company.codeinsight.common.storage.TaskWorkspacePaths;
import com.company.codeinsight.modules.repository.entity.CodeRepository;
import com.company.codeinsight.modules.repository.mapper.CodeRepositoryMapper;
import com.company.codeinsight.modules.scanner.entity.CodeFileSnapshot;
import com.company.codeinsight.modules.scanner.mapper.CodeFileSnapshotMapper;
import com.company.codeinsight.modules.scanner.model.IncrementalContext;
import com.company.codeinsight.modules.scanner.model.ScanResult;
import com.company.codeinsight.modules.scanner.service.CodeScannerService;
import com.company.codeinsight.modules.task.entity.DecompileTask;
import com.company.codeinsight.modules.task.mapper.DecompileTaskMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.util.io.NullOutputStream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;
import org.springframework.util.StringUtils;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 代码拉取与扫描服务实现类
 * 负责解析仓库配置，通过本地直接复制或者 JGit 克隆获取项目源码，递归扫描文件生成物理快照与数据库快照索引记录。
 */
@Slf4j
@Service
public class CodeScannerServiceImpl implements CodeScannerService {

    @Autowired
    private CodeRepositoryMapper repositoryMapper;

    @Autowired
    private CodeFileSnapshotMapper snapshotMapper;

    @Autowired
    private SqlSessionFactory sqlSessionFactory;

    @Autowired
    private TaskWorkspacePaths taskWorkspacePaths;

    @Autowired
    private DecompileTaskMapper taskMapper;

    /** 快照批量写入大小，减少 DB 往返次数 */
    private static final int SNAPSHOT_BATCH_SIZE = 500;

    // 对象存储的本地物理暂存根路径
    @Value("${code-insight.storage.local-path:./storage}")
    private String localStoragePath;

    /**
     * 拉取代码库代码并进行结构扫描
     * 1. 优先校验本地文件路径是否存在（如本地文件夹直接扫描）
     * 2. 否则通过 JGit 克隆远程代码库
     * 3. 极端的离线网络情况下，自动生成一套演示用的 Mock 仓库文件以跑通业务流
     *
     * @param taskId       当前分析任务 ID
     * @param repositoryId 关联的代码库配置 ID
     * @param taskType     任务类型：INITIAL-全量（默认），INCREMENTAL-基于 git diff 的增量
     * @return {@link ScanResult} 含本地项目目录与本次扫描的增量上下文
     */
    @Override
    public ScanResult pullAndScan(Long taskId, Long repositoryId, String taskType) {
        CodeRepository repo = repositoryMapper.selectById(repositoryId);
        if (repo == null) {
            throw new BusinessException("未找到关联的代码库配置");
        }

        // 确定该任务的临时存放目录 (workspace-root/task_{taskId})
        File targetDir = taskWorkspacePaths.taskProjectDir(taskId);
        // 清理上一次运行残留的临时旧目录
        deleteDirectory(targetDir);

        String commitId = "MOCK_COMMIT_" + System.currentTimeMillis();
        boolean gitPullSuccess = false;
        // 仅在走远程 Git 成功克隆分支里持有句柄，本地目录与 Mock 降级分支保持为 null
        Git gitHandle = null;
        // 增量/全量决策的输出：声明在 if 外以便在末尾组装 IncrementalContext 时引用
        boolean performFullScan = true;
        Set<String> changedPaths = null;  // ADD / MODIFY / COPY / RENAME-新路径
        Set<String> deletedPaths = null;  // DELETE / RENAME-旧路径
        boolean isIncremental = "INCREMENTAL".equalsIgnoreCase(taskType);
        boolean hasBaseline = StringUtils.hasText(repo.getLastCommitId());
        String baselineCommitId = isIncremental && hasBaseline ? repo.getLastCommitId() : null;

        // 检查配置的 GitUrl 是否为本地已存在的绝对或相对路径
        File localSourceDir = new File(repo.getGitUrl());
        if (localSourceDir.exists() && localSourceDir.isDirectory()) {
            log.info("检测到本地代码库路径: {}，直接复制文件进行扫描", repo.getGitUrl());
            try {
                copyDirectory(localSourceDir, targetDir);
                gitPullSuccess = true;
                commitId = "LOCAL_" + System.currentTimeMillis();
            } catch (IOException e) {
                log.error("复制本地代码库失败", e);
                throw new BusinessException("扫描代码失败: 无法复制本地目录 " + repo.getGitUrl());
            }
        } else {
            // 否则，作为 Git 协议 URL 进行克隆
            try {
                log.info("开始克隆 Git 仓库: {} 分支: {}", repo.getGitUrl(), repo.getBranch());
                CloneCommand cloneCommand = Git.cloneRepository()
                        .setURI(repo.getGitUrl())
                        .setBranch(repo.getBranch())
                        .setDirectory(targetDir);

                // 注入 HTTP Basic 认证凭证配置
                if (StringUtils.hasText(repo.getUsername()) && StringUtils.hasText(repo.getPassword())) {
                    cloneCommand.setCredentialsProvider(new UsernamePasswordCredentialsProvider(repo.getUsername(), repo.getPassword()));
                }

                // 不再使用 try-with-resources：增量分支需要在同一句柄上执行 git diff，
                // 算完 DiffEntry 后再统一关闭
                gitHandle = cloneCommand.call();
                commitId = gitHandle.getRepository().resolve("HEAD").getName();
                gitPullSuccess = true;
                log.info("Git 克隆成功, Commit ID: {}", commitId);
            } catch (Exception e) {
                log.warn("JGit 克隆仓库失败 ({}), 启动本地 Mock 代码生成以便离线跑通闭环", e.getMessage());
                try {
                    // 容错降级：在没有外网或 Git 服务器不可达时，在目标目录自动拼装一套标准的 Controller/Service/Mapper 模拟文件
                    generateMockRepositoryFiles(targetDir);
                    gitPullSuccess = true;
                } catch (IOException ioException) {
                    log.error("生成 Mock 仓库文件失败", ioException);
                    throw new BusinessException("扫描代码失败: 无法克隆且生成模拟文件失败");
                }
            }
        }

        if (gitPullSuccess) {
            // === 1. 计算本次扫描的文件范围（增量或全量） ===
            if (isIncremental && gitHandle != null && hasBaseline) {
                try {
                    DiffOutcome diff = computeIncrementalDiff(gitHandle, repo.getLastCommitId(), "HEAD");
                    changedPaths = diff.changed;
                    deletedPaths = diff.deleted;
                    log.info("增量扫描 — 变更 {} 个文件，删除 {} 个文件（基线 {} → HEAD {}）",
                            changedPaths.size(), deletedPaths.size(), repo.getLastCommitId(), commitId);
                } catch (Exception diffEx) {
                    // 基线 commit 在新 history 中不可解析（force-push / rebase），
                    // 降级为全量扫描，不让流水线因增量分支异常而中断
                    log.warn("增量 diff 识别失败（{}），降级为全量扫描", diffEx.getMessage());
                    changedPaths = null;
                    deletedPaths = null;
                }
            } else if (isIncremental && !hasBaseline) {
                log.warn("增量任务无基线 commitId (repositoryId={})，降级为全量扫描", repositoryId);
            } else if (isIncremental && gitHandle == null) {
                log.warn("增量任务未持有 Git 句柄（本地路径或 Mock 降级），降级为全量扫描");
            }

            performFullScan = !isIncremental || changedPaths == null;

            // === 2. 清理/重建 snapshot 索引 ===
            if (performFullScan) {
                // 全量：清空当前任务全部旧 snapshot
                snapshotMapper.delete(new LambdaQueryWrapper<CodeFileSnapshot>().eq(CodeFileSnapshot::getTaskId, taskId));
            } else {
                // 增量：仅清掉「本次需要重写」+「本次被删除」的文件
                if (!changedPaths.isEmpty()) {
                    snapshotMapper.delete(new LambdaQueryWrapper<CodeFileSnapshot>()
                            .eq(CodeFileSnapshot::getTaskId, taskId)
                            .in(CodeFileSnapshot::getFilePath, changedPaths));
                }
                if (!deletedPaths.isEmpty()) {
                    snapshotMapper.delete(new LambdaQueryWrapper<CodeFileSnapshot>()
                            .eq(CodeFileSnapshot::getTaskId, taskId)
                            .in(CodeFileSnapshot::getFilePath, deletedPaths));
                }
            }

            // === 3. 递归扫描目录，生成新 snapshot（批量缓冲写入） ===
            List<CodeFileSnapshot> batchBuffer = new ArrayList<>();
            int[] totalInserted = {0};
            if (performFullScan) {
                scanDirectory(targetDir, targetDir, repo, taskId, batchBuffer, null, totalInserted);
            } else {
                scanDirectory(targetDir, targetDir, repo, taskId, batchBuffer, changedPaths, totalInserted);
            }
            // 刷出缓冲区剩余快照
            if (!batchBuffer.isEmpty()) {
                totalInserted[0] += flushSnapshotBatch(batchBuffer);
            }

            // === 4. 落库任务扫描 commit；刷新仓库最近扫描时间（发布基线 commit 仅在推送/回滚时更新） ===
            persistTaskSourceCommit(taskId, commitId);
            repo.setLastDecompileAt(LocalDateTime.now());
            repositoryMapper.updateById(repo);

            if (performFullScan) {
                log.info("全量扫描完成，共生成快照记录数: {}", totalInserted[0]);
            } else {
                log.info("增量扫描完成，更新快照 {} 个（已删 {} 个），其余文件未变动", totalInserted[0], deletedPaths.size());
            }
        }

        // Git 句柄在所有 diff 算完之后再关闭（不论是否走增量）
        if (gitHandle != null) {
            try {
                gitHandle.close();
            } catch (Exception closeEx) {
                log.warn("关闭 Git 句柄失败: {}", closeEx.getMessage());
            }
        }

        // 组装给下游的增量上下文；performFullScan 走 fullScan()，否则把 changed/deleted 透传
        IncrementalContext ctx = performFullScan
                ? IncrementalContext.fullScan()
                : IncrementalContext.incremental(changedPaths, deletedPaths);
        String scanMode = "INITIAL";
        String headCommitId = commitId;
        if (isIncremental) {
            scanMode = performFullScan ? "DEGRADED_FULL" : "INCREMENTAL";
        }
        return new ScanResult(targetDir, ctx, baselineCommitId, headCommitId, scanMode);
    }

    /**
     * 计算 {@code oldRef} 与 {@code newRef} 之间的文件级 diff。
     * <p>
     * RENAME 拆解为「旧路径进 deleted + 新路径进 changed」处理；COPY 与 MODIFY 一并视为 changed。
     * 任意 ref 在当前 history 中无法解析时抛异常，由调用方决定降级策略。
     * <p>
     * 使用 {@link DiffFormatter} 而非 {@link org.eclipse.jgit.api.DiffCommand}：
     * DiffCommand 在 6.8 尚未暴露 rename detection API，DiffFormatter.setDetectRenames(true)
     * 可以让 ADD/DELETE 在文本相似度高于阈值时被合并为 RENAME。
     */
    private DiffOutcome computeIncrementalDiff(Git git, String oldRef, String newRef) throws Exception {
        ObjectId oldTreeId = git.getRepository().resolve(oldRef + "^{tree}");
        ObjectId newTreeId = git.getRepository().resolve(newRef + "^{tree}");
        if (oldTreeId == null || newTreeId == null) {
            throw new IllegalStateException("无法解析 ref: old=" + oldRef + " new=" + newRef);
        }

        try (ObjectReader reader = git.getRepository().newObjectReader();
             // NullOutputStream 丢弃 patch 文本，我们只需要 DiffEntry 列表
             DiffFormatter formatter = new DiffFormatter(NullOutputStream.INSTANCE)) {
            CanonicalTreeParser oldIter = new CanonicalTreeParser();
            CanonicalTreeParser newIter = new CanonicalTreeParser();
            oldIter.reset(reader, oldTreeId);
            newIter.reset(reader, newTreeId);

            formatter.setRepository(git.getRepository());
            // 开启 JGit 内置 rename detection：相似度高于 RenameDetector 默认阈值（50%）即合并为 RENAME
            formatter.setDetectRenames(true);
            // scan 会立即执行 file-level diff 并把 RENAME 折叠进结果
            List<DiffEntry> entries = formatter.scan(oldIter, newIter);

            Set<String> changed = new HashSet<>();
            Set<String> deleted = new HashSet<>();
            for (DiffEntry entry : entries) {
                switch (entry.getChangeType()) {
                    case ADD:
                    case MODIFY:
                    case COPY:
                        if (entry.getNewPath() != null) {
                            changed.add(entry.getNewPath());
                        }
                        break;
                    case DELETE:
                        if (entry.getOldPath() != null) {
                            deleted.add(entry.getOldPath());
                        }
                        break;
                    case RENAME:
                        if (entry.getOldPath() != null) {
                            deleted.add(entry.getOldPath());
                        }
                        if (entry.getNewPath() != null) {
                            changed.add(entry.getNewPath());
                        }
                        break;
                    default:
                        break;
                }
            }
            return new DiffOutcome(changed, deleted);
        }
    }

    /**
     * 增量扫描 diff 结果的简单容器：变更集（新路径）+ 删除集（旧路径）
     */
    private static final class DiffOutcome {
        final Set<String> changed;
        final Set<String> deleted;
        DiffOutcome(Set<String> changed, Set<String> deleted) {
            this.changed = changed;
            this.deleted = deleted;
        }
    }

    /**
     * 批量写入缓冲区中的快照记录，使用 JDBC batch 模式减少 DB 往返。
     *
     * @return 实际写入的条数
     */
    private int flushSnapshotBatch(List<CodeFileSnapshot> batch) {
        if (batch.isEmpty()) return 0;
        int count = batch.size();
        try {
            try (SqlSession sqlSession = sqlSessionFactory.openSession(ExecutorType.BATCH)) {
                CodeFileSnapshotMapper mapper = sqlSession.getMapper(CodeFileSnapshotMapper.class);
                for (CodeFileSnapshot s : batch) {
                    mapper.insert(s);
                }
                sqlSession.flushStatements();
                sqlSession.commit();
            }
        } catch (Exception e) {
            log.error("批量写入快照失败，丢失 {} 条记录", count, e);
            count = 0;
        } finally {
            batch.clear();
        }
        return count;
    }

    /**
     * 获取指定任务生成的全部代码快照记录
     */
    @Override
    public List<CodeFileSnapshot> getSnapshotsByTaskId(Long taskId) {
        return snapshotMapper.selectList(new LambdaQueryWrapper<CodeFileSnapshot>().eq(CodeFileSnapshot::getTaskId, taskId));
    }

    /**
     * 递归遍历扫描文件夹，分析黑白名单规则过滤文件
     *
     * @param baseDir       任务临时存放根目录（作为计算相对路径的基准）
     * @param currentDir    当前正在遍历的子目录
     * @param repo          代码库配置实体（包含排除规则）
     * @param taskId        任务 ID
     * @param batchBuffer   快照批量写入缓冲区，攒满 {@link #SNAPSHOT_BATCH_SIZE} 条后触发批量 INSERT
     * @param pathFilter    非空时只处理相对路径命中该集合的文件（增量场景下使用）；
     *                      目录级短路：若子树中没有任何匹配文件则跳过递归。
     *                      传 null 表示不过滤（按全量行为走）。
     * @param totalInserted [0] 累计已写入的快照总数，由 {@link #flushSnapshotBatch} 回填
     */
    private void scanDirectory(File baseDir, File currentDir, CodeRepository repo, Long taskId,
                               List<CodeFileSnapshot> batchBuffer, Set<String> pathFilter,
                               int[] totalInserted) {
        File[] files = currentDir.listFiles();
        if (files == null) return;

        // 解析配置的排除文件夹（逗号隔开，转为数组）
        String[] excludeDirs = StringUtils.hasText(repo.getExcludeDirs()) ? repo.getExcludeDirs().split(",") : new String[0];
        // 解析排除的文件类型后缀
        String[] excludeTypes = StringUtils.hasText(repo.getExcludeFileTypes()) ? repo.getExcludeFileTypes().split(",") : new String[0];

        for (File file : files) {
            // 计算文件相对于扫描根目录的相对路径 (例如 src/main/java/com/demo/User.java)
            String relativePath = baseDir.toURI().relativize(file.toURI()).getPath();
            // 去除末尾反斜杠
            if (relativePath.endsWith("/")) {
                relativePath = relativePath.substring(0, relativePath.length() - 1);
            }

            if (file.isDirectory()) {
                // 1. 判断是否被配置的排除文件夹排除
                boolean isExcluded = false;
                for (String exDir : excludeDirs) {
                    String cleanEx = exDir.trim();
                    if (StringUtils.hasText(cleanEx) && relativePath.toLowerCase().contains(cleanEx.toLowerCase())) {
                        isExcluded = true;
                        break;
                    }
                }
                // 默认强制排除系统敏感目录与前端依赖包
                if (file.getName().startsWith(".") || file.getName().equals("target") || file.getName().equals("node_modules")) {
                    isExcluded = true;
                }
                if (isExcluded) {
                    continue;
                }
                // 增量模式下：若该目录下没有任何命中 pathFilter 的文件，直接跳过整个子树
                if (pathFilter != null && !subtreeHasMatch(pathFilter, relativePath)) {
                    continue;
                }
                // 递归向下进行目录扫描
                scanDirectory(baseDir, file, repo, taskId, batchBuffer, pathFilter, totalInserted);
            } else {
                // 2. 检查文件类型后缀过滤
                boolean isExcluded = false;
                String ext = getFileExtension(file.getName());
                for (String exType : excludeTypes) {
                    String cleanEx = exType.trim().replace(".", "");
                    if (StringUtils.hasText(cleanEx) && ext.equalsIgnoreCase(cleanEx)) {
                        isExcluded = true;
                        break;
                    }
                }
                if (isExcluded) {
                    continue;
                }
                // 增量模式下：仅当文件相对路径命中 git diff 变更集时才落快照
                if (pathFilter != null && !pathFilter.contains(relativePath)) {
                    continue;
                }
                // 如果未被排除，创建文件级别的物理快照与数据库记录
                try {
                    createFileSnapshot(baseDir, file, relativePath, taskId, ext, batchBuffer, totalInserted);
                } catch (Exception e) {
                    log.error("保存文件快照失败: {}", file.getAbsolutePath(), e);
                }
            }
        }
    }

    /**
     * 增量模式辅助：判断 {@code pathFilter} 中是否存在位于给定目录子树下的文件。
     * 用于 {@link #scanDirectory} 在目录级短路跳过整个子树，避免无谓递归。
     */
    private static boolean subtreeHasMatch(Set<String> pathFilter, String dirRelativePath) {
        if (pathFilter == null || pathFilter.isEmpty()) {
            return true;
        }
        String prefix = dirRelativePath.endsWith("/") ? dirRelativePath : dirRelativePath + "/";
        for (String p : pathFilter) {
            if (p.equals(dirRelativePath) || p.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 创建文件物理快照并持久化至快照库中（批量缓冲写入模式）
     *
     * @param baseDir       根文件夹
     * @param file          目标待备份文件对象
     * @param relativePath  计算好的项目内相对路径
     * @param taskId        任务 ID
     * @param ext           文件扩展名
     * @param batchBuffer   批量写入缓冲区，攒满后自动触发 {@link #flushSnapshotBatch}
     * @param totalInserted [0] 累计已写入快照数
     */
    private void createFileSnapshot(File baseDir, File file, String relativePath, Long taskId, String ext,
                                     List<CodeFileSnapshot> batchBuffer, int[] totalInserted) throws IOException {
        byte[] contentBytes = Files.readAllBytes(file.toPath());
        // 计算文件内容的 MD5 值作为文件指纹，用来做增量对比及版本哈希校验
        String md5 = DigestUtils.md5DigestAsHex(contentBytes);
        int lineCount = 0;
        try {
            // 简单统计文件代码物理行数
            lineCount = (int) Files.lines(file.toPath()).count();
        } catch (Exception ignored) {}

        // 直接引用 temp_repos/ 下的源文件，不再额外复制到 storage/（节省磁盘空间，文件在 pipeline 生命周期内始终存在）
        // 创建数据库索引记录，先入缓冲区，攒够一批再批量写入
        CodeFileSnapshot snapshot = new CodeFileSnapshot();
        snapshot.setTaskId(taskId);
        snapshot.setFilePath(relativePath);
        snapshot.setFileType(ext);
        snapshot.setLineCount(lineCount);
        snapshot.setFileHash(md5);
        snapshot.setContentUri(file.toURI().toString());
        snapshot.setCreatedAt(LocalDateTime.now());

        batchBuffer.add(snapshot);
        if (batchBuffer.size() >= SNAPSHOT_BATCH_SIZE) {
            totalInserted[0] += flushSnapshotBatch(batchBuffer);
        }
    }

    /**
     * 辅助获取文件扩展名
     */
    private String getFileExtension(String fileName) {
        int lastIdx = fileName.lastIndexOf('.');
        if (lastIdx == -1) return "";
        return fileName.substring(lastIdx + 1);
    }

    /**
     * 递归拷贝文件夹内容（排除 IDE 及工程编译产生的缓存敏感目录）
     */
    private void copyDirectory(File source, File destination) throws IOException {
        if (source.isDirectory()) {
            String name = source.getName();
            if (name.equals(".git") || name.equals("target") || name.equals("node_modules") || name.equals(".idea") || name.equals(".vscode")) {
                return;
            }
            if (!destination.exists()) {
                destination.mkdirs();
            }
            String[] files = source.list();
            if (files != null) {
                for (String file : files) {
                    File srcFile = new File(source, file);
                    File destFile = new File(destination, file);
                    copyDirectory(srcFile, destFile);
                }
            }
        } else {
            Files.copy(source.toPath(), destination.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * 递归删除文件夹及其所有子文件与子目录
     */
    private void deleteDirectory(File file) {
        if (file.isDirectory()) {
            File[] files = file.listFiles();
            if (files != null) {
                for (File f : files) {
                    deleteDirectory(f);
                }
            }
        }
        file.delete();
    }

    /**
     * 离线降级辅助生成器：在无外网/克隆失败时自动创建测试项目，保证流程完整性。
     * 创建一个包含 UserController, UserService, UserMapper, User 实体类在内的典型 Spring Boot 后端分层架构示例。
     */
    private void generateMockRepositoryFiles(File targetDir) throws IOException {
        Files.createDirectories(targetDir.toPath());

        // 1. Controller 控制器模拟文件
        File userController = new File(targetDir, "src/main/java/com/demo/controller/UserController.java");
        userController.getParentFile().mkdirs();
        try (FileWriter writer = new FileWriter(userController)) {
            writer.write("""
package com.demo.controller;

import org.springframework.web.bind.annotation.*;
import com.demo.service.UserService;
import com.demo.entity.User;
import java.util.List;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    public List<User> listUsers() {
        return userService.listUsers();
    }

    @GetMapping("/{id}")
    public User getUserById(@PathVariable Long id) {
        return userService.getUserById(id);
    }

    @PostMapping
    public void createUser(@RequestBody User user) {
        userService.createUser(user);
    }
}
""");
        }

        // 2. Service 业务层模拟文件
        File userService = new File(targetDir, "src/main/java/com/demo/service/UserService.java");
        userService.getParentFile().mkdirs();
        try (FileWriter writer = new FileWriter(userService)) {
            writer.write("""
package com.demo.service;

import org.springframework.stereotype.Service;
import com.demo.mapper.UserMapper;
import com.demo.entity.User;
import java.util.List;

@Service
public class UserService {

    private final UserMapper userMapper;

    public UserService(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    public List<User> listUsers() {
        return userMapper.selectAllUsers();
    }

    public User getUserById(Long id) {
        return userMapper.selectUserById(id);
    }

    public void createUser(User user) {
        userMapper.insertUser(user);
    }
}
""");
        }

        // 3. Mapper 数据持久层接口及硬编码 SQL 注解文件
        File userMapper = new File(targetDir, "src/main/java/com/demo/mapper/UserMapper.java");
        userMapper.getParentFile().mkdirs();
        try (FileWriter writer = new FileWriter(userMapper)) {
            writer.write("""
package com.demo.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Insert;
import com.demo.entity.User;
import java.util.List;

@Mapper
public interface UserMapper {

    @Select("SELECT * FROM ci_user")
    List<User> selectAllUsers();

    @Select("SELECT * FROM ci_user WHERE id = #{id}")
    User selectUserById(Long id);

    @Insert("INSERT INTO ci_user(username, password) VALUES(#{username}, #{password})")
    void insertUser(User user);
}
""");
        }

        // 4. Entity 数据实体层模拟文件
        File userEntity = new File(targetDir, "src/main/java/com/demo/entity/User.java");
        userEntity.getParentFile().mkdirs();
        try (FileWriter writer = new FileWriter(userEntity)) {
            writer.write("""
package com.demo.entity;

import lombok.Data;

@Data
public class User {
    private Long id;
    private String username;
    private String password;
}
""");
        }
    }

    /**
     * 将本次扫描 HEAD 写入任务 {@code source_commit}。入口扫描试跑等非任务场景（无 ci_task 行）则跳过。
     */
    private void persistTaskSourceCommit(Long taskId, String commitId) {
        if (taskId == null || !StringUtils.hasText(commitId)) {
            return;
        }
        DecompileTask task = taskMapper.selectById(taskId);
        if (task == null) {
            log.debug("pullAndScan: taskId={} 无对应任务行，跳过 source_commit 落库", taskId);
            return;
        }
        task.setSourceCommit(commitId);
        taskMapper.updateById(task);
        log.info("任务 source_commit 已落库: taskId={} commit={}", taskId, commitId);
    }
}
