import { useCallback, useEffect, useState } from 'react';
import { listRepositories } from '../../api/repository';
import { getKnowledgeContext, type KnowledgeContextView } from '../../api/knowledge-query';
import { listSystems } from '../../api/system';
import type { Repository, System } from '../../types';

export function useKnowledgeQueryContext() {
  const [systems, setSystems] = useState<System[]>([]);
  const [repositories, setRepositories] = useState<Repository[]>([]);
  // 默认空,由用户进入页面后自行选择系统 / 仓库
  const [systemId, setSystemIdState] = useState<number | undefined>(undefined);
  const [repositoryId, setRepositoryIdState] = useState<number | undefined>(undefined);
  const [context, setContext] = useState<KnowledgeContextView | null>(null);
  const [contextLoading, setContextLoading] = useState(false);

  useEffect(() => {
    listSystems({ current: 1, size: 200, hasPublished: true })
      .then((data) => setSystems(data.records ?? []))
      .catch(() => undefined);
  }, []);

  useEffect(() => {
    if (systemId == null) {
      setRepositories([]);
      return;
    }
    listRepositories({ current: 1, size: 200, systemId, hasPublished: true })
      .then((data) => setRepositories(data.records ?? []))
      .catch(() => setRepositories([]));
  }, [systemId]);

  const setSystemId = useCallback((id?: number) => {
    setSystemIdState(id);
    setRepositoryIdState(undefined);
  }, []);

  const setRepositoryId = useCallback((id?: number) => {
    setRepositoryIdState(id);
  }, []);

  const refreshContext = useCallback(async () => {
    if (repositoryId == null) {
      setContext(null);
      return;
    }
    setContextLoading(true);
    try {
      const data = await getKnowledgeContext(repositoryId);
      setContext(data);
      if (data.systemId != null && data.systemId !== systemId) {
        setSystemIdState(data.systemId);
      }
    } catch {
      setContext(null);
    } finally {
      setContextLoading(false);
    }
  }, [repositoryId, systemId]);

  useEffect(() => {
    refreshContext();
  }, [refreshContext]);

  return {
    systems,
    repositories,
    systemId,
    setSystemId,
    repositoryId,
    setRepositoryId,
    context,
    contextLoading,
    refreshContext,
  };
}
