package com.company.codeinsight.modules.scanwindow.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ScanProbeRecordView {
    private Long id;
    private Long repositoryId;
    private Long systemId;
    private String gitUrl;
    private Integer attemptNo;
    private String status;
    private String remoteHead;
    private String baselineCommit;
    private String dispatchAction;
    private Long taskId;
    private String message;
    private String probedAt;
}
