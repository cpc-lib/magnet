import { useCallback, useEffect, useState } from 'react';
import { Button, Empty, Input, message, Select, Space, Table, Tag, Typography, Upload } from 'antd';
import { ReloadOutlined, UploadOutlined } from '@ant-design/icons';
import { Link } from 'react-router-dom';
import client from '@/api/client';
import type { PageView, ParseJobView, ParseStatus } from '@/api/types';
import { PATHS } from '@/router/paths';
import { formatTime } from '@/utils/format';

const STATUS_TAG: Record<ParseStatus, { color: string; label: string }> = {
  PENDING: { color: 'default', label: '等待中' },
  RESOLVING: { color: 'processing', label: '解析中' },
  READY: { color: 'success', label: '已就绪' },
  FAILED: { color: 'error', label: '失败' },
  TIMEOUT: { color: 'warning', label: '超时' },
};

export default function ParsesPage() {
  const [data, setData] = useState<PageView<ParseJobView>>({ items: [], total: 0, page: 1, size: 20 });
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(20);
  const [status, setStatus] = useState<ParseStatus | undefined>();
  const [magnetInput, setMagnetInput] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [loading, setLoading] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const { data: pageData } = await client.get<PageView<ParseJobView>>('/parses', {
        params: { page, size, ...(status ? { status } : {}) },
      });
      setData(pageData);
    } catch (err: any) {
      message.error(err.response?.data?.message ?? '加载失败');
    } finally {
      setLoading(false);
    }
  }, [page, size, status]);

  useEffect(() => {
    load();
  }, [load]);

  const submitMagnet = async (uri: string) => {
    const trimmed = uri.trim();
    if (!trimmed) return;
    setSubmitting(true);
    try {
      await client.post('/parses', { uri: trimmed });
      message.success('已提交解析任务');
      setMagnetInput('');
      setPage(1);
      load();
    } catch (err: any) {
      message.error(err.response?.data?.message ?? '提交失败');
    } finally {
      setSubmitting(false);
    }
  };

  const retry = async (id: string) => {
    try {
      await client.post(`/parses/${id}/retry`);
      message.success('已重新提交解析');
      load();
    } catch (err: any) {
      message.error(err.response?.data?.message ?? '重试失败');
    }
  };

  return (
    <div>
      <div className="page-header">
        <div>
          <h1 className="page-title">资源解析</h1>
          <p className="page-subtitle">提交 Magnet 链接、HTTP/HTTPS 直链或上传 .torrent 文件，校验通过后可按文件创建下载任务</p>
        </div>
      </div>

      <div className="surface-card">
        <div className="toolbar-row">
          <Space size={8} wrap>
            <Input.Search
              allowClear
              value={magnetInput}
              onChange={(e) => setMagnetInput(e.target.value)}
              placeholder="粘贴 Magnet 链接或 HTTP/HTTPS 直链"
              enterButton="解析链接"
              loading={submitting}
              style={{ width: 420 }}
              onSearch={submitMagnet}
            />
            <Upload
              accept=".torrent"
              showUploadList={false}
              customRequest={async ({ file, onSuccess, onError }) => {
                const form = new FormData();
                form.append('file', file as File);
                try {
                  await client.post('/parses/upload', form);
                  message.success('已上传并完成元数据解析');
                  setPage(1);
                  load();
                  onSuccess?.({});
                } catch (err: any) {
                  message.error(err.response?.data?.message ?? '上传失败');
                  onError?.(err);
                }
              }}
            >
              <Button icon={<UploadOutlined />}>上传 .torrent</Button>
            </Upload>
          </Space>

          <Space size={8}>
            <Select
              allowClear
              placeholder="全部状态"
              style={{ width: 136 }}
              value={status}
              onChange={(v) => {
                setStatus(v);
                setPage(1);
              }}
              options={(['PENDING', 'RESOLVING', 'READY', 'FAILED', 'TIMEOUT'] as ParseStatus[]).map((s) => ({
                value: s,
                label: STATUS_TAG[s].label,
              }))}
            />
            <Button icon={<ReloadOutlined />} onClick={load}>
              刷新
            </Button>
          </Space>
        </div>

        <Table<ParseJobView>
          rowKey="id"
          size="small"
          loading={loading}
          dataSource={data.items}
          locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无解析记录，请在上方粘贴磁力链接或上传种子文件" /> }}
          pagination={{
            current: page,
            pageSize: size,
            total: data.total,
            showSizeChanger: true,
            pageSizeOptions: [10, 20, 30, 50],
            showTotal: (t) => `共 ${t} 条`,
            style: { padding: '0 16px', margin: '12px 0' },
            onChange: (p, s) => {
              setPage(p);
              setSize(s);
            },
          }}
          columns={[
            {
              title: '名称 / 标识',
              dataIndex: 'displayHint',
              ellipsis: true,
              render: (v: string | null, row) => (
                <Link to={PATHS.parseDetail(row.id)} style={{ fontWeight: 500 }}>
                  {v || row.id}
                </Link>
              ),
            },
            {
              title: '来源',
              dataIndex: 'inputType',
              width: 100,
              render: (v: string) => <Tag bordered={false}>{v}</Tag>,
            },
            {
              title: '状态',
              dataIndex: 'status',
              width: 100,
              render: (v: ParseStatus) => <Tag color={STATUS_TAG[v].color}>{STATUS_TAG[v].label}</Tag>,
            },
            {
              title: '错误信息',
              dataIndex: 'errorMessage',
              ellipsis: true,
              render: (v: string | null) =>
                v ? <Typography.Text type="danger" style={{ fontSize: 12 }}>{v}</Typography.Text> : <Typography.Text type="secondary">-</Typography.Text>,
            },
            {
              title: '创建时间',
              dataIndex: 'createdAt',
              width: 168,
              render: (v: string) => <span className="tabular-nums" style={{ color: 'var(--color-text-secondary)', fontSize: 12 }}>{formatTime(v)}</span>,
            },
            {
              title: '操作',
              key: 'actions',
              width: 140,
              align: 'right',
              render: (_, row) => (
                <Space size={8}>
                  <Link to={PATHS.parseDetail(row.id)}>
                    {row.status === 'READY' ? '选择文件' : '查看详情'}
                  </Link>
                  {(row.status === 'FAILED' || row.status === 'TIMEOUT') && (
                    <Button type="link" size="small" style={{ padding: 0 }} onClick={() => retry(row.id)}>
                      重试
                    </Button>
                  )}
                </Space>
              ),
            },
          ]}
        />
      </div>
    </div>
  );
}
