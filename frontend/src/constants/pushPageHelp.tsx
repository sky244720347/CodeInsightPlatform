import React from 'react';

const listStyle: React.CSSProperties = { margin: 0, paddingLeft: 18 };

export const knowledgePushHelp = {
  title: '知识发布说明',
  content: (
    <ul style={listStyle}>
      <li>发布成功后将知识文档写入 NAS（或 Git）。</li>
      <li>强制同步到仓库：扫描配置快照、提示词绑定、入口复核结果、模块层级复核结果。</li>
      <li>已发布版本支持「回滚到该版本」，切换生效配置与知识浏览版本指针。</li>
      <li>支持导出 ZIP、创建 MR，以及按系统 / 仓库筛选版本列表。</li>
    </ul>
  ),
};
