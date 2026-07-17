package com.company.codeinsight.modules.scanner.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.company.codeinsight.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 代码扫描文件快照实体类
 * 对应数据库中的 ci_file_snapshot 表，记录对目标 Git 仓库拉取后进行扫描产生的所有有效代码文件元数据及路径信息。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ci_file_snapshot")
public class CodeFileSnapshot extends BaseEntity {

    /**
     * 自增主键 ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 关联的任务 ID
     */
    private Long taskId;

    /**
     * 源码文件的相对路径（相对于仓库根路径）
     */
    private String filePath;

    /**
     * 文件后缀扩展名类型（如 java, xml, js 等）
     */
    private String fileType;

    /**
     * 该文件的物理代码总行数
     */
    private Integer lineCount;

    /**
     * 文件源码内容的 MD5 哈希校验指纹（用于检测内容是否变动）
     */
    private String fileHash;

    /**
     * 该扫描文件在本地所存储的物理 URI 路径
     */
    private String contentUri;
}
