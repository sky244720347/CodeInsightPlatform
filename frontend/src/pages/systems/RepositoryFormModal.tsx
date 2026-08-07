import React, { useEffect, useMemo, useState } from 'react';
import { Button, Col, Form, Input, Modal, Row, Select } from 'antd';
import type { FormInstance } from 'antd';
import { getTechStackCatalog } from '../../api/repository';
import type { TechStackCatalog } from '../../types';

export interface RepositoryFormValues {
  gitUrl: string;
  repoType: string;
  techStack: string;
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
}) => {
  const [catalog, setCatalog] = useState<TechStackCatalog>({});
  const repoType = Form.useWatch('repoType', form);

  useEffect(() => {
    if (!open) return;
    let cancelled = false;
    getTechStackCatalog()
      .then((data) => {
        if (!cancelled) setCatalog(data || {});
      })
      .catch(() => {
        if (!cancelled) setCatalog({});
      });
    return () => {
      cancelled = true;
    };
  }, [open]);

  const repoTypeOptions = useMemo(
    () => Object.keys(catalog).map((code) => ({ value: code, label: code })),
    [catalog],
  );

  const techStackOptions = useMemo(() => {
    const stacks = repoType ? catalog[repoType] || [] : [];
    return stacks.map((code) => ({ value: code, label: code }));
  }, [catalog, repoType]);

  return (
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
            <Form.Item
              name="repoType"
              label="代码库类型"
              rules={[{ required: true, message: '请选择代码库类型' }]}
              extra="混合仓可选「前后端」；技术栈可多选。仍建议用扫描根目录缩小范围"
            >
              <Select
                placeholder="前端 / 后端 / 前后端 / DB"
                options={repoTypeOptions}
                onChange={() => form.setFieldsValue({ techStack: undefined })}
              />
            </Form.Item>
          </Col>
          <Col span={12}>
            <Form.Item
              name="techStack"
              label="技术栈"
              rules={[{ required: true, message: '请选择技术栈' }]}
              getValueFromEvent={(v) => (Array.isArray(v) ? v.join(',') : v)}
              getValueProps={(v) => ({
                value:
                  repoType === '前后端'
                    ? typeof v === 'string' && v
                      ? v.split(',').map((s) => s.trim()).filter(Boolean)
                      : []
                    : v,
              })}
            >
              <Select
                mode={repoType === '前后端' ? 'multiple' : undefined}
                placeholder={
                  !repoType
                    ? '请先选择代码库类型'
                    : repoType === '前后端'
                      ? '请选择前端+后端技术栈'
                      : '请选择技术栈'
                }
                options={techStackOptions}
                disabled={!repoType}
              />
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
};

export default RepositoryFormModal;
