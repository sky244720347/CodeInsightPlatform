package com.company.codeinsight.modules.knowledge.remediation;

import com.company.codeinsight.modules.knowledge.remediation.dto.DocumentRemediationRequest;
import com.company.codeinsight.modules.knowledge.remediation.dto.EntrypointRemediationRequest;
import com.company.codeinsight.modules.knowledge.remediation.dto.HierarchyRemediationRequest;
import com.company.codeinsight.modules.knowledge.remediation.dto.RemediationTaskResponse;

public interface KnowledgeRemediationService {

    RemediationTaskResponse remediateEntrypoints(EntrypointRemediationRequest request);

    RemediationTaskResponse remediateHierarchy(HierarchyRemediationRequest request);

    RemediationTaskResponse remediateDocuments(DocumentRemediationRequest request);
}
