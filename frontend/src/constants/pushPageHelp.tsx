import React from 'react';

const listStyle: React.CSSProperties = { margin: 0, paddingLeft: 18 };

export const knowledgePushHelp = {
  title: '推送记录说明',
  content: (
    <ul style={listStyle}>
      <li>知识版本在任务确认后自动按 v1 / v2 / v3… 创建，并固定 NAS 推送到 releases。</li>
      <li>本页仅查看版本与推送历史，不再支持手动新建版本或发起推送。</li>
      <li>已发布版本可「回滚生效」，切换仓库当前知识浏览指针。</li>
    </ul>
  ),
};
