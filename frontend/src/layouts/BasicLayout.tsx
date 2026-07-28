import React, { useEffect, useMemo, useRef, useState } from 'react';
import { Avatar, Badge, Button, Dropdown, Layout, Menu, Space, Tag, Tooltip, Typography } from 'antd';
import { Outlet, useLocation, useNavigate } from 'react-router-dom';
import TabLink from '../components/TabLink';
import {
  ApartmentOutlined,
  AuditOutlined,
  BarChartOutlined,
  BellOutlined,
  BranchesOutlined,
  CloudUploadOutlined,
  CodeOutlined,
  DashboardOutlined,
  EditOutlined,
  FileSearchOutlined,
  FileTextOutlined,
  HistoryOutlined,
  HourglassOutlined,
  KeyOutlined,
  LogoutOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  PlayCircleOutlined,
  SettingOutlined,
  SwapOutlined,
  ThunderboltOutlined,
  UnorderedListOutlined,
  UserOutlined,
} from '@ant-design/icons';
import { useAuthStore } from '../stores/auth';

const { Header, Content, Sider } = Layout;
const { Text } = Typography;

interface NavItem {
  key: string;
  icon: React.ReactNode;
  label: React.ReactNode;
  title: string;
  description: string;
  /** 子菜单：用于把『手动下发 / 定时任务』挂在『知识构建任务』下 */
  children?: NavItem[];
}

/**
 * 侧边栏导航数据源配置（按三大类归档）
 *
 * - 仪表盘 / 看板大类 {@link dashboardNav}：运营层视图与全局统计
 *   任务概览 / AI 模型用量 / 流水线分析 / 系统覆盖报表 / Token 审计 / 操作日志
 *
 * - 知识构建大类 {@link knowledgeNav}：端到端知识生产流水线
 *   系统与仓库 → 知识构建任务 → 入口复核 / 模块层级复核 → 知识查看 / 复核 → 知识推送
 *
 * - 基础配置大类 {@link basicNav}：后台管理类页面（模型、提示词、权限、流量）
 *
 * 支持父子结构：父菜单本身可点击进入默认页，下方子菜单用于聚合相关子页面。
 */

/**
 * 仪表盘 / 看板 大类：运营层视图与全局统计
 */
const dashboardNav: NavItem[] = [
  {
    key: '/dashboard/tasks',
    icon: <DashboardOutlined />,
    label: <TabLink to="/dashboard/tasks">任务概览</TabLink>,
    title: '任务概览',
    description: '按状态 / 类型 / 系统多维分析知识构建任务：分布饼图、时间趋势、成功率与平均耗时。',
  },
  {
    key: '/dashboard/ai-usage',
    icon: <BarChartOutlined />,
    label: <TabLink to="/dashboard/ai-usage">AI 模型用量</TabLink>,
    title: 'AI 模型用量',
    description: '按模型 / 阶段分组的 Token 用量、调用次数、成本排行、成功率。',
  },
  {
    key: '/dashboard/pipeline',
    icon: <BranchesOutlined />,
    label: <TabLink to="/dashboard/pipeline">流水线分析</TabLink>,
    title: '流水线分析',
    description: '各阶段（PULLING / PARSING / SPLITTING / AI_ANALYZING ...）的平均耗时与失败率，定位瓶颈。',
  },
  {
    key: '/dashboard/coverage',
    icon: <ApartmentOutlined />,
    label: <TabLink to="/dashboard/coverage">系统覆盖报表</TabLink>,
    title: '系统覆盖报表',
    description: '所有系统的最近反编译时间、任务 / 草稿 / 推送版本数量与覆盖情况。',
  },
  {
    key: '/audit',
    icon: <ThunderboltOutlined />,
    label: <TabLink to="/audit">Token 审计</TabLink>,
    title: 'Token 审计',
    description: '原始 Token 消耗与计费记录查询（明细级）。',
  },
  {
    key: '/logs',
    icon: <HistoryOutlined />,
    label: <TabLink to="/logs">操作日志</TabLink>,
    title: '操作日志',
    description: '追踪系统、仓库、任务、草稿和推送操作。',
  },
];

