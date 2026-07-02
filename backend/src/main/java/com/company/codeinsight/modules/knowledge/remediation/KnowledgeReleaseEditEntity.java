package com.company.codeinsight.modules.knowledge.remediation;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ci_knowledge_release_edit")
public class KnowledgeReleaseEditEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long repositoryId;
    private Long versionId;
    private String relativePath;
    private String contentText;
    private String status;
    private String submittedBy;
    private String approvedBy;
    private LocalDateTime createdAt;
    private LocalDateTime approvedAt;
}
