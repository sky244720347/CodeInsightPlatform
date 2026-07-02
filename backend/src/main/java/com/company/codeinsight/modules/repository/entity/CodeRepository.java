package com.company.codeinsight.modules.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.company.codeinsight.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;

/**
 * Git 代码仓库配置实体类
 * 对应数据库中的 ci_repository 表，存储 Git 仓库地址、克隆分支、访问帐密、扫描过滤规则及最近一次分析详情。
 * 通过 deleted_at + @TableLogic 实现软删除。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ci_repository")
public class CodeRepository extends BaseEntity {

    /**
     * 自增主键 ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 所属业务系统 ID
     */
    private Long systemId;

    /**
     * Git 仓库的远程 clone URL 地址（支持 HTTP/HTTPS，或本地文件绝对目录路径以支持本地测试）
     */
    private String gitUrl;

    /**
     * 目标拉取的分支名（如 main, master）
     */
    private String branch;

    /**
     * 访问 Git 的用户名（私有仓库）
     */
    private String username;

    /**
     * 访问 Git 的密码或 Access Token（私有仓库）
     */
    private String password;

    /**
     * 推送目标 Git 仓库 URL（为空则使用 git_url）
     */
    private String pushGitUrl;

    /**
     * 推送目标分支名（为空则默认 docs-code-insight）
     */
    private String pushBranch;

    /**
     * 推送目标 Git 凭证用户名
     */
    private String pushUsername;

    /**
     * 推送目标 Git 凭证密码或 Token
     */
    private String pushPassword;

    /**
     * 文档在仓库中的目标文件夹路径（默认 docs/code-insight）
     */
    private String pushTargetFolder;

    /**
     * 项目扫描时的起始相对路径根目录（默认为项目根路径）
     */
    private String scanRoot;

    /**
     * 排除不进行扫描的文件夹名（以逗号分隔，如 target, .git, node_modules）
     */
    private String excludeDirs;

    /**
     * 排除不进行解析分析的文件后缀类型（以逗号分隔，如 .png, .jpg, .zip, .pdf）
     */
    private String excludeFileTypes;

    /**
     * 扫描时所拉取的最近一次 Git 提交 Commit ID
     */
    private String lastCommitId;

    /**
     * 最近一次触发反编译或分析任务的执行时间
     */
    private LocalDateTime lastDecompileAt;

    /**
     * 仓库级入口扫描配置 JSON 字符串（整体序列化 EntryPointConfig）
     * 新建知识构建任务时默认带出此配置；任务可单独配置覆盖该默认值。
     * null 表示该仓库未配置入口扫描规则，新建任务时直接走默认 Controller/JOB/MQ 兜底。
     */
    @TableField("entry_scan_config")
    private String entryScanConfig;

    /**
     * 逻辑删除时间。NULL=未删除，非空=已删除时间。
     */
    /** 仓库级模块提取提示词 ID（FK → ci_prompt.id） */
    private Long modularizePromptId;

    /** 仓库级文档生成提示词 ID（FK → ci_prompt.id） */
    private Long documentPromptId;

    /** 最近一次成功发布到仓库的来源任务 ID */
    private Long lastPublishedTaskId;

    /** 最近一次成功发布到仓库的知识版本 ID */
    private Long lastPublishedVersionId;

    /** 最近一次成功发布到仓库的时间 */
    private LocalDateTime publishedAt;

    /** 最近一次成功发布到仓库的操作人 */
    private String publishedBy;

    @TableLogic(value = "null", delval = "now()")
    private LocalDateTime deletedAt;
}

