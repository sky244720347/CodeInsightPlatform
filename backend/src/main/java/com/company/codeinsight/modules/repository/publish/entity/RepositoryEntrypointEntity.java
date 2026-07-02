package com.company.codeinsight.modules.repository.publish.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ci_repository_entrypoint")
public class RepositoryEntrypointEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long repositoryId;
    private Long systemId;
    private String className;

    @TableField("file_path")
    private String filePath;

    @TableField("entry_type")
    private String entryType;

    private String annotation;
    private String remark;

    @TableField("methods_json")
    private String methodsJson;

    @TableField("sort_order")
    private Integer sortOrder;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
