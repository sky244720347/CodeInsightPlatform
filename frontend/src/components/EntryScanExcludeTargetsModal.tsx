import type React from 'react';
import { useMemo } from 'react';
import {
  Button,
  Empty,
  Form,
  Input,
  Modal,
  Space,
  Table,
  Tooltip,
  Typography,
  message,
} from 'antd';
import { DeleteOutlined, PlusOutlined } from '@ant-design/icons';
import type { ExcludeTarget } from '../types';

const { Text } = Typography;

interface Props {
  open: boolean;
  onClose: () => void;
}

/**
 * 指定排除列表（类 / 类+方法）独立配置弹窗。
 * 通过 Form.useFormInstance 拿到父表单实例,Form.List 双向绑定到
 * ['entryScanConfig', 'excludeTargets'],行内增删改直接作用在父表单字段上。
 * 弹窗本身只控制"开 / 关",由父级表单的提交按钮统一落库。
 */
const EntryScanExcludeTargetsModal: React.FC<Props> = ({ open, onClose }) => {
  const form = Form.useFormInstance();

  /** 监听 excludeTargets 用于表格渲染与摘要 */
  const targets: ExcludeTarget[] =
    Form.useWatch(['entryScanConfig', 'excludeTargets'], form) ?? [];

  const summary = useMemo(() => {
    let c = 0;
    let m = 0;
    for (const t of targets) {
      if (t?.methodSignature?.trim()) m += 1;
      else if (t?.className?.trim()) c += 1;
    }
    return { classCount: c, methodCount: m };
  }, [targets]);

  const handleClose = () => {
    // 行内校验：若有未填的类名,提示但不阻断关闭
    const invalid = targets.findIndex((t) => !t?.className?.trim());
    if (invalid >= 0) {
      message.warning(`第 ${invalid + 1} 行类名未填写，已忽略该行`);
      form.setFieldValue(
        ['entryScanConfig', 'excludeTargets'],
        targets.filter((t) => t?.className?.trim()),
      );
    }
    onClose();
  };

  return (
    <Modal
      title="指定排除列表"
      open={open}
      onCancel={handleClose}
      width={640}
      destroyOnClose
      footer={[
        <Button key="close" onClick={handleClose}>
          关闭
        </Button>,
      ]}
    >
      <Text type="secondary" style={{ display: 'block', marginBottom: 12, fontSize: 12 }}>
        指定要从入口识别结果中排除的类或方法。方法签名为空表示整类排除。
        {targets.length > 0 && (
          <span style={{ marginLeft: 8 }}>
            当前共 {targets.length} 条（{summary.classCount} 类 / {summary.methodCount} 方法）
          </span>
        )}
      </Text>

      <Form.List name={['entryScanConfig', 'excludeTargets']}>
        {(fields, { add, remove }) => (
          <>
            {fields.length === 0 ? (
              <Empty
                image={Empty.PRESENTED_IMAGE_SIMPLE}
                description="尚未配置指定排除项"
                style={{ margin: '12px 0' }}
              >
                <Button
                  type="primary"
                  size="small"
                  icon={<PlusOutlined />}
                  onClick={() => add({ className: '', methodSignature: '' })}
                >
                  添加排除项
                </Button>
              </Empty>
            ) : (
              <>
                <Table<ExcludeTarget>
                  size="small"
                  rowKey={(_, idx) => `row-${idx}`}
                  pagination={false}
                  dataSource={targets}
                  columns={[
                    {
                      title: '类名',
                      dataIndex: 'className',
                      key: 'className',
                      width: '50%',
                      render: (_: string, _record: ExcludeTarget, idx: number) => (
                        <Form.Item
                          name={[idx, 'className']}
                          noStyle
                          rules={[{ required: true, message: '类名必填' }]}
                        >
                          <Input
                            size="small"
                            placeholder="全限定类名,如 com.example.FooController"
                          />
                        </Form.Item>
                      ),
                    },
                    {
                      title: '方法签名',
                      dataIndex: 'methodSignature',
                      key: 'methodSignature',
                      width: '40%',
                      render: (_: string, _record: ExcludeTarget, idx: number) => (
                        <Form.Item name={[idx, 'methodSignature']} noStyle>
                          <Input
                            size="small"
                            placeholder="留空 = 整类排除"
                          />
                        </Form.Item>
                      ),
                    },
                    {
                      title: '操作',
                      key: 'action',
                      width: 60,
                      align: 'center',
                      render: (_: unknown, _record: ExcludeTarget, idx: number) => (
                        <Tooltip title="删除该行">
                          <Button
                            size="small"
                            type="text"
                            danger
                            icon={<DeleteOutlined />}
                            onClick={() => remove(idx)}
                          />
                        </Tooltip>
                      ),
                    },
                  ]}
                  scroll={{ y: 360 }}
                />
                <Space style={{ marginTop: 8 }}>
                  <Button
                    size="small"
                    type="dashed"
                    icon={<PlusOutlined />}
                    onClick={() => add({ className: '', methodSignature: '' })}
                  >
                    添加排除项
                  </Button>
                </Space>
              </>
            )}
          </>
        )}
      </Form.List>
    </Modal>
  );
};

export default EntryScanExcludeTargetsModal;