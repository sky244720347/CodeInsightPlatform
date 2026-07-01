package com.company.codeinsight.modules.entrypoint.trial;

import lombok.Data;

import java.time.LocalDateTime;

/** 试跑记录摘要（列表/历史用，不含完整 result_json） */
@Data
public class EntryScanTrialSummary {

    private Long id;
    private Long repositoryId;
    private String userId;
    private String status;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    /** 识别到的入口类数量（SUCCESS 时有值） */
    private Integer entryCount;
    private String errorMessage;
}
