import React from 'react';
import { Button, Col, Input, Row, Space } from 'antd';
import { PlusOutlined, ReloadOutlined, SearchOutlined, UploadOutlined } from '@ant-design/icons';

interface Props {
  searchName: string;
  searchComponent: string;
  searchOwner: string;
  onSearchNameChange: (v: string) => void;
  onSearchComponentChange: (v: string) => void;
  onSearchOwnerChange: (v: string) => void;
  onSearch: () => void;
  onReset: () => void;
  onAdd: () => void;
  onImport: () => void;
}

/**
 * 系统管理 - 筛选条
 * 纯受控组件，所有状态由父组件管理
 */
const SystemFilterBar: React.FC<Props> = ({
  searchName,
  searchComponent,
  searchOwner,
  onSearchNameChange,
  onSearchComponentChange,
  onSearchOwnerChange,
  onSearch,
  onReset,
  onAdd,
  onImport,
}) => (
  <Row gutter={[12, 12]} align="middle">
    <Col xs={24} md={6}>
      <Input
        placeholder="搜索系统"
        value={searchName}
        onChange={(e) => onSearchNameChange(e.target.value)}
        onPressEnter={onSearch}
        prefix={<SearchOutlined />}
      />
    </Col>
    <Col xs={24} md={6}>
      <Input
        placeholder="搜索组件"
        value={searchComponent}
        onChange={(e) => onSearchComponentChange(e.target.value)}
        onPressEnter={onSearch}
      />
    </Col>
    <Col xs={24} md={6}>
      <Input
        placeholder="筛选负责人"
        value={searchOwner}
        onChange={(e) => onSearchOwnerChange(e.target.value)}
        onPressEnter={onSearch}
      />
    </Col>
    <Col xs={24} md={6}>
      <Space className="ci-toolbar-actions" wrap>
        <Button type="primary" icon={<SearchOutlined />} onClick={onSearch}>
          查询
        </Button>
        <Button icon={<ReloadOutlined />} onClick={onReset}>
          重置
        </Button>
        <Button type="primary" icon={<PlusOutlined />} onClick={onAdd}>
          新增系统
        </Button>
        <Button icon={<UploadOutlined />} onClick={onImport}>
          批量导入
        </Button>
      </Space>
    </Col>
  </Row>
);

export default SystemFilterBar;
