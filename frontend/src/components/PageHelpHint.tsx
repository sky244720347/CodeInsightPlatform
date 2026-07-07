import React from 'react';
import { Popover, theme } from 'antd';
import { ExclamationCircleOutlined } from '@ant-design/icons';

export interface PageHelpHintProps {
  /** Popover 标题（可选） */
  title?: string;
  /** 说明正文 */
  content: React.ReactNode;
}

/**
 * 页面说明提示：标题旁感叹号图标，悬停/点击展示说明，不占固定版面。
 */
const PageHelpHint: React.FC<PageHelpHintProps> = ({ title, content }) => {
  const { token } = theme.useToken();

  return (
    <Popover
      trigger={['hover', 'click']}
      placement="bottomLeft"
      overlayStyle={{ maxWidth: 480 }}
      title={title}
      content={content}
    >
      <ExclamationCircleOutlined
        aria-label="页面说明"
        style={{
          color: token.colorInfo,
          cursor: 'help',
          fontSize: 14,
        }}
        onClick={(e) => e.stopPropagation()}
      />
    </Popover>
  );
};

export default PageHelpHint;