/**
 * 知识管理 大类：端到端知识生产流水线（生产侧 / 消费侧）
 *
 * 1 级：知识管理
 *   2.1 系统与仓库（独立）
 *   2.2 知识任务构建
 *       2.2.1 扫描入口复核（原入口复核）
 *       2.2.2 知识模块复核（原模块层级复核）
 *       2.2.3 生成知识复核（原知识复核）
 *       2.2.4 知识推送
 *   2.3 知识查询
 */
const knowledgeNav: NavItem[] = [
  {
    key: '/systems',
    icon: <ApartmentOutlined />,
    label: <TabLink to="/systems">系统与仓库</TabLink>,
    title: '系统与代码库管理',
    description: '维护业务系统、负责人、Git 仓库、扫描范围和排除规则。',
  },
  {
    key: '/tasks',
    icon: <PlayCircleOutlined />,
    label: '知识任务构建',
    title: '知识任务构建',
    description: '任务查询 / 任务队列 / JOB配置 / 手动下发及复核断点。',
    children: [
      {
        key: '/tasks/query',
        icon: <UnorderedListOutlined />,
        label: <TabLink to="/tasks/query">任务列表</TabLink>,
        title: '任务列表',
        description: '查询全部知识构建任务，按状态、类型、系统等条件过滤。',
      },
      {
        key: '/tasks/entrypoint-review',
        icon: <ApartmentOutlined />,
        label: <TabLink to="/tasks/entrypoint-review">扫描入口复核</TabLink>,
        title: '扫描入口复核',
        description: '集中处理处于入口复核断点的任务。',
      },
      {
        key: '/tasks/hierarchy-review',
        icon: <SwapOutlined />,
        label: <TabLink to="/tasks/hierarchy-review">知识模块复核</TabLink>,
        title: '知识模块复核',
        description: '集中处理处于模块层级调试断点的任务。',
      },
      {
        key: '/drafts',
        icon: <EditOutlined />,
        label: <TabLink to="/drafts">生成知识复核</TabLink>,
        title: '生成知识复核',
        description: '复核 AI 生成的 Markdown 草稿。',
      },
      {
        key: '/push',
        icon: <CloudUploadOutlined />,
        label: <TabLink to="/push">推送记录</TabLink>,
        title: '推送记录',
        description: '查看知识版本与 NAS 推送历史（自动发布，不可手动新建）。',
      },
    ],
  },
  {
    key: 'knowledge-query',
    icon: <FileSearchOutlined />,
    label: '知识查询',
    title: '知识查询',
    description: '按仓库浏览已发布的扫描入口、模块层级与知识文档。',
    children: [
      {
        key: '/knowledge/entrypoints',
        icon: <ApartmentOutlined />,
        label: <TabLink to="/knowledge/entrypoints">扫描入口</TabLink>,
        title: '扫描入口',
        description: '查看当前生效发布版的仓库级入口清单（只读）。',
      },
      {
        key: '/knowledge/hierarchy',
        icon: <SwapOutlined />,
        label: <TabLink to="/knowledge/hierarchy">模块层级</TabLink>,
        title: '模块层级',
        description: '查看当前生效发布版的模块层级树（只读）。',
      },
      {
        key: '/knowledge/documents',
        icon: <FileTextOutlined />,
        label: <TabLink to="/knowledge/documents">知识文档</TabLink>,
        title: '知识文档',
        description: '浏览当前生效发布版的 Markdown 文档、索引与清单文件。',
      },
    ],
  },
];

/**
 * 基础配置 大类：聚合后台管理类页面（模型、提示词、权限、流量）。
 */
