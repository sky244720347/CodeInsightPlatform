import React from 'react';
import { Button, Col, Form, Input, Modal, Row } from 'antd';
import type { FormInstance } from 'antd';

export interface SystemFormValues {
  name: string;
  /** 组件标识；与系统名称联合查重；可空 */
  component?: string;
  nameCn?: string;
  owner: string;
  description?: string;
}

interface Props {
  open: boolean;
  form: FormInstance<SystemFormValues>;
  submitting?: boolean;
  onCancel: () => void;
  onSubmit: () => void;
}

/** 编辑系统基本信息 Modal */
const SystemFormModal: React.FC<Props> = ({
  open,
  form,
  submitting = false,
  onCancel,
  onSubmit,
}) => (
  <Modal
    title="编辑系统"
    open={open}
    onCancel={onCancel}
    width={680}
    footer={[
      <Button key="cancel" onClick={onCancel}>
        取消
      </Button>,
      <Button key="submit" type="primary" loading={submitting} onClick={onSubmit}>
        保存
      </Button>,
    ]}
    destroyOnHidden
  >
    <Form<SystemFormValues> form={form} layout="vertical" style={{ marginTop: 16 }}>
      <Row gutter={16}>
        <Col span={12}>
          <Form.Item
            name="name"
            label="系统名称"
            rules={[{ required: true, message: '请输入系统标识（如 order-service）' }]}
          >
            <Input placeholder="order-service" />
          </Form.Item>
        </Col>
        <Col span={12}>
          <Form.Item
            name="component"
            label="组件"
            extra="与系统名称联合唯一；可不填表示无组件"
          >
            <Input placeholder="billing（可选）" allowClear />
          </Form.Item>
        </Col>
        <Col span={12}>
          <Form.Item name="nameCn" label="中文名称">
            <Input placeholder="订单服务系统" />
          </Form.Item>
        </Col>
        <Col span={12}>
          <Form.Item name="owner" label="负责人" rules={[{ required: true, message: '请输入负责人' }]}>
            <Input placeholder="开发负责人" />
          </Form.Item>
        </Col>
        <Col span={24}>
          <Form.Item name="description" label="描述">
            <Input.TextArea rows={3} placeholder="核心业务职责和技术范围" />
          </Form.Item>
        </Col>
      </Row>
    </Form>
  </Modal>
);

export default SystemFormModal;
