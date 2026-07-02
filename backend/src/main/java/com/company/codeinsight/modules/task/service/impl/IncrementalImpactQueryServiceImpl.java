package com.company.codeinsight.modules.task.service.impl;

import com.company.codeinsight.common.exception.BusinessException;
import com.company.codeinsight.modules.callchain.service.impl.IncrementalImpactPersistenceImpl;
import com.company.codeinsight.modules.knowledge.remediation.KnowledgeRemediationConstants;
import com.company.codeinsight.modules.task.dto.IncrementalImpactDto;
import com.company.codeinsight.modules.task.entity.DecompileTask;
import com.company.codeinsight.modules.task.mapper.DecompileTaskMapper;
import com.company.codeinsight.modules.task.service.IncrementalImpactQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class IncrementalImpactQueryServiceImpl implements IncrementalImpactQueryService {

    private final DecompileTaskMapper taskMapper;
    private final IncrementalImpactPersistenceImpl impactPersistence;

    @Override
    public IncrementalImpactDto getByTaskId(Long taskId) {
        DecompileTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException("任务不存在");
        }
        if (!"INCREMENTAL".equalsIgnoreCase(task.getType())) {
            IncrementalImpactDto dto = new IncrementalImpactDto();
            dto.setIncremental(false);
            dto.setAvailable(true);
            dto.setScanMode("INITIAL");
            dto.setMessage("全量扫描任务，无增量影响分析");
            return dto;
        }
        if (KnowledgeRemediationConstants.TRIGGER_SOURCE.equals(task.getTriggerSource())) {
            IncrementalImpactDto dto = new IncrementalImpactDto();
            dto.setIncremental(false);
            dto.setAvailable(true);
            dto.setScanMode("REMEDIATION");
            dto.setMessage("知识纠错任务，不适用增量影响分析");
            return dto;
        }
        IncrementalImpactDto stored = impactPersistence.read(taskId);
        if (stored != null) {
            return stored;
        }
        IncrementalImpactDto pending = new IncrementalImpactDto();
        pending.setIncremental(true);
        pending.setAvailable(false);
        pending.setScanMode("INCREMENTAL");
        pending.setMessage("影响分析将在入口确认后执行，或任务尚未完成扫描阶段");
        return pending;
    }
}