const basicNav: NavItem[] = [
  {
    key: '/basic/models',
    icon: <SettingOutlined />,
    label: <TabLink to="/basic/models">模型配置</TabLink>,
    title: '模型配置管理',
    description: '配置 AI 大模型、调用 ID、API 密钥、接口地址及模型排序与默认项。',
  },
  {
    key: '/basic/prompts',
    icon: <FileTextOutlined />,
    label: <TabLink to="/basic/prompts">提示词</TabLink>,
    title: '提示词配置',
    description: 'AI 归纳提示词统一维护：草稿编辑 / 试跑 / 发布 / 设为默认；每种类型支持多个发布版本，仅 1 条当前生效。',
  },
  {
    key: '/basic/permissions',
    icon: <KeyOutlined />,
    label: <TabLink to="/basic/permissions">权限管理</TabLink>,
    title: '权限管理',
    description: '当前账号信息与角色（后续接入 UM/SSO 后扩展为完整 RBAC）。',
  },
  {
    key: 'task-control',
    icon: <ThunderboltOutlined />,
    label: '任务管控',
    title: '任务管控',
    description: '流量配置 / 任务编排 / 队列查看。',
    children: [
      {
        key: '/basic/quota',
        icon: <ThunderboltOutlined />,
        label: <TabLink to="/basic/quota">流量配置</TabLink>,
        title: '流量配置',
        description: '全局限流配置、用户级 Token 额度、AI 调用并发控制。',
      },
      {
        key: '/basic/orchestration',
        icon: <BranchesOutlined />,
        label: <TabLink to="/basic/orchestration">任务编排</TabLink>,
        title: '任务编排',
        description: '查看所有时间窗口配置的仓库，分析调度密度与历史执行情况。',
      },
      {
        key: '/tasks/queue',
        icon: <HourglassOutlined />,
        label: <TabLink to="/tasks/queue">队列查看</TabLink>,
        title: '队列查看',
        description: '查看排队中的 PENDING 任务，调整优先级或取消排队。',
      },
    ],
  },
];

/**
 * 把 NavItem 树拍平为 AntD Menu items 需要的结构。
 * 子项 key 在父项 key 之下，selectedKey 取最深匹配。
 */
const buildMenuItems = (items: NavItem[]): { menuItems: any[]; flatMap: Map<string, NavItem> } => {
  const flatMap = new Map<string, NavItem>();
  const menuItems = items.map((item) => {
    flatMap.set(item.key, item);
    const mi: any = { key: item.key, icon: item.icon, label: item.label };
    if (item.children && item.children.length > 0) {
      mi.children = item.children.map((c) => {
        flatMap.set(c.key, c);
        return { key: c.key, icon: c.icon, label: c.label };
      });
    }
    return mi;
  });
  return { menuItems, flatMap };
};

// 三大类导航 → 拍平为 AntD Menu items + 统一 flatMap（key → NavItem）

// 合并 flatMap：currentPage 查找可命中四大类任意 key
const navFlatMap = new Map<string, NavItem>([
  ...Array.from(buildMenuItems(basicNav).flatMap),
  ...Array.from(buildMenuItems(knowledgeNav).flatMap),
  ...Array.from(buildMenuItems(dashboardNav).flatMap),
]);

// 兜底 currentPage：取基础配置第一项（保证页面顶部 kicker / title 一定有值）
const fallbackCurrentPage = basicNav[0];

/**
 * 把 NAV_GROUPS 渲染为统一的可折叠 SubMenu（一级分组）。
 * 带 children 的 NavItem（知识任务构建）方案二：5 个 Menu.Item 平铺，子项仅加文字前引导线。
 */


/** 三大类导航分组的展示名（顺序与侧边栏渲染顺序一致） */
const NAV_GROUPS: ReadonlyArray<{ key: string; label: string; items: NavItem[] }> = [
  { key: "basic", label: "基础配置", items: basicNav },
  { key: "knowledge", label: "知识管理", items: knowledgeNav },
  { key: "dashboard", label: "仪表盘 / 看板", items: dashboardNav },
];

/** 在四大类中查找指定 key 所属的分组（含子项）；找不到返回 null。 */
function findGroupForKey(key: string): { group: typeof NAV_GROUPS[number]; parent: NavItem | null } | null {
  for (const group of NAV_GROUPS) {
    for (const item of group.items) {
      if (item.key === key) return { group, parent: null };
      if (item.children?.some((c) => c.key === key)) return { group, parent: item };
    }
  }
  return null;
}

/**
 * 计算 selectedKey 与 openKeys：
 * - selectedKey 取最深匹配的菜单 key（子菜单优先于父菜单）
 * - selectedKeys 仅含 selectedKey（平铺子项时不连带高亮父项）
 * - openKeys 取一级分组 SubMenu 的祖先 key
 */
