import React from 'react';
import { Button, Col, Form, Input, Modal, Row } from 'antd';
import type { FormInstance } from 'antd';

export interface RepositoryFormValues {
  gitUrl: string;
  branch: string;
  scanRoot: string;
  username?: string;
  password?: string;
  excludeDirs?: string;
  excludeFileTypes?: string;
}

interface Props {
  open: boolean;
  editing: boolean;
  testing: boolean;
  submitting?: boolean;
  form: FormInstance<RepositoryFormValues>;
  onCancel: () => void;
  onSubmit: () => void;
  onTest: () => void;
}

/** 创建 / 编辑代码库 Modal */
const RepositoryFormModal: React.FC<Props> = ({
  open,
  editing,
  testing,
  submitting = false,
  form,
  onCancel,
  onSubmit,
  onTest,
}) => (
  <Modal
    title={editing ? '编辑代码库' : '添加代码库'}
    open={open}
    onCancel={onCancel}
    width={680}
    footer={[
      <Button key="cancel" onClick={onCancel}>
        取消
      </Button>,
      <Button key="test" loading={testing} onClick={onTest}>
        测试 Git
      </Button>,
      <Button key="submit" type="primary" loading={submitting} onClick={onSubmit}>
        保存
      </Button>,
    ]}
    destroyOnHidden
  >
    <Form<RepositoryFormValues> form={form} layout="vertical" style={{ marginTop: 16 }}>
      <Row gutter={16}>
        <Col span={24}>
          <Form.Item
            name="gitUrl"
            label="Git 地址"
            rules={[{ required: true, message: '请输入 Git 地址' }]}
          >
            <Input placeholder="https://github.com/company/project.git" />
          </Form.Item>
        </Col>
        <Col span={12}>
          <Form.Item name="branch" label="分支" rules={[{ required: true, message: '请输入分支' }]}>
            <Input placeholder="master" />
          </Form.Item>
        </Col>
        <Col span={12}>
          <Form.Item
            name="scanRoot"
            label="扫描根目录"
            rules={[{ required: true, message: '请输入扫描根目录' }]}
          >
            <Input placeholder="/ 或 /backend" />
          </Form.Item>
        </Col>
        <Col span={12}>
          <Form.Item name="username" label="用户名">
            <Input placeholder="私有仓库账号" />
          </Form.Item>
        </Col>
        <Col span={12}>
          <Form.Item name="password" label="密码 / 访问令牌">
            <Input.Password placeholder="仅由后端存储" />
          </Form.Item>
        </Col>
        <Col span={12}>
          <Form.Item name="excludeDirs" label="排除目录">
            <Input placeholder=".git,target,test,bin" />
          </Form.Item>
        </Col>
        <Col span={12}>
          <Form.Item name="excludeFileTypes" label="排除文件类型">
            <Input placeholder=".md,.txt,.xml,.json" />
          </Form.Item>
        </Col>
      </Row>
    </Form>
  </Modal>
);

export default RepositoryFormModal;
