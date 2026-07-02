import { useCallback, useEffect, useState } from 'react';
import { listRepositories } from '../../api/repository';
import { getKnowledgeContext, type KnowledgeContextView } from '../../api/knowledge-query';
import { listSystems } from '../../api/system';
import type { Repository, System } from '../../types';

const STORAGE_KEY = 'ci-knowledge-query-context';

interface StoredContext {
  systemId?: number;
  repositoryId?: number;
}

function readStored(): StoredContext {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return {};
    return JSON.parse(raw) as StoredContext;
  } catch {
    return {};
  }
}

function writeStored(value: StoredContext) {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(value));
  } catch {
    /* ignore */
  }
}

export function useKnowledgeQueryContext() {
  const [systems, setSystems] = useState<System[]>([]);
  const [repositories, setRepositories] = useState<Repository[]>([]);
  const [systemId, setSystemIdState] = useState<number | undefined>(() => readStored().systemId);
  const [repositoryId, setRepositoryIdState] = useState<number | undefined>(
    () => readStored().repositoryId,
  );
  const [context, setContext] = useState<KnowledgeContextView | null>(null);
  const [contextLoading, setContextLoading] = useState(false);

  useEffect(() => {
    listSystems({ current: 1, size: 200 })
      .then((data) => setSystems(data.records ?? []))
      .catch(() => undefined);
  }, []);

  useEffect(() => {
    if (systemId == null) {
      setRepositories([]);
      return;
    }
    listRepositories({ current: 1, size: 200, systemId })
      .then((data) => setRepositories(data.records ?? []))
      .catch(() => setRepositories([]));
  }, [systemId]);

  const setSystemId = useCallback((id?: number) => {
    setSystemIdState(id);
    setRepositoryIdState(undefined);
    writeStored({ systemId: id });
  }, []);

  const setRepositoryId = useCallback((id?: number) => {
    setRepositoryIdState(id);
    writeStored({ systemId, repositoryId: id });
  }, [systemId]);

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
        writeStored({ systemId: data.systemId, repositoryId });
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
