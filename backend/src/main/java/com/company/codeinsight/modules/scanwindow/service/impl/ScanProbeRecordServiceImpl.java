package com.company.codeinsight.modules.scanwindow.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.company.codeinsight.common.config.ScanProperties;
import com.company.codeinsight.modules.repository.entity.CodeRepository;
import com.company.codeinsight.modules.repository.mapper.CodeRepositoryMapper;
import com.company.codeinsight.modules.scanwindow.dto.ScanOrchestrationSummary;
import com.company.codeinsight.modules.scanwindow.dto.ScanProbeRecordView;
import com.company.codeinsight.modules.scanwindow.entity.ScanProbeRecordEntity;
import com.company.codeinsight.modules.scanwindow.mapper.ScanProbeRecordMapper;
import com.company.codeinsight.modules.scanwindow.service.ScanProbeRecordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.File;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ScanProbeRecordServiceImpl implements ScanProbeRecordService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ScanProbeRecordMapper probeRecordMapper;
    private final CodeRepositoryMapper repositoryMapper;
    private final ScanProperties scanProperties;

    @Override
    public void insert(ScanProbeRecordEntity record) {
        if (record == null) {
            return;
        }
        try {
            LocalDateTime now = LocalDateTime.now();
            if (record.getProbeDate() == null) {
                record.setProbeDate(now.toLocalDate());
            }
            if (record.getProbedAt() == null) {
                record.setProbedAt(now);
            }
            if (record.getAttemptNo() == null && record.getRepositoryId() != null) {
                int max = probeRecordMapper.maxAttemptNo(record.getProbeDate(), record.getRepositoryId());
                record.setAttemptNo(max + 1);
            }
            probeRecordMapper.insert(record);
        } catch (Exception e) {
            log.error("scan probe record insert failed repoId={}: {}", record.getRepositoryId(), e.toString());
        }
    }

    @Override
    public ScanOrchestrationSummary summary(LocalDate date) {
        LocalDate d = date != null ? date : LocalDate.now();
        long target = countProbeTargets();
        long settled = probeRecordMapper.countSettledDistinct(d);
        long retryPending = probeRecordMapper.countRetryPendingDistinct(d);
        long probed = settled + retryPending;
        long unprobed = Math.max(0L, target - probed);
        long attempt = probeRecordMapper.selectCount(new LambdaQueryWrapper<ScanProbeRecordEntity>()
                .eq(ScanProbeRecordEntity::getProbeDate, d));
        long dispatched = probeRecordMapper.countDispatched(d);

        return ScanOrchestrationSummary.builder()
                .probeDate(d.toString())
                .probeTargetTotal(target)
                .probedCount(probed)
                .settledSuccessCount(settled)
                .retryPendingCount(retryPending)
                .unprobedCount(unprobed)
                .attemptCount(attempt)
                .dispatchedCount(dispatched)
                .globalPollEnabled(scanProperties.isGlobalPollEnabled())
                .forceFullOnUnchanged(scanProperties.isForceFullOnUnchanged())
                .dailyCoverageEnabled(scanProperties.isDailyCoverageEnabled())
                .build();
    }

    @Override
    public Page<ScanProbeRecordView> pageRecords(LocalDate date, String status, String keyword,
                                                 int current, int size) {
        LocalDate d = date != null ? date : LocalDate.now();
        int pageNo = Math.max(1, current);
        int pageSize = Math.min(Math.max(1, size), 200);

        Set<Long> repoFilter = null;
        if (StringUtils.hasText(keyword)) {
            String kw = keyword.trim();
            LambdaQueryWrapper<CodeRepository> rq = new LambdaQueryWrapper<CodeRepository>()
                    .select(CodeRepository::getId, CodeRepository::getGitUrl)
                    .like(CodeRepository::getGitUrl, kw);
            if (kw.matches("\\d+")) {
                long id = Long.parseLong(kw);
                rq = new LambdaQueryWrapper<CodeRepository>()
                        .select(CodeRepository::getId, CodeRepository::getGitUrl)
                        .and(w -> w.like(CodeRepository::getGitUrl, kw).or().eq(CodeRepository::getId, id));
            }
            List<CodeRepository> matched = repositoryMapper.selectList(rq);
            repoFilter = matched.stream().map(CodeRepository::getId).filter(Objects::nonNull)
                    .collect(Collectors.toCollection(HashSet::new));
            if (repoFilter.isEmpty()) {
                Page<ScanProbeRecordView> empty = new Page<>(pageNo, pageSize, 0);
                empty.setRecords(List.of());
                return empty;
            }
        }

        LambdaQueryWrapper<ScanProbeRecordEntity> q = new LambdaQueryWrapper<ScanProbeRecordEntity>()
                .eq(ScanProbeRecordEntity::getProbeDate, d)
                .eq(StringUtils.hasText(status), ScanProbeRecordEntity::getStatus, status)
                .in(repoFilter != null, ScanProbeRecordEntity::getRepositoryId, repoFilter)
                .orderByDesc(ScanProbeRecordEntity::getProbedAt)
                .orderByDesc(ScanProbeRecordEntity::getId);

        Page<ScanProbeRecordEntity> raw = probeRecordMapper.selectPage(new Page<>(pageNo, pageSize), q);
        Set<Long> ids = raw.getRecords().stream()
                .map(ScanProbeRecordEntity::getRepositoryId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, String> gitUrlById = new HashMap<>();
        if (!ids.isEmpty()) {
            List<CodeRepository> repos = repositoryMapper.selectList(new LambdaQueryWrapper<CodeRepository>()
                    .select(CodeRepository::getId, CodeRepository::getGitUrl)
                    .in(CodeRepository::getId, ids));
            for (CodeRepository r : repos) {
                gitUrlById.put(r.getId(), r.getGitUrl());
            }
        }

        Page<ScanProbeRecordView> out = new Page<>(raw.getCurrent(), raw.getSize(), raw.getTotal());
        out.setRecords(raw.getRecords().stream().map(e -> ScanProbeRecordView.builder()
                .id(e.getId())
                .repositoryId(e.getRepositoryId())
                .systemId(e.getSystemId())
                .gitUrl(gitUrlById.get(e.getRepositoryId()))
                .attemptNo(e.getAttemptNo())
                .status(e.getStatus())
                .remoteHead(e.getRemoteHead())
                .baselineCommit(e.getBaselineCommit())
                .dispatchAction(e.getDispatchAction())
                .taskId(e.getTaskId())
                .message(e.getMessage())
                .probedAt(e.getProbedAt() != null ? e.getProbedAt().format(TS) : null)
                .build()).toList());
        return out;
    }

    @Override
    public long countProbeTargets() {
        List<CodeRepository> all = repositoryMapper.selectList(new LambdaQueryWrapper<CodeRepository>()
                .select(CodeRepository::getId, CodeRepository::getGitUrl));
        long n = 0;
        for (CodeRepository r : all) {
            if (isProbeTarget(r.getGitUrl())) {
                n++;
            }
        }
        return n;
    }

    public static boolean isProbeTarget(String gitUrl) {
        if (!StringUtils.hasText(gitUrl)) {
            return false;
        }
        return !isLocalPathRepo(gitUrl.trim());
    }

    static boolean isLocalPathRepo(String gitUrl) {
        if (!StringUtils.hasText(gitUrl)) {
            return false;
        }
        File probe = new File(gitUrl.trim());
        return probe.exists() && probe.isDirectory();
    }
}
