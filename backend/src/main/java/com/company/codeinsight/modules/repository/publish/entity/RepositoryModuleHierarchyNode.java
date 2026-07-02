package com.company.codeinsight.modules.repository.publish.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ci_repository_module_hierarchy")
public class RepositoryModuleHierarchyNode {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long repositoryId;
    private Long systemId;
    private String level;
    private Long parentId;
    private String nodeId;
    private String name;
    private String keywords;

    @TableField("class_paths")
    private String classPaths;

    @TableField("method_signatures")
    private String methodSignatures;

    private Boolean confirmed;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
