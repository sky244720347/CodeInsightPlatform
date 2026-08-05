package com.company.codeinsight.modules.scanwindow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.company.codeinsight.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 定时 commit 探测流水，对应 {@code ci_scan_probe_record}。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ci_scan_probe_record")
public class ScanProbeRecordEntity extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private LocalDate probeDate;

    private Long repositoryId;

    private Long systemId;

    private Integer attemptNo;

    /** SUCCESS / FAILED / SKIPPED_LOCAL / INCONCLUSIVE / DEFERRED_DISPATCH / DISPATCH_FAILED */
    private String status;

    private String remoteHead;

    private String baselineCommit;

    /** INITIAL / INCREMENTAL / NONE */
    private String dispatchAction;

    private Long taskId;

    private String message;

    private LocalDateTime probedAt;
}
