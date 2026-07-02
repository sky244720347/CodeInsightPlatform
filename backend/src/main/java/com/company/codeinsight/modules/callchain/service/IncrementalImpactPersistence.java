package com.company.codeinsight.modules.callchain.service;

import com.company.codeinsight.modules.callchain.model.IncrementalImpact;
import com.company.codeinsight.modules.scanner.model.IncrementalContext;

import java.util.List;

public interface IncrementalImpactPersistence {

    void persist(Long taskId, IncrementalImpact impact, IncrementalContext ctx);

    void delete(Long taskId);
}
