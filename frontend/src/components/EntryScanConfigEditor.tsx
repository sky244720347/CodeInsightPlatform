import type React from 'react';
import { useMemo, useState } from 'react';
import { Button, Card, Form, Segmented, Select, Space, Typography } from 'antd';
import { EditOutlined } from '@ant-design/icons';
import type { EntryScanTypeKey, ExcludeTarget } from '../types';
import { ENTRY_SCAN_TYPE_KEYS, ENTRY_SCAN_TYPE_LABELS } from '../utils/scanConfigDefaults';
import EntryScanExcludeTargetsModal from './EntryScanExcludeTargetsModal';

const { Text } = Typography;

const TYPE_FIELD: Record<
  EntryScanTypeKey,
  { annotations: string[]; classpaths: string[]; extends: string[] }
> = {
  CONTROLLER: {
    annotations: ['entryScanConfig', 'includesByType', 'CONTROLLER', 'includeAnnotations'],
    classpaths: ['entryScanConfig', 'includesByType', 'CONTROLLER', 'includeClasspaths'],
    extends: ['entryScanConfig', 'includesByType', 'CONTROLLER', 'includeExtends'],
  },
  SCHEDULED_JOB: {
    annotations: ['entryScanConfig', 'includesByType', 'SCHEDULED_JOB', 'includeAnnotations'],
    classpaths: ['entryScanConfig', 'includesByType', 'SCHEDULED_JOB', 'includeClasspaths'],
    extends: ['entryScanConfig', 'includesByType', 'SCHEDULED_JOB', 'includeExtends'],
  },
  MQ_LISTENER: {
    annotations: ['entryScanConfig', 'includesByType', 'MQ_LISTENER', 'includeAnnotations'],
    classpaths: ['entryScanConfig', 'includesByType', 'MQ_LISTENER', 'includeClasspaths'],
    extends: ['entryScanConfig', 'includesByType', 'MQ_LISTENER', 'includeExtends'],
  },
  OTHER: {
    annotations: ['entryScanConfig', 'includesByType', 'OTHER', 'includeAnnotations'],
    classpaths: ['entryScanConfig', 'includesByType', 'OTHER', 'includeClasspaths'],
    extends: ['entryScanConfig', 'includesByType', 'OTHER', 'includeExtends'],
  },
};

const RuleRow: React.FC<{ label: string; name: (string | number)[]; placeholder: string }> = ({
  label,
  name,
  placeholder,
}) => (
  <div className="ci-scan-config-row">
    <span className="ci-scan-config-label">{label}</span>
    <Form.Item name={name} noStyle>
      <Select
        size="small"
        mode="tags"
        placeholder={placeholder}
        style={{ width: '100%' }}
        tokenSeparators={[',']}
      />
    </Form.Item>
  </div>
);

/**
 * 入口扫描配置：
 *  - 上半：扫描配置（按 Controller/Job/MQ/其他 四类 Tab 独立 include）
 *  - 下半：排除配置（全局，四类共用：类路径 / 包路径 / 注解）
 *  - 指定排除列表（类 / 类+方法）单独 Modal 配置,行内只展示摘要
 */
const EntryScanConfigEditor: React.FC = () => {
  const [activeType, setActiveType] = useState<EntryScanTypeKey>('CONTROLLER');
  const form = Form.useFormInstance();
  const fields = TYPE_FIELD[activeType];

  /** 指定排除列表独立 Modal */
  const [excludeModalOpen, setExcludeModalOpen] = useState(false);

  /** 监听 excludeTargets 变化,用于行内摘要 */
  const excludeTargets: ExcludeTarget[] =
    Form.useWatch(['entryScanConfig', 'excludeTargets'], form) ?? [];
  const { classCount, methodCount } = useMemo(() => {
    let c = 0;
    let m = 0;
    for (const t of excludeTargets) {
      if (t?.methodSignature?.trim()) m += 1;
      else if (t?.className?.trim()) c += 1;
    }
    return { classCount: c, methodCount: m };
  }, [excludeTargets]);

  return (
    <div className="ci-scan-config">
      <Text type="secondary" style={{ display: 'block', marginBottom: 8 }}>
        扫描配置按 Controller → Job → MQ → 其他 优先级匹配，一个类只归一类。
      </Text>

      {/* 上框：扫描配置 */}
      <Card
        size="small"
        className="ci-scan-config-box"
        title={
          <Space>
            <span>扫描配置</span>
            <Text type="secondary" style={{ fontSize: 12, fontWeight: 'normal' }}>
              {ENTRY_SCAN_TYPE_LABELS[activeType]} — 入口识别（满足任一即视为该类型入口）
            </Text>
          </Space>
        }
      >
        <Segmented
          value={activeType}
          onChange={(v) => setActiveType(v as EntryScanTypeKey)}
          options={ENTRY_SCAN_TYPE_KEYS.map((k) => ({
            label: ENTRY_SCAN_TYPE_LABELS[k],
            value: k,
          }))}
          style={{ marginBottom: 12 }}
        />
        <RuleRow label="注解" name={fields.annotations} placeholder="如 RestController" />
        <RuleRow label="类路径" name={fields.classpaths} placeholder="如 com.example.web.**" />
        <RuleRow label="继承/实现" name={fields.extends} placeholder="如 CommandLineRunner" />
      </Card>

      {/* 中间断点 */}
      <div className="ci-scan-config-divider" />

      {/* 下框：排除配置 */}
      <Card
        size="small"
        className="ci-scan-config-box"
        title={
          <Space>
            <span>排除配置</span>
            <Text type="secondary" style={{ fontSize: 12, fontWeight: 'normal' }}>
              全局，四类共用
            </Text>
          </Space>
        }
      >
        <RuleRow
          label="类路径"
          name={['entryScanConfig', 'excludeClasspaths']}
          placeholder="如 **/*Test"
        />
        <RuleRow
          label="包路径"
          name={['entryScanConfig', 'excludePackages']}
          placeholder="如 com.legacy.config"
        />
        <RuleRow
          label="注解"
          name={['entryScanConfig', 'excludeAnnotations']}
          placeholder="如 Internal"
        />
      </Card>

      {/* 中间断点 */}
      <div className="ci-scan-config-divider" />

      {/* 指定排除列表：摘要 + 按钮打开独立 Modal */}
      <div className="ci-scan-config-row" style={{ alignItems: 'center' }}>
        <span className="ci-scan-config-label" style={{ flex: '0 0 auto' }}>
          指定排除列表
        </span>
        <div
          style={{
            flex: 1,
            display: 'flex',
            justifyContent: 'space-between',
            alignItems: 'center',
            gap: 8,
          }}
        >
          <Text type="secondary" style={{ fontSize: 12 }}>
            {excludeTargets.length === 0
              ? '尚未配置'
              : `已排除 ${classCount} 个类 / ${methodCount} 个方法`}
          </Text>
          <Button
            size="small"
            type="link"
            icon={<EditOutlined />}
            onClick={() => setExcludeModalOpen(true)}
          >
            {excludeTargets.length === 0 ? '去配置' : '配置'}
          </Button>
        </div>
      </div>

      <EntryScanExcludeTargetsModal
        open={excludeModalOpen}
        onClose={() => setExcludeModalOpen(false)}
      />
    </div>
  );
};

export default EntryScanConfigEditor;