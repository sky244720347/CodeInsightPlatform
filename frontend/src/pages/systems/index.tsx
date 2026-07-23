import React, { useCallback, useEffect, useState } from 'react';
import { Card, Form, Space, Table, message } from 'antd';
import { useNavigate, useSearchParams } from 'react-router-dom';
import {
  deleteSystem,
  getSystem,
  updateSystem,
} from '../../api/system';
import {
  createRepository,
  deleteRepository,
  getRepository,
  testRepositoryConnection,
  updateRepository,
} from '../../api/repository';
import type { Repository, System } from '../../types';
import { getSystemColumns } from './columns';
import SystemFilterBar from './SystemFilterBar';
import SystemFormModal, { type SystemFormValues } from './SystemFormModal';
import SystemWizardModal from './SystemWizardModal';
import SystemPromptBindModal from './SystemPromptBindModal';
import SystemBusinessKnowledgeModal from './SystemBusinessKnowledgeModal';
import RepositoryDrawer from './RepositoryDrawer';
import RepositoryFormModal, { type RepositoryFormValues } from './RepositoryFormModal';
import RepositoryScanConfigModal, { type ScanConfigFormValues } from './RepositoryScanConfigModal';
import ScanWindowModal from './ScanWindowModal';
import { parseRepoEntryScanConfig } from './repositoryUtils';
import { useRepositories, useSystemsList } from './hooks';

/**
 * 系统与代码库管理主页面
 *
 *  关注点拆分：
 *   - 数据获取   → hooks.ts (useSystemsList / useRepositories)
 *   - 视觉组件   → SystemFilterBar / SystemWizardModal / RepositoryDrawer
 *   - 列定义     → columns.tsx
 *   - 本文件     → 编排：handlers + 弹窗状态 + 渲染
 *
 *  <p>系统级启停状态机已删除：表头无"状态"列、行内无"启停"Switch。</p>
 */
