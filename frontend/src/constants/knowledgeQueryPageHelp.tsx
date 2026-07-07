import React from 'react';
import { Typography } from 'antd';
import { CloseOutlined, HolderOutlined } from '@ant-design/icons';

const { Text } = Typography;

const listStyle: React.CSSProperties = { margin: 0, paddingLeft: 18 };

export const knowledgeEntrypointsHelp = {
  title: '扫描入口说明',
  content: (
    <ul style={listStyle}>
      <li>查看当前生效发布版的仓库级入口清单（只读）；需先选择系统与仓库。</li>
      <li>点击「调整并重跑」进入编辑模式，可排除入口类/方法；确认后将创建纠错任务。</li>
      <li>排除入口后将从模块层级 AI 阶段重跑（全量重算层级与后续文档）。</li>
      <li>若规则本身有误，请回到「代码库配置」调整 <code>entry_scan_config</code> 后重新发布任务。</li>
    </ul>
  ),
};

export const knowledgeHierarchyHelp = {
  title: '模块层级说明',
  content: (
    <ul style={listStyle}>
      <li>查看当前生效发布版的模块 / 子模块 / 功能树（只读）；需先选择系统与仓库。</li>
      <li>点击「调整并重跑」可编辑层级结构，提交后创建纠错任务。</li>
      <li>调整层级后，可指定模块范围从文档生成阶段重跑，未选模块保留原发布版文档。</li>
      <li>功能节点的类路径仅用于关联代码，不会进入 AI 提示词正文。</li>
    </ul>
  ),
};

export const knowledgeDocumentsHelp = {
  title: '知识文档说明',
  content: (
    <ul style={listStyle}>
      <li>浏览当前生效发布版的 Markdown 文档、索引与清单文件。</li>
      <li>树形视图按模块层级组织；列表视图展示 INDEX / MANIFEST 等发布版文件（不含任务草稿）。</li>
      <li>可批量选择模块重跑文档生成；预览 Markdown 时可人工修订并直写 NAS（需审核通过）。</li>
      <li>树形模式需选择仓库；列表模式可按系统 / 仓库 / 类型筛选。</li>
    </ul>
  ),
};

/** 知识查看 · 扫描入口 · 纠错编辑模式操作指引（与 knowledgeEntrypointsHelp 互补，不共用） */
export const knowledgeEntrypointsRemediationHelp = {
  title: '入口纠错说明',
  content: (
    <ul style={listStyle}>
      <li>
        点击类/方法旁的 <CloseOutlined /> 将其临时排除（可排除整类或单个方法）。
      </li>
      <li>已排除项以 Tag 展示在列表上方，点击 Tag 上的 × 可撤销。</li>
      <li>「确认并重跑」将弹出二次确认；确认后创建纠错任务并跳转任务详情。</li>
      <li>
        纠错任务克隆当前生效任务的工作区与 AST 产物，应用入口排除后从模块层级 AI 阶段全量重算层级与后续文档（跳过拉取/扫描）。
      </li>
      <li>
        若规则本身有误，请回到「代码库配置」调整 <Text code>entry_scan_config</Text> 后重新发布任务。
      </li>
    </ul>
  ),
};

/** 知识查看 · 模块层级 · 纠错抽屉操作指引（与 knowledgeHierarchyHelp 互补，不共用） */
export const knowledgeHierarchyRemediationHelp = {
  title: '模块层级纠错说明',
  content: (
    <ul style={listStyle}>
      <li>
        在树形/JSON 双视图调整层级结构，并选择需要重生成文档的模块（默认仅 scope 内模块调 AI）。
      </li>
      <li>树状展示模块 → 子模块 → 功能；默认仅显示模块，点击箭头展开查看子级。</li>
      <li>
        「树形编辑」支持节点改名、新增/删除、拖拽 <HolderOutlined /> 手柄排序/跨级移动，逐项编辑类路径与方法签名。
      </li>
      <li>「JSON 编辑」适合大批量文本替换；两侧通过「应用」按钮双向同步。</li>
      <li>
        每个节点右侧「已确认/未确认」复选框用于标记复核进度；JSON 中以 <Text code>"Y"</Text> /{' '}
        <Text code>"N"</Text> 呈现。
      </li>
      <li>
        功能节点的「类路径」与其它字段用于层级关联；调用 AI 时类路径与「已确认」不会进入 analyze / module_doc 提示词正文。
      </li>
      <li>「重跑文档的模块范围」决定哪些模块走 AI 重跑；其他模块保留原发布版文档。</li>
      <li>「确认并重跑」保存层级修改并创建纠错任务，从文档生成阶段重跑所选模块。</li>
    </ul>
  ),
};

/** 知识查看 · 知识文档 · 模块文档重跑操作指引（与 knowledgeDocumentsHelp 互补，不共用） */
export const knowledgeDocumentsRemediationHelp = {
  title: '文档重跑说明',
  content: (
    <ul style={listStyle}>
      <li>在树形视图 + 已选仓库 + 存在生效发布版时，点击「调整并重跑」打开本对话框。</li>
      <li>选择需要重生成文档的模块（至少 1 个 moduleId）。</li>
      <li>确认后将创建纠错任务，仅对选中模块从文档生成阶段重跑 AI；未选中模块保留当前生效发布版文档。</li>
    </ul>
  ),
};

/** 知识查看 · 知识文档 · 发布版人工修订操作指引 */
export const knowledgeDocumentsReleaseEditHelp = {
  title: '人工修订说明',
  content: (
    <ul style={listStyle}>
      <li>人工修订将直写当前生效发布版 NAS 文件，不创建新版本或推送任务。</li>
      <li>「提交待审」写入待批准记录；「批准并写入」经审核后直接覆盖 NAS 正文。</li>
      <li>适用于小范围纠错；大范围变更建议走「调整并重跑」创建纠错任务。</li>
    </ul>
  ),
};
