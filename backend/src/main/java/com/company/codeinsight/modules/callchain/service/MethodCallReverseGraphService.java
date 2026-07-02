package com.company.codeinsight.modules.callchain.service;

import com.company.codeinsight.modules.callchain.model.EntryMethodHit;

import java.util.List;
import java.util.Set;

public interface MethodCallReverseGraphService {

    /**
     * 从变更类出发，沿 ci_method_call 反向 BFS，找到调用链上游的入口类方法。
     */
    List<EntryMethodHit> resolveCallingEntries(Long taskId,
                                               String changedFqcn,
                                               Set<String> entryClassNames,
                                               int maxDepth);
}
