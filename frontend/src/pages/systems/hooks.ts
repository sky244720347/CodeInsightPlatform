import { useCallback, useEffect, useState } from 'react';
import { listSystems } from '../../api/system';
import { listRepositories } from '../../api/repository';
import type { Repository, System } from '../../types';

/**
 * 系统列表数据 hook
 *
 * 封装列表数据获取 + 搜索条件 + 分页 + 重新拉取。
 * 父组件只关心：状态 + 主动调用 fetch(force)。
 */
export function useSystemsList() {
  const [systems, setSystems] = useState<System[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [current, setCurrent] = useState(1);
  const [size, setSize] = useState(10);

  // 搜索条件
  const [searchName, setSearchName] = useState('');
  const [searchComponent, setSearchComponent] = useState('');
  const [searchOwner, setSearchOwner] = useState('');

  const fetch = useCallback(
    async (page = current, pageSize = size) => {
      setLoading(true);
      try {
        const data = await listSystems({
          current: page,
          size: pageSize,
          name: searchName || undefined,
          component: searchComponent || undefined,
          owner: searchOwner || undefined,
        });
        setSystems(data.records);
        setTotal(data.total);
      } finally {
        setLoading(false);
      }
    },
    [current, searchComponent, searchName, searchOwner, size],
  );

  // 任意依赖变化都自动重新拉取
  useEffect(() => {
    fetch();
  }, [fetch]);

  const handleSearch = useCallback(() => {
    setCurrent(1);
    fetch(1);
  }, [fetch]);

  const handleReset = useCallback(() => {
    setSearchName('');
    setSearchComponent('');
    setSearchOwner('');
    setCurrent(1);
    setTimeout(() => fetch(1), 0);
  }, [fetch]);

  return {
    // 数据
    systems,
    total,
    loading,
    // 分页
    current,
    size,
    setCurrent,
    setSize,
    // 搜索
    searchName,
    searchComponent,
    searchOwner,
    setSearchName,
    setSearchComponent,
    setSearchOwner,
    // 操作
    fetch,
    handleSearch,
    handleReset,
  };
}

/**
 * 代码库列表数据 hook（按系统 ID 加载）
 *
 * 通过传 null / number 切换：传 null 时清空列表
 */
export function useRepositories(systemId: number | null) {
  const [repositories, setRepositories] = useState<Repository[]>([]);
  const [loading, setLoading] = useState(false);

  const fetch = useCallback(async (id: number) => {
    setLoading(true);
    try {
      const data = await listRepositories({ current: 1, size: 50, systemId: id });
      setRepositories(data.records);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (systemId === null) {
      setRepositories([]);
      return;
    }
    fetch(systemId);
  }, [systemId, fetch]);

  return { repositories, loading, refresh: fetch, setRepositories };
}
