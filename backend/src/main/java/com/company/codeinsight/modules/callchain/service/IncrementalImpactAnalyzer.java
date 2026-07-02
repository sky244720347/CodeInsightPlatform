package com.company.codeinsight.modules.callchain.service;

import com.company.codeinsight.modules.callchain.model.IncrementalImpact;
import com.company.codeinsight.modules.entrypoint.model.EntryPoint;
import com.company.codeinsight.modules.hierarchy.model.ModuleHierarchy;
import com.company.codeinsight.modules.scanner.model.IncrementalContext;

import java.io.File;
import java.util.List;

public interface IncrementalImpactAnalyzer {

    IncrementalImpact analyze(Long taskId,
                                File projectDir,
                                IncrementalContext ctx,
                                ModuleHierarchy hierarchy,
                                List<EntryPoint> enabledEntries,
                                String baselineCommitId,
                                String headCommitId,
                                String scanMode);
}
