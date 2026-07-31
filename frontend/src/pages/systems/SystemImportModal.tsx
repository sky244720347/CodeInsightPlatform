import React, { useState } from 'react';
import { Alert, Button, Modal, Space, Table, Upload, message } from 'antd';
import { DownloadOutlined, InboxOutlined } from '@ant-design/icons';
import type { UploadFile } from 'antd/es/upload/interface';
import {
  downloadSystemImportTemplate,
  importSystemsFromExcel,
  type SystemImportItemResult,
  type SystemImportResult,
} from '../../api/system';

interface Props {
  open: boolean;
  onClose: () => void;
  onCompleted: () => void;
}

const STATUS_LABEL: Record<string, string> = {
  CREATED: '新建',
  SYSTEM_REUSED: '复用系统',
  SKIPPED: '跳过',
  FAILED: '失败',
};

/**
 * 系统 Excel 批量导入：只上传文件，无其它表单字段。
 */
const SystemImportModal: React.FC<Props> = ({ open, onClose, onCompleted }) => {
  const [fileList, setFileList] = useState<UploadFile[]>([]);
  const [submitting, setSubmitting] = useState(false);
  const [result, setResult] = useState<SystemImportResult | null>(null);

  const reset = () => {
    setFileList([]);
    setResult(null);
    setSubmitting(false);
  };

  const handleClose = () => {
    reset();
    onClose();
  };

  const handleOk = async () => {
    const file = fileList[0]?.originFileObj;
    if (!file) {
      message.warning('请先选择 .xlsx 文件');
      return;
    }
    setSubmitting(true);
    try {
      const res = await importSystemsFromExcel(file);
      setResult(res);
      message.success(
        `导入完成：新建系统 ${res.systemCreated}，复用系统 ${res.systemReused}，新建仓库 ${res.repoCreated}，跳过 ${res.skipped}，失败 ${res.failed}`,
      );
      onCompleted();
    } catch (err) {
      console.error(err);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Modal
      title="批量导入系统与仓库"
      open={open}
      onCancel={handleClose}
      onOk={handleOk}
      okText="开始导入"
      cancelText="关闭"
      confirmLoading={submitting}
      okButtonProps={{ disabled: fileList.length === 0 }}
      width={820}
      destroyOnHidden
    >
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
        message="请先下载模板，按表头填写后上传 .xlsx。"
        description={
          <Space direction="vertical" size={8} style={{ width: '100%' }}>
            <span>
              必填列：系统、系统中文名、系统描述、负责人、git完整地址、代码库类型、技术栈。系统已存在则复用；同系统下仓库
              URL 已存在则跳过。「代码库类型 / 技术栈」请用模板下拉选择（技术栈随类型联动）。仓库其它配置与新建向导默认一致（含默认提示词）。
            </span>
            <Button
              type="link"
              icon={<DownloadOutlined />}
              onClick={downloadSystemImportTemplate}
              style={{ paddingInline: 0 }}
            >
              下载导入模板
            </Button>
          </Space>
        }
      />
      <Upload.Dragger
        accept=".xlsx"
        maxCount={1}
        fileList={fileList}
        beforeUpload={() => false}
        onChange={({ fileList: next }) => {
          setFileList(next.slice(-1));
          setResult(null);
        }}
        disabled={submitting}
      >
        <p className="ant-upload-drag-icon">
          <InboxOutlined />
        </p>
        <p className="ant-upload-text">点击或拖拽 .xlsx 到此处</p>
      </Upload.Dragger>

      {result && (
        <>
          <Alert
            style={{ marginTop: 16, marginBottom: 12 }}
            type={result.failed > 0 ? 'warning' : 'success'}
            showIcon
            message={`共 ${result.totalRows} 行：系统新建 ${result.systemCreated} / 复用 ${result.systemReused}，仓库新建 ${result.repoCreated}，跳过 ${result.skipped}，失败 ${result.failed}`}
          />
          <Table<SystemImportItemResult>
            size="small"
            rowKey={(r) => `${r.row}-${r.gitUrl ?? ''}`}
            dataSource={result.items}
            pagination={{ pageSize: 8 }}
            scroll={{ y: 280 }}
            columns={[
              { title: '行', dataIndex: 'row', width: 56 },
              { title: '系统', dataIndex: 'systemName', width: 120, ellipsis: true },
              { title: 'Git', dataIndex: 'gitUrl', ellipsis: true },
              {
                title: '结果',
                dataIndex: 'status',
                width: 100,
                render: (s: string) => STATUS_LABEL[s] ?? s,
              },
              { title: '说明', dataIndex: 'message', ellipsis: true },
            ]}
          />
        </>
      )}
    </Modal>
  );
};

export default SystemImportModal;
