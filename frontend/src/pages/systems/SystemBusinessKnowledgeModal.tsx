import React, { useEffect, useState } from 'react';
import {
  Alert,
  Button,
  Input,
  Modal,
  Space,
  Tag,
  Typography,
  message,
} from 'antd';
import { BookOutlined, SaveOutlined } from '@ant-design/icons';
import {
  getBusinessKnowledge,
  upsertBusinessKnowledge,
  type BusinessKnowledge,
} from '../../api/businessKnowledge';
import type { System } from '../../types';

const { Text } = Typography;

/** 后端正文字符上限（与 BusinessKnowledgeServiceImpl.MAX_CONTENT_LENGTH 对齐） */
const MAX_CONTENT_LENGTH = 64 * 1024;

interface Props {
  /** 是否打开 */
  open: boolean;
  /** 当前编辑目标的系统记录（必传；父组件需保证非空再 open） */
  system: System | null;
  /** 关闭弹窗 */
  onClose: () => void;
  /** 保存成功回调（用于外部刷新统计/缓存） */
  onSaved?: (record: BusinessKnowledge) => void;
}

/**
 * 「业务知识配置」弹窗（按系统维度）
 *
 * <p>在系统表的"操作"列中点击"业务知识"按钮打开，编辑后保存。</p>
 * <p>内容会作为 {@code {business_knowledge.md}} 占位符值，在 AI 调用模块提取前自动注入提示词。</p>
 *
 * <p>风格：与 {@link SystemPromptEditorModal} 一致——使用 Input.TextArea 而非 Monaco，</p>
 * <p>保持"系统设置"类弹窗的轻量感；不引入额外的编辑器依赖。</p>
 */
const SystemBusinessKnowledgeModal: React.FC<Props> = ({ open, system, onClose, onSaved }) => {
  const [content, setContent] = useState('');
  const [version, setVersion] = useState<number | null>(null);
  const [updatedDate, setUpdatedDate] = useState<string | null>(null);
  const [updatedBy, setUpdatedBy] = useState<string | null>(null);
  const [initialContent, setInitialContent] = useState('');
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);

  // 打开时拉取该系统的业务知识（无配置时显示空内容）
  useEffect(() => {
    if (!open || !system) return;
    setContent('');
    setInitialContent('');
    setVersion(null);
    setUpdatedDate(null);
    setUpdatedBy(null);

    let cancelled = false;
    setLoading(true);
    getBusinessKnowledge(system.id)
      .then((record) => {
        if (cancelled) return;
        if (record) {
          const c = record.content ?? '';
          setContent(c);
          setInitialContent(c);
          setVersion(record.version ?? null);
          setUpdatedDate(record.updatedDate ?? null);
          setUpdatedBy(record.updatedBy ?? null);
        } else {
          // 无配置时，初始 baseline 留空串；保存后端视为首次插入
          setInitialContent('');
        }
      })
      .catch((err) => {
        // 拦截器已统一提示；保持空内容即可
        console.error('加载业务知识失败', err);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, [open, system]);

  const handleSave = async () => {
    if (!system) return;
    if (content.length > MAX_CONTENT_LENGTH) {
      message.error(`内容超过 ${MAX_CONTENT_LENGTH} 字符上限`);
      return;
    }
    setSaving(true);
    try {
      const saved = await upsertBusinessKnowledge({
        systemId: system.id,
        content,
        updatedBy: undefined, // 后端操作日志会从会话兜底；MVP 不强制前端传
      });
      message.success('业务知识已保存');
      onSaved?.(saved);
      onClose();
    } catch (err) {
      // 拦截器已提示
      console.error('保存业务知识失败', err);
    } finally {
      setSaving(false);
    }
  };

  const charCount = content.length;
  const overLimit = charCount > MAX_CONTENT_LENGTH;
  const dirty = content !== initialContent;

  return (
    <Modal
      title={
        <Space>
          <BookOutlined />
          业务知识配置
          {system && (
            <Text type="secondary" style={{ fontSize: 13, fontWeight: 'normal' }}>
              · {system.name}
              {system.nameCn ? `（${system.nameCn}）` : ''}
            </Text>
          )}
        </Space>
      }
      open={open}
      onCancel={onClose}
      width={860}
      destroyOnClose
      footer={[
        <Button key="cancel" onClick={onClose} disabled={saving}>
          取消
        </Button>,
        <Button
          key="save"
          type="primary"
          icon={<SaveOutlined />}
          loading={saving}
          disabled={loading || overLimit}
          onClick={handleSave}
        >
          保存
        </Button>,
      ]}
    >
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
        message={
          <span>
            维护该系统已知的业务领域知识（业务实体、领域术语、关键概念）。
            在 AI 执行模块提取（{`{business_knowledge.md}`} 占位符）时会自动注入到提示词中，
            帮助 AI 更精准地匹配与提取业务模块。
          </span>
        }
      />

      <Space size={16} style={{ marginBottom: 12 }} wrap>
        <Tag color={version ? 'geekblue' : 'default'}>
          {version ? `当前版本 v${version}` : '尚未配置'}
        </Tag>
        {updatedDate && (
          <Text type="secondary" style={{ fontSize: 12 }}>
            最近保存：{new Date(updatedDate).toLocaleString()}
            {updatedBy ? ` · ${updatedBy}` : ''}
          </Text>
        )}
        <Text type={overLimit ? 'danger' : 'secondary'} style={{ fontSize: 12 }}>
          字符数：{charCount} / {MAX_CONTENT_LENGTH}
        </Text>
      </Space>

      <Input.TextArea
        value={content}
        onChange={(e) => setContent(e.target.value)}
        disabled={loading}
        autoSize={{ minRows: 16, maxRows: 28 }}
        className="ci-code-input"
        placeholder={
          '# 业务领域描述\n\n' +
          '## 核心业务实体\n- 房产、白名单、公积金...\n\n' +
          '## 业务术语\n- 房管局业务：包含授权、备案、查询...\n\n' +
          '## 业务约束\n- 仅面向重庆/佛山地区的房管局对接...'
        }
      />

      {dirty && (
        <Text type="warning" style={{ fontSize: 12, display: 'block', marginTop: 8 }}>
          当前内容已修改但未保存
        </Text>
      )}
    </Modal>
  );
};

export default SystemBusinessKnowledgeModal;
