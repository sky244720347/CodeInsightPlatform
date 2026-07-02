package com.company.codeinsight.modules.task.service;

import com.company.codeinsight.modules.task.dto.IncrementalImpactDto;

public interface IncrementalImpactQueryService {

    IncrementalImpactDto getByTaskId(Long taskId);
}