const Systems: React.FC = () => {
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();

  // ===== 数据 =====
  const list = useSystemsList();
  const { systems, total, loading, current, size, setCurrent, setSize } = list;

  // ===== Wizard（新增系统）=====
  const [wizardOpen, setWizardOpen] = useState(false);
  const openWizard = useCallback(() => setWizardOpen(true), []);

  // ===== 编辑基本信息（name / nameCn / owner / description）=====
  const [editModalOpen, setEditModalOpen] = useState(false);
  const [editingSystem, setEditingSystem] = useState<System | null>(null);
  const [editSubmitting, setEditSubmitting] = useState(false);
  const [editForm] = Form.useForm<SystemFormValues>();

  const handleEdit = useCallback(
    (record: System) => {
      setEditingSystem(record);
      editForm.setFieldsValue({
        name: record.name,
        component: record.component || undefined,
        nameCn: record.nameCn,
        owner: record.owner,
        description: record.description,
      });
      setEditModalOpen(true);
    },
    [editForm],
  );

  const handleEditSubmit = useCallback(async () => {
    if (!editingSystem) return;
    try {
      const values = await editForm.validateFields();
      setEditSubmitting(true);
      await updateSystem(editingSystem.id, {
        ...values,
        component: values.component?.trim() || '',
      });
      message.success('系统信息已更新');
      setEditModalOpen(false);
      setEditingSystem(null);
      list.fetch();
    } catch (err) {
      if (err && typeof err === 'object' && 'errorFields' in err) return;
      console.error(err);
    } finally {
      setEditSubmitting(false);
    }
  }, [editForm, editingSystem, list]);

  // ===== 启停切换（已删除：系统级状态机下线）=====

  // ===== 删除系统 =====
  const handleDeleteSystem = useCallback(
    async (record: System) => {
      try {
        await deleteSystem(record.id);
        message.success(`系统【${record.name}】已删除（含其下代码库）`);
        list.fetch();
      } catch (err) {
        console.error(err);
      }
    },
    [list],
  );

  // ===== Drawer（开/关 + 选中系统）=====
  const [selectedSystem, setSelectedSystem] = useState<System | null>(null);
  const [selectedRepo, setSelectedRepo] = useState<Repository | null>(null);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const repoHook = useRepositories(drawerOpen ? selectedSystem?.id ?? null : null);

  const openDetailDrawer = useCallback((system: System) => {
    setSelectedSystem(system);
    setDrawerOpen(true);
  }, []);

  // ===== 提示词聚焦编辑弹窗 =====
  const [promptBindOpen, setPromptBindOpen] = useState(false);
  const openPromptBind = useCallback((system: System) => {
    setSelectedSystem(system);
    setPromptBindOpen(true);
  }, []);

  // ===== 业务知识配置弹窗 =====
  const [bizOpen, setBizOpen] = useState(false);
  const [bizSystem, setBizSystem] = useState<System | null>(null);
  const openBusinessKnowledge = useCallback((system: System) => {
    setBizSystem(system);
    setBizOpen(true);
  }, []);
  const closeBusinessKnowledge = useCallback(() => {
    setBizOpen(false);
    setBizSystem(null);
  }, []);

  // 从任务下发等页面深链打开提示词绑定弹窗：
  //   /systems?systemId=1&repositoryId=2&action=prompts  → 直接打开指定仓库的提示词弹窗
  //   /systems?systemId=1&action=prompts                  → 兼容旧链接：只打开系统详情抽屉
  useEffect(() => {
    const action = searchParams.get('action');
    const sysId = Number(searchParams.get('systemId'));
    const repoId = Number(searchParams.get('repositoryId'));
    if (action !== 'prompts' || !Number.isFinite(sysId) || sysId <= 0 || systems.length === 0) {
      return;
    }
    const system = systems.find((s) => s.id === sysId);
    if (!system) return;
    const clearParams = () => {
      const next = new URLSearchParams(searchParams);
      next.delete('systemId');
      next.delete('repositoryId');
      next.delete('action');
      setSearchParams(next, { replace: true });
    };
    if (Number.isFinite(repoId) && repoId > 0) {
      // 有 repositoryId:先取仓库,再打开绑定弹窗(带上正确的 repository 上下文)
      setSelectedSystem(system);
      getRepository(repoId)
        .then((repo) => {
          setSelectedRepo(repo);
          setPromptBindOpen(true);
          clearParams();
        })
        .catch(() => {
          // 取仓库失败:回退到抽屉
          openDetailDrawer(system);
          clearParams();
        });
    } else {
      // 兼容旧链接:没传 repositoryId,只打开抽屉让用户自选
      openDetailDrawer(system);
      clearParams();
    }
  }, [openDetailDrawer, searchParams, setSearchParams, systems]);

  // ===== 仓库删除 =====
  const handleDeleteRepository = useCallback(
    async (repo: Repository) => {
      try {
        await deleteRepository(repo.id);
        message.success('代码库已删除');
        if (selectedSystem?.id) {
          repoHook.refresh(selectedSystem.id);
        }
        list.fetch();
      } catch (err) {
        console.error(err);
      }
    },
    [list, repoHook, selectedSystem],
  );

  const handleScan = useCallback(
    (repo: Repository) => {
      navigate(`/tasks/dispatch?systemId=${repo.systemId}&repositoryId=${repo.id}`);
    },
    [navigate],
  );

  // ===== 代码库添加 / 编辑 =====
  const [repoModalOpen, setRepoModalOpen] = useState(false);
  const [editingRepo, setEditingRepo] = useState<Repository | null>(null);
  const [repoTesting, setRepoTesting] = useState(false);
  const [repoSubmitting, setRepoSubmitting] = useState(false);
  const [repoForm] = Form.useForm<RepositoryFormValues>();

  const [scanModalOpen, setScanModalOpen] = useState(false);
  const [scanConfigRepo, setScanConfigRepo] = useState<Repository | null>(null);
  const [scanSubmitting, setScanSubmitting] = useState(false);
  const [scanForm] = Form.useForm<ScanConfigFormValues>();

  const openScanConfig = useCallback(
    (repo: Repository) => {
      setScanConfigRepo(repo);
      scanForm.setFieldsValue({ entryScanConfig: parseRepoEntryScanConfig(repo) });
      setScanModalOpen(true);
    },
    [scanForm],
  );

  const openAddRepo = useCallback(() => {
    if (!selectedSystem) return;
    setEditingRepo(null);
    repoForm.resetFields();
    repoForm.setFieldsValue({ branch: 'main', scanRoot: '/' });
    setRepoModalOpen(true);
  }, [repoForm, selectedSystem]);

  const openEditRepo = useCallback(
    (repo: Repository) => {
      setEditingRepo(repo);
      repoForm.setFieldsValue({
        gitUrl: repo.gitUrl,
        branch: repo.branch,
        scanRoot: repo.scanRoot,
        username: repo.username,
        password: repo.password,
        excludeDirs: repo.excludeDirs,
        excludeFileTypes: repo.excludeFileTypes,
      });
      setRepoModalOpen(true);
    },
    [repoForm],
  );

  const handleRepoSubmit = useCallback(async () => {
    if (!selectedSystem) return;
    try {
      const values = await repoForm.validateFields();
      setRepoSubmitting(true);
      if (editingRepo) {
        await updateRepository(editingRepo.id, { id: editingRepo.id, ...values });
        message.success('代码库已更新');
        setRepoModalOpen(false);
        setEditingRepo(null);
      } else {
        const repo = await createRepository({ systemId: selectedSystem.id, ...values });
        message.success('代码库已添加，请配置入口扫描规则');
        setRepoModalOpen(false);
        setEditingRepo(null);
        openScanConfig(repo);
      }
      repoHook.refresh(selectedSystem.id);
      list.fetch();
    } catch (err) {
      if (err && typeof err === 'object' && 'errorFields' in err) return;
      console.error(err);
    } finally {
      setRepoSubmitting(false);
    }
  }, [editingRepo, list, openScanConfig, repoForm, repoHook, selectedSystem]);

  const handleRepoTest = useCallback(async () => {
    try {
      const values = await repoForm.validateFields(['gitUrl', 'branch', 'username', 'password']);
      setRepoTesting(true);
      const payload = editingRepo ? { id: editingRepo.id, ...values } : values;
      const ok = await testRepositoryConnection(payload);
      if (ok) message.success('Git 连接测试成功');
      else message.error('Git 连接测试失败');
    } catch (err) {
      if (err && typeof err === 'object' && 'errorFields' in err) return;
      console.error(err);
    } finally {
      setRepoTesting(false);
    }
  }, [editingRepo, repoForm]);

  // ===== 代码库入口扫描规则 =====
  const [scanWindowOpen, setScanWindowOpen] = useState(false);
  const [scanWindowRepo, setScanWindowRepo] = useState<Repository | null>(null);

  const handleScanConfigSubmit = useCallback(async () => {
    if (!scanConfigRepo || !selectedSystem) return;
    try {
      const values = await scanForm.validateFields();
      setScanSubmitting(true);
      await updateRepository(scanConfigRepo.id, {
        id: scanConfigRepo.id,
        entryScanConfig: values.entryScanConfig
          ? JSON.stringify(values.entryScanConfig)
          : null,
      } as Partial<Repository>);
      message.success('入口扫描规则已保存');
      setScanModalOpen(false);
      setScanConfigRepo(null);
      repoHook.refresh(selectedSystem.id);
      list.fetch();
    } catch (err) {
      if (err && typeof err === 'object' && 'errorFields' in err) return;
      console.error(err);
    } finally {
      setScanSubmitting(false);
    }
  }, [list, repoHook, scanConfigRepo, scanForm, selectedSystem]);

  // ===== 列配置 =====
  const systemColumns = getSystemColumns({
    onEdit: handleEdit,
    onEditPrompts: openPromptBind,
    onEditBusinessKnowledge: openBusinessKnowledge,
    onOpenDetail: openDetailDrawer,
    onDelete: handleDeleteSystem,
  });

  return (
    <div className="ci-page ci-systems-page">
      <Card className="ci-filter-card">
        <SystemFilterBar
          searchName={list.searchName}
          searchComponent={list.searchComponent}
          searchOwner={list.searchOwner}
          onSearchNameChange={list.setSearchName}
          onSearchComponentChange={list.setSearchComponent}
          onSearchOwnerChange={list.setSearchOwner}
          onSearch={list.handleSearch}
          onReset={list.handleReset}
          onAdd={openWizard}
        />
      </Card>

      <Card
        className="ci-systems-table-card"
        title={
          <Space>
            <span>系统列表</span>
          </Space>
        }
      >
        <Table<System>
          dataSource={systems}
          columns={systemColumns}
          rowKey="id"
          loading={loading}
          scroll={{ x: 1500 }}
          pagination={{
            current,
            pageSize: size,
            total,
            showSizeChanger: true,
            onChange: (page, pageSize) => {
              setCurrent(page);
              setSize(pageSize);
            },
          }}
        />
      </Card>

      <SystemWizardModal
        open={wizardOpen}
        onClose={() => setWizardOpen(false)}
        onCompleted={() => {
          setWizardOpen(false);
          list.fetch();
        }}
        onPartialSave={() => list.fetch()}
      />

      <SystemFormModal
        open={editModalOpen}
        form={editForm}
        submitting={editSubmitting}
        onCancel={() => {
          setEditModalOpen(false);
          setEditingSystem(null);
        }}
        onSubmit={handleEditSubmit}
      />

      <RepositoryDrawer
        open={drawerOpen}
        system={selectedSystem}
        repositories={repoHook.repositories}
        loading={repoHook.loading}
        onClose={() => setDrawerOpen(false)}
        onAddRepo={openAddRepo}
        onEditRepo={openEditRepo}
        onBindPrompts={(repo) => { setSelectedRepo(repo); setPromptBindOpen(true); }}
        onDeleteRepo={handleDeleteRepository}
        onScan={handleScan}
        onScanConfig={openScanConfig}
        onScanWindow={(repo) => { setScanWindowRepo(repo); setScanWindowOpen(true); }}
      />

      <SystemPromptBindModal
        open={promptBindOpen}
        repository={selectedRepo}
        onClose={() => {
          setPromptBindOpen(false);
          setSelectedRepo(null);
        }}
        onSaved={async () => {
          const systemId = selectedSystem?.id;
          const repoId = selectedRepo?.id;
          await list.fetch();
          if (systemId) {
            await repoHook.refresh(systemId);
            try {
              const updatedSystem = await getSystem(systemId);
              setSelectedSystem(updatedSystem);
            } catch {
              /* ignore */
            }
          }
          if (repoId) {
            try {
              const updated = await getRepository(repoId);
              setSelectedRepo(updated);
            } catch {
              /* ignore */
            }
          }
        }}
      />

      <RepositoryFormModal
        open={repoModalOpen}
        editing={!!editingRepo}
        testing={repoTesting}
        submitting={repoSubmitting}
        form={repoForm}
        onCancel={() => {
          setRepoModalOpen(false);
          setEditingRepo(null);
        }}
        onSubmit={handleRepoSubmit}
        onTest={handleRepoTest}
      />

      <RepositoryScanConfigModal
        open={scanModalOpen}
        form={scanForm}
        repoId={scanConfigRepo?.id}
        systemId={selectedSystem?.id}
        submitting={scanSubmitting}
        onCancel={() => {
          setScanModalOpen(false);
          setScanConfigRepo(null);
        }}
        onSubmit={handleScanConfigSubmit}
      />

      {scanWindowRepo && (
        <ScanWindowModal
          open={scanWindowOpen}
          repositoryId={scanWindowRepo.id}
          onClose={() => { setScanWindowOpen(false); setScanWindowRepo(null); }}
        />
      )}

      <SystemBusinessKnowledgeModal
        open={bizOpen}
        system={bizSystem}
        onClose={closeBusinessKnowledge}
      />
    </div>
  );
};

export default Systems;
