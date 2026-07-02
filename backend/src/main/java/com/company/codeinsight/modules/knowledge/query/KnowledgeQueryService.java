package com.company.codeinsight.modules.knowledge.query;

import com.company.codeinsight.modules.entrypoint.model.EntrypointReviewView;
import com.company.codeinsight.modules.hierarchy.model.ModuleHierarchy;
import com.company.codeinsight.modules.knowledge.query.dto.KnowledgeContextView;

import java.util.List;

public interface KnowledgeQueryService {

    KnowledgeContextView getContext(Long repositoryId);

    List<EntrypointReviewView> listPublishedEntrypoints(Long systemId, Long repositoryId);

    ModuleHierarchy getPublishedHierarchy(Long systemId, Long repositoryId);
}
