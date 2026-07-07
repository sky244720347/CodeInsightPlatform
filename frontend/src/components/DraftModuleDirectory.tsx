import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Button, Card, Space, Spin, Tooltip, Tree, Typography } from 'antd';
import type { DataNode } from 'antd/es/tree';
import {
  FolderOpenOutlined,
  NodeCollapseOutlined,
  NodeExpandOutlined,
  PushpinFilled,
  PushpinOutlined,
} from '@ant-design/icons';
import { collectExpandableKeys } from '../utils/treeExpandKeys';

const { Text } = Typography;

const DEFAULT_WIDTH = 296;
const DEFAULT_MIN_TOP = 120;
const MIN_WIDTH = 240;
const MIN_HEIGHT = 280;
const MAX_WIDTH = 560;
const MAX_HEIGHT = 900;

export interface DraftModuleDirectoryProps {
  visible: boolean;
  pinned: boolean;
  loading: boolean;
  leafCount: number;
  treeData: DataNode[];
  selectedDraftId: number | null;
  onSelectDraft: (draftId: number) => void;
  onPinnedChange: (pinned: boolean) => void;
  onResetDock: () => void;
  onMouseEnter?: () => void;
  onMouseLeave?: () => void;
  fullscreen?: boolean;
}

const DraftModuleDirectory: React.FC<DraftModuleDirectoryProps> = ({
  visible,
  pinned,
  loading,
  leafCount,
  treeData,
  selectedDraftId,
  onSelectDraft,
  onPinnedChange,
  onResetDock,
  onMouseEnter,
  onMouseLeave,
  fullscreen = false,
}) => {
  const panelRef = useRef<HTMLDivElement>(null);
  const treeScrollRef = useRef<HTMLDivElement>(null);
  const [treeViewportHeight, setTreeViewportHeight] = useState(320);
  const [size, setSize] = useState({ width: DEFAULT_WIDTH, height: fullscreen ? 520 : 480 });
  const [position, setPosition] = useState({ x: 0, y: DEFAULT_MIN_TOP });
  const [floating, setFloating] = useState(false);
  const dragRef = useRef<{ startX: number; startY: number; originX: number; originY: number } | null>(null);
  const resizeRef = useRef<{
    axis: 'width' | 'height' | 'both';
    startX: number;
    startY: number;
    originW: number;
    originH: number;
  } | null>(null);

  const allExpandableKeys = useMemo(() => collectExpandableKeys(treeData), [treeData]);
  const [expandedKeys, setExpandedKeys] = useState<string[]>(allExpandableKeys);

  useEffect(() => {
    setExpandedKeys(allExpandableKeys);
  }, [allExpandableKeys]);

  useEffect(() => {
    if (!pinned) {
      setFloating(false);
    }
  }, [pinned]);

  const measureTreeViewport = useCallback(() => {
    const panel = panelRef.current;
    if (!panel) return;
    const head = panel.querySelector<HTMLElement>('.ant-card-head');
    const body = panel.querySelector<HTMLElement>('.ant-card-body');
    if (!head || !body) return;
    const panelHeight = panel.getBoundingClientRect().height;
    const headHeight = head.getBoundingClientRect().height;
    const bodyStyle = window.getComputedStyle(body);
    const paddingY =
      parseFloat(bodyStyle.paddingTop || '0') + parseFloat(bodyStyle.paddingBottom || '0');
    setTreeViewportHeight(Math.max(120, panelHeight - headHeight - paddingY));
  }, []);

  useEffect(() => {
    if (!visible) return;
    const id = window.requestAnimationFrame(() => measureTreeViewport());
    return () => window.cancelAnimationFrame(id);
  }, [visible, measureTreeViewport, treeData.length, expandedKeys.length, loading]);

  useEffect(() => {
    if (!visible) return;
    measureTreeViewport();
    const panel = panelRef.current;
    if (!panel) return;
    const observer = new ResizeObserver(() => measureTreeViewport());
    observer.observe(panel);
    return () => observer.disconnect();
  }, [visible, measureTreeViewport, size.width, size.height, floating, pinned, fullscreen]);

  useEffect(() => {
    const el = treeScrollRef.current;
    if (!el || !visible) return;

    const onWheel = (event: WheelEvent) => {
      const { scrollTop, scrollHeight, clientHeight } = el;
      if (scrollHeight <= clientHeight + 1) return;

      const delta = event.deltaY;
      if (Math.abs(delta) < 0.5) return;

      const atTop = scrollTop <= 0;
      const atBottom = scrollTop + clientHeight >= scrollHeight - 1;
      if ((delta < 0 && atTop) || (delta > 0 && atBottom)) {
        return;
      }

      event.preventDefault();
      event.stopPropagation();
      el.scrollTop = Math.max(0, Math.min(scrollHeight - clientHeight, scrollTop + delta));
    };

    el.addEventListener('wheel', onWheel, { passive: false, capture: true });
    return () => el.removeEventListener('wheel', onWheel, { capture: true });
  }, [visible, treeViewportHeight, treeData.length]);

  const resetDockLayout = useCallback(() => {
    setFloating(false);
    setPosition({ x: 0, y: DEFAULT_MIN_TOP });
    setSize({ width: DEFAULT_WIDTH, height: fullscreen ? 520 : 480 });
  }, [fullscreen]);

  const handleTogglePin = () => {
    if (pinned) {
      resetDockLayout();
      onPinnedChange(false);
      onResetDock();
      return;
    }
    onPinnedChange(true);
  };

  const onHeaderMouseDown = useCallback(
    (event: React.MouseEvent) => {
      if (!pinned) return;
      if ((event.target as HTMLElement).closest('button')) return;
      event.preventDefault();
      const rect = panelRef.current?.getBoundingClientRect();
      const originX = floating ? position.x : (rect?.left ?? 0);
      const originY = floating ? position.y : (rect?.top ?? DEFAULT_MIN_TOP);
      if (!floating) {
        setFloating(true);
        setPosition({ x: originX, y: originY });
      }
      dragRef.current = {
        startX: event.clientX,
        startY: event.clientY,
        originX,
        originY,
      };

      const onMove = (ev: MouseEvent) => {
        if (!dragRef.current) return;
        const dx = ev.clientX - dragRef.current.startX;
        const dy = ev.clientY - dragRef.current.startY;
        setPosition({
          x: Math.max(8, dragRef.current.originX + dx),
          y: Math.max(64, dragRef.current.originY + dy),
        });
      };
      const onUp = () => {
        dragRef.current = null;
        window.removeEventListener('mousemove', onMove);
        window.removeEventListener('mouseup', onUp);
      };
      window.addEventListener('mousemove', onMove);
      window.addEventListener('mouseup', onUp);
    },
    [floating, pinned, position.x, position.y],
  );

  const onResizeMouseDown = useCallback(
    (event: React.MouseEvent, axis: 'width' | 'height' | 'both') => {
      event.preventDefault();
      event.stopPropagation();
      resizeRef.current = {
        axis,
        startX: event.clientX,
        startY: event.clientY,
        originW: size.width,
        originH: size.height,
      };

      const onMove = (ev: MouseEvent) => {
        if (!resizeRef.current) return;
        const dw = ev.clientX - resizeRef.current.startX;
        const dh = ev.clientY - resizeRef.current.startY;
        const next = { width: resizeRef.current.originW, height: resizeRef.current.originH };
        if (resizeRef.current.axis === 'width' || resizeRef.current.axis === 'both') {
          next.width = Math.min(MAX_WIDTH, Math.max(MIN_WIDTH, resizeRef.current.originW + dw));
        }
        if (resizeRef.current.axis === 'height' || resizeRef.current.axis === 'both') {
          next.height = Math.min(MAX_HEIGHT, Math.max(MIN_HEIGHT, resizeRef.current.originH + dh));
        }
        setSize(next);
      };
      const onUp = () => {
        resizeRef.current = null;
        window.removeEventListener('mousemove', onMove);
        window.removeEventListener('mouseup', onUp);
      };
      window.addEventListener('mousemove', onMove);
      window.addEventListener('mouseup', onUp);
    },
    [size.height, size.width],
  );

  const selectedKeys = useMemoSelectedKeys(treeData, selectedDraftId);

  const panelStyle: React.CSSProperties = floating
    ? {
        position: 'fixed',
        left: position.x,
        top: position.y,
        width: size.width,
        height: size.height,
        zIndex: fullscreen ? 1200 : 1000,
      }
    : pinned
      ? {
          width: size.width,
          height: size.height,
        }
      : {
          width: size.width,
        };

  return (
    <div
      ref={panelRef}
      className={[
        'ci-module-drawer',
        visible ? 'is-open' : '',
        pinned ? 'is-pinned' : '',
        floating ? 'is-floating' : '',
        fullscreen ? 'is-fullscreen' : '',
      ]
        .filter(Boolean)
        .join(' ')}
      style={panelStyle}
      onMouseEnter={onMouseEnter}
      onMouseLeave={onMouseLeave}
    >
      <Card
        size="small"
        className="ci-module-drawer-card"
        title={
          <div
            className={`ci-module-drawer-head ${pinned ? 'is-draggable' : ''}`}
            onMouseDown={onHeaderMouseDown}
          >
            <Space size={6}>
              <span className="ci-review-title-icon" style={{ width: 26, height: 26, fontSize: 13, borderRadius: 7 }}>
                <FolderOpenOutlined />
              </span>
              <span>模块目录</span>
              <span className="ci-status-pill" style={{ height: 19, fontSize: 10 }}>
                <span className="ci-status-pill-dot" />
                {leafCount} 个功能
              </span>
            </Space>
          </div>
        }
        extra={
          <Space size={2}>
            <Tooltip title="全部展开">
              <Button
                size="small"
                type="text"
                icon={<NodeExpandOutlined />}
                disabled={treeData.length === 0}
                onClick={() => setExpandedKeys(allExpandableKeys)}
              />
            </Tooltip>
            <Tooltip title="全部折叠">
              <Button
                size="small"
                type="text"
                icon={<NodeCollapseOutlined />}
                disabled={treeData.length === 0}
                onClick={() => setExpandedKeys([])}
              />
            </Tooltip>
            <Tooltip title={pinned ? '取消固定并放回左侧' : '固定后可拖拽到任意位置'}>
              <Button
                size="small"
                type="text"
                icon={pinned ? <PushpinFilled style={{ color: '#5258e8' }} /> : <PushpinOutlined />}
                onClick={handleTogglePin}
              />
            </Tooltip>
          </Space>
        }
      >
        <Spin spinning={loading} size="small" wrapperClassName="ci-module-drawer-spin">
          <div
            ref={treeScrollRef}
            className="ci-module-drawer-tree-wrap"
            style={{ height: treeViewportHeight, maxHeight: treeViewportHeight }}
          >
            {treeData.length > 0 ? (
              <Tree
                showLine={{ showLeafIcon: false }}
                blockNode
                treeData={treeData}
                expandedKeys={expandedKeys}
                onExpand={(keys) => setExpandedKeys(keys.map(String))}
                selectedKeys={selectedKeys}
                onSelect={(_keys, info) => {
                  const draftId = (info.node as DataNode & { draftId?: number }).draftId;
                  if (draftId != null) {
                    onSelectDraft(draftId);
                  }
                }}
              />
            ) : (
              <div className="ci-info-empty" style={{ padding: '24px 0 20px' }}>
                <strong>该工作区下暂无目录</strong>
                <Text type="secondary" style={{ fontSize: 12 }}>
                  等待任务生成模块层级与草稿
                </Text>
              </div>
            )}
          </div>
        </Spin>
        <button
          type="button"
          className="ci-module-drawer-resize-edge-right"
          aria-label="调整模块目录宽度"
          onMouseDown={(e) => onResizeMouseDown(e, 'width')}
        />
        <button
          type="button"
          className="ci-module-drawer-resize-edge-bottom"
          aria-label="调整模块目录高度"
          onMouseDown={(e) => onResizeMouseDown(e, 'height')}
        />
        <button
          type="button"
          className="ci-module-drawer-resize-handle"
          aria-label="调整模块目录大小"
          onMouseDown={(e) => onResizeMouseDown(e, 'both')}
        />
      </Card>
    </div>
  );
};

function useMemoSelectedKeys(treeData: DataNode[], selectedDraftId: number | null): string[] {
  const keys: string[] = [];
  if (selectedDraftId == null) return keys;
  const walk = (nodes: DataNode[]) => {
    for (const n of nodes) {
      const draftId = (n as DataNode & { draftId?: number }).draftId;
      if (draftId === selectedDraftId && n.key != null) {
        keys.push(String(n.key));
      }
      if (n.children?.length) walk(n.children);
    }
  };
  walk(treeData);
  return keys;
}

export default DraftModuleDirectory;