const computeMenuState = (pathname: string) => {
  let selectedKey: string | null = null;
  let bestDepth = -1;
  const openKeys = new Set<string>();

  const visit = (item: NavItem, ancestors: string[]) => {
    const matches =
      item.key === '/'
        ? pathname === '/'
        : pathname === item.key || pathname.startsWith(item.key + '/');
    if (matches) {
      const depth = item.key.split('/').length;
      if (depth > bestDepth) {
        bestDepth = depth;
        selectedKey = item.key;
      }
      ancestors.forEach((a) => openKeys.add(a));
    }
    if (item.children) {
      for (const child of item.children) {
        visit(child, [...ancestors, item.key]);
      }
    }
  };

  for (const item of basicNav) {
    visit(item, []);
  }
  for (const item of knowledgeNav) {
    visit(item, []);
  }
  for (const item of dashboardNav) {
    visit(item, []);
  }

  return {
    selectedKey,
    // 仅高亮最深匹配项；平铺子项时不连带高亮父项（如 知识任务构建）
    selectedKeys: selectedKey ? [selectedKey] : [],
    openKeys: Array.from(openKeys),
  };
};

/**
 * 平台通用骨架布局组件 (BasicLayout)
 * 包含左侧侧边栏导航、顶部页头操作栏、实时动态变化的面包屑标题以及核心内容呈现区（Outlet 组件）。
 */
