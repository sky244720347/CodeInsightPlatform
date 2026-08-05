package com.company.codeinsight.modules.scanwindow.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.company.codeinsight.modules.scanwindow.dto.ScanOrchestrationSummary;
import com.company.codeinsight.modules.scanwindow.dto.ScanProbeRecordView;
import com.company.codeinsight.modules.scanwindow.entity.ScanProbeRecordEntity;

import java.time.LocalDate;

public interface ScanProbeRecordService {

    void insert(ScanProbeRecordEntity record);

    ScanOrchestrationSummary summary(LocalDate date);

    Page<ScanProbeRecordView> pageRecords(LocalDate date, String status, String keyword, int current, int size);

    /** 需探测总量：gitUrl 非空且非本地路径 */
    long countProbeTargets();
}
