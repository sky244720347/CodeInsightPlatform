import React from 'react';
import { Typography } from 'antd';
import { CloseOutlined, HolderOutlined } from '@ant-design/icons';

const { Text } = Typography;

const listStyle: React.CSSProperties = { margin: 0, paddingLeft: 18 };

export const entrypointReviewHelp = {
  title: '扫描入口复核说明',
  content: (
    <ul style={listStyle}>
      <li>仅启用「知识入口复核」的任务会停在此状态等待人工确认。</li>
      <li>点击「开始复核」进入详情页，可查看入口类清单并确认或驳回。</li>
      <li>
        复核页是<strong>只读视图</strong>：展示识别到的入口类与关键方法；
        只能<strong>确认并继续</strong>或<strong>驳回任务</strong>，不能直接增删改入口。
      </li>
      <li>
        如发现入口清单与预期不一致（例如少了 Controller / 混入测试类），请回到
        「创建任务」或「代码库配置」调整 <Text code>entry_scan_config</Text>（include / exclude
        规则）后重新创建任务。
      </li>
      <li>
        「确认」后任务进入 AI_ANALYZING → 模块层级（按 requireHierarchyReview 决定是否再触发模块层级复核）；
        「驳回」后任务直接终止（CANCELLED），不会留下任何知识资产。
      </li>
    </ul>
  ),
};

export const hierarchyReviewHelp = {
  title: '知识模块复核说明',
  content: (
    <ul style={listStyle}>
      <li>仅启用「模块层级调试」的任务会停在此状态等待人工确认。</li>
      <li>点击「开始调试」进入抽屉，可对模块/子模块/功能树进行增删改；功能节点的类路径随其它字段一起落表 ci_module_hierarchy，重启不丢失。</li>
      <li>「类路径」仅在调用 AI 时被剥离，不会出现在 analyze / module_doc 提示词中。</li>
      <li>提交后将进入 GENERATING_DOC → PENDING_REVIEW，无法再返回调试状态。</li>
    </ul>
  ),
};

/** 扫描入口复核 · 详情页操作指引（与列表页 entrypointReviewHelp 互补，不共用） */
export const entrypointReviewDetailHelp = {
  title: '入口复核说明',
  content: (
    <ul style={listStyle}>
      <li>
        可点击类/方法旁的 <CloseOutlined /> 临时排除；确认继续时写入任务级指定排除列表。
      </li>
      <li>确认后任务进入 AI 分析；驳回则任务终止（CANCELLED）。</li>
      <li>
        若规则本身有误，请调整 <Text code>entry_scan_config</Text> 后重新创建任务。
      </li>
    </ul>
  ),
};

/** 知识模块复核 · 详情页操作指引（与列表页 hierarchyReviewHelp 互补，不共用） */
export const hierarchyReviewDetailHelp = {
  title: '模块层级调试说明',
  content: (
    <ul style={listStyle}>
      <li>
        树状展示模块 → 子模块 → 功能；默认仅显示模块，点击箭头展开查看子级。
      </li>
      <li>
        拖拽 <HolderOutlined /> 手柄可移动子模块（放入另一模块）或功能（放入另一子模块），同层间隙放置可排序。
      </li>
      <li>
        每个节点右侧带「已确认/未确认」复选框，用于逐项标记人工复核进度；JSON 中以 <Text code>"Y"</Text> /{' '}
        <Text code>"N"</Text> 呈现。也可以切换到 <b>JSON 编辑</b> tab 直接基于 JSON 文本快速批量修改。
      </li>
      <li>
        功能节点的「类路径」与其它字段一起整体落表 <Text code>ci_module_hierarchy</Text>
        （FUNCTION 行 class_paths 列），服务重启不会丢失。
      </li>
      <li>
        「类路径」与「已确认」仅在调用 AI 时被剥离，不会出现在 analyze / module_doc 提示词中。
      </li>
      <li>
        点击「保存并继续」将落表 ModuleHierarchy 并推进流水线至 GENERATING_DOC。
      </li>
    </ul>
  ),
};

export const draftReviewHelp = {
  title: '生成知识复核说明',
  content: (
    <ul style={listStyle}>
      <li>任务进入「待复核」或「复核中」后，可在此列表点击「开始复核」进入工作区编辑 Markdown 草稿。</li>
      <li>复核人可对单篇草稿保存修订；「确认通过」将把整组草稿置为已确认并推进任务状态。</li>
      <li>已确认任务仍可继续编辑草稿；推送锁定后（PUSHING / PUSHED）将变为只读。</li>
    </ul>
  ),
};