const BasicLayout: React.FC = () => {
  // 控制左侧侧边栏折叠/展开的状态
  const [collapsed, setCollapsed] = useState(false);
  // 控制移动端小屏幕自适应的状态
  const [isMobile, setIsMobile] = useState(false);

  const location = useLocation();
  const navigate = useNavigate();
  const session = useAuthStore((state) => state.session);
  const clearSession = useAuthStore((state) => state.clearSession);

  // 计算当前选中的菜单 key 与需要展开的父菜单 key
  const { selectedKey, selectedKeys, openKeys: initialOpenKeys } = useMemo(
    () => computeMenuState(location.pathname),
    [location.pathname],
  );
  // 用户也可手动展开/折叠父菜单
  // 行为约定：
  // - 初次进入页面：openKeys 初始化为命中路径的父菜单（initialOpenKeys）
  // - 路由变化且命中其他父菜单：自动把新父菜单追加到 openKeys（不删除用户已展开的）
  // - 用户手动操作过一次后：不再受路由变化影响（除非用户再次操作）
  const [openKeys, setOpenKeys] = useState<string[]>(initialOpenKeys);
  const userTouchedRef = useRef(false);
  useEffect(() => {
    if (userTouchedRef.current) return;
    setOpenKeys(initialOpenKeys);
  }, [initialOpenKeys]);

  const handleOpenChange = (keys: string[]) => {
    userTouchedRef.current = true;
    setOpenKeys(keys);
  };

  // 实时估算当前页面配置数据以更新页头标题解释
  // 优先取子菜单（如 /tasks/jobs 命中"JOB配置"），否则回退到仪表盘第一项
  const currentPage = useMemo(
    () => (selectedKey ? navFlatMap.get(selectedKey) : null) ?? fallbackCurrentPage,
    [selectedKey ?? 'null'],
  );

  // 当前页面所属分组（用于标题区 eyebrow 标签展示）
  const currentPageGroup = useMemo(
    () => (currentPage ? findGroupForKey(currentPage.key)?.group ?? null : null),
    [currentPage],
  );

  const handleLogout = () => {
    clearSession();
    navigate('/login', { replace: true });
  };

  return (
    <Layout className="ci-shell">
      {/* 响应式侧边栏配置 */}
      <Sider
        width={208}
        collapsedWidth={isMobile ? 0 : 64}
        collapsed={collapsed}
        trigger={null}
        breakpoint="lg"
        onBreakpoint={(broken) => {
          setIsMobile(broken);
          setCollapsed(broken);
        }}
        className="ci-sider"
      >
        {/* 系统 LOGO 标识栏 */}
        <div className="ci-brand">
          <div className="ci-brand-mark">
            <CodeOutlined />
          </div>
          {!collapsed && (
            <div className="ci-brand-copy">
              <strong>代码洞察</strong>
              <span>代码知识平台</span>
            </div>
          )}
        </div>

        {/* 侧边栏菜单区:仅此区独立纵向滚动,brand + footer 保持固定不动 */}
        <div className="ci-sider-body">
          <Menu
            mode="inline"
            selectedKeys={selectedKeys}
            openKeys={openKeys}
            onOpenChange={handleOpenChange}
            onClick={() => {
              if (isMobile) setCollapsed(true);
            }}
            className="ci-menu"
          >
            {NAV_GROUPS.map((group) => (
              <Menu.SubMenu key={group.key} title={group.label} icon={null}>
                {group.items.map((item) =>
                  item.children && item.children.length > 0 ? (
                    <React.Fragment key={item.key}>
                      <Menu.Item key={item.key} icon={item.icon}>
                        {item.label}
                      </Menu.Item>
                      {item.children.map((c) => (
                        <Menu.Item key={c.key} className="ci-menu-item-nested">
                          {c.label}
                        </Menu.Item>
                      ))}
                    </React.Fragment>
                  ) : (
                    <Menu.Item key={item.key} icon={item.icon}>
                      {item.label}
                    </Menu.Item>
                  )
                )}
              </Menu.SubMenu>
            ))}
          </Menu>
        </div>{/* end ci-sider-body */}

        {/* 侧边栏底部:用户信息 + 登出 */}
        <div className="ci-sider-footer">
          <Dropdown
            trigger={['click']}
            menu={{
              items: [
                {
                  key: 'logout',
                  icon: <LogoutOutlined />,
                  label: '退出登录',
                  onClick: handleLogout,
                },
              ],
            }}
            placement="topRight"
          >
            <div
              className={`ci-sider-user ${collapsed ? 'ci-sider-user--collapsed' : ''}`}
              aria-label={`当前用户：${session?.displayName ?? '负责人'}，${session?.role ?? 'ADMIN'}`}
            >
              <Avatar
                size={collapsed ? 32 : 36}
                icon={<UserOutlined />}
                className="ci-sider-user-avatar"
              />
              {!collapsed && (
                <div className="ci-sider-user-copy">
                  <strong>{session?.displayName ?? '负责人'}</strong>
                  <span>{session?.role === 'ADMIN' ? '平台管理员' : '开发负责人'}</span>
                </div>
              )}
            </div>
          </Dropdown>
        </div>
      </Sider>

      {/* 移动端菜单展开时的背景遮罩 */}
      {isMobile && !collapsed && (
        <button
          type="button"
          className="ci-mobile-backdrop"
          aria-label="Close navigation"
          onClick={() => setCollapsed(true)}
        />
      )}
      
      {/* 右侧核心渲染主体区域 */}
      <Layout className="ci-main">
        {/* 顶部操作页头 */}
        <Header className="ci-header">
          {/* 折叠切换按钮 + 页面上下文(标题 + 描述) */}
          <div className="ci-header-context">
            <Tooltip title={collapsed ? '展开导航' : '收起导航'}>
              <Button
                type="text"
                icon={collapsed ? <MenuUnfoldOutlined /> : <MenuFoldOutlined />}
                onClick={() => setCollapsed((value) => !value)}
                className="ci-icon-button"
              />
            </Tooltip>
            <span className="ci-header-divider" aria-hidden="true" />
            <div className="ci-header-context-info">
              {currentPageGroup && (
                <Tag className="ci-header-context-eyebrow" color="blue">
                  {currentPageGroup.label}
                </Tag>
              )}
              <Text className="ci-header-context-title">{currentPage.title}</Text>
              {currentPage.description && (
                <Text type="secondary" className="ci-header-context-desc">
                  {currentPage.description}
                </Text>
              )}
            </div>
          </div>

          {/* 顶部右侧快捷标签 */}
          <Space size={16} className="ci-header-actions">
            <div className="ci-pipeline">
              <Tag icon={<BranchesOutlined />} color="blue">
                扫描
              </Tag>
              <Tag icon={<FileSearchOutlined />} color="cyan">
                AI 归纳
              </Tag>
              <Tag icon={<AuditOutlined />} color="green">
                复核
              </Tag>
            </div>
            <Tooltip title="待处理事项">
              <Badge count={3} size="small">
                <Button type="text" icon={<BellOutlined />} className="ci-icon-button" />
              </Badge>
            </Tooltip>
          </Space>
        </Header>

        {/* 页面正文内容渲染 */}
        <Content className="ci-content">
          <Outlet />
        </Content>
      </Layout>
    </Layout>
  );
};

export default BasicLayout;
