import { useCallback, useEffect, useRef, useState } from 'react';
import {
  Button,
  Checkbox,
  Empty,
  InputNumber,
  message,
  Modal,
  Progress,
  Select,
  Space,
  Table,
  Tag,
  Typography,
} from 'antd';
import { DownloadOutlined, ReloadOutlined } from '@ant-design/icons';
import { Link, useNavigate } from 'react-router-dom';
import client from '@/api/client';
import { PATHS } from '@/router/paths';
import { useAuth } from '@/store/auth';
import type { ExportView, PageView, TaskStatus, TaskView } from '@/api/types';
import { formatBytes, formatTime } from '@/utils/format';

const STATUS_META: Record<TaskStatus, { color: string; label: string }> = {
  CREATED: { color: 'default', label: '待启动' },
  QUEUED: { color: 'default', label: '排队中' },
  STARTING: { color: 'processing', label: '启动中' },
  DOWNLOADING: { color: 'processing', label: '下载中' },
  VERIFYING: { color: 'processing', label: '校验中' },
  COMPLETED: { color: 'success', label: '已完成' },
  PAUSING: { color: 'warning', label: '暂停中' },
  PAUSED: { color: 'warning', label: '已暂停' },
  RETRY_WAIT: { color: 'warning', label: '等待重试' },
  REPAIRING: { color: 'processing', label: '修复中' },
  FAILED: { color: 'error', label: '失败' },
  DELETING: { color: 'default', label: '删除中' },
  DELETED: { color: 'default', label: '已删除' },
};

const STATUS_OPTIONS: { value: TaskStatus; label: string }[] = (
  ['DOWNLOADING', 'QUEUED', 'COMPLETED', 'PAUSED', 'FAILED', 'CREATED'] as TaskStatus[]
).map((s) => ({ value: s, label: STATUS_META[s].label }));

/** 状态机驱动的合法命令（规格 §5.1） */
function allowedActions(t: TaskView): { pause: boolean; resume: boolean; retry: boolean; del: boolean } {
  switch (t.status) {
    case 'QUEUED':
    case 'STARTING':
    case 'DOWNLOADING':
    case 'VERIFYING':
      return { pause: true, resume: false, retry: false, del: true };
    case 'PAUSING':
      return { pause: false, resume: false, retry: false, del: true };
    case 'PAUSED':
    case 'CREATED':
      return { pause: false, resume: true, retry: false, del: true };
    case 'FAILED':
      return { pause: false, resume: false, retry: true, del: true };
    case 'COMPLETED':
      return { pause: false, resume: false, retry: false, del: true };
    default:
      return { pause: false, resume: false, retry: false, del: false };
  }
}

export default function TasksPage() {
  const navigate = useNavigate();
  const token = useAuth((s) => s.token);
  const [data, setData] = useState<PageView<TaskView> | null>(null);
  const [loading, setLoading] = useState(false);
  const [status, setStatus] = useState<TaskStatus | undefined>();
  const [page, setPage] = useState(1);
  const timer = useRef<ReturnType<typeof setInterval>>();

  const load = useCallback(async (p: number, s?: TaskStatus) => {
    setLoading(true);
    try {
      const { data: result } = await client.get<PageView<TaskView>>('/tasks', {
        params: { page: p, size: 20, status: s },
      });
      setData(result);
    } catch (err: any) {
      message.error(err.response?.data?.message ?? '加载失败');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load(page, status);
    timer.current = setInterval(() => {
      if (document.visibilityState === 'visible') {
        load(page, status);
      }
    }, 3000);
    return () => clearInterval(timer.current);
  }, [page, status, load]);

  const act = async (fn: () => Promise<unknown>, ok: string) => {
    try {
      await fn();
      message.success(ok);
      await load(page, status);
    } catch (err: any) {
      message.error(err.response?.data?.message ?? '操作失败');
    }
  };

  const editLimit = (t: TaskView) => {
    let limit = t.speedLimit ? Math.round(t.speedLimit / 1024) : 0;
    Modal.confirm({
      title: '设置任务限速（KB/s，0 表示不限速）',
      content: (
        <div style={{ marginTop: 8 }}>
          <InputNumber
            min={0}
            style={{ width: '100%' }}
            defaultValue={limit}
            addonAfter="KB/s"
            onChange={(v) => {
              limit = v ?? 0;
            }}
          />
        </div>
      ),
      onOk: () =>
        act(
          () => client.patch(`/tasks/${t.id}`, { speedLimit: limit > 0 ? limit * 1024 : null }),
          '已更新限速设置',
        ),
    });
  };

  const confirmDelete = (t: TaskView) => {
    let removeOwnExport = false;
    Modal.confirm({
      title: '确认删除该下载任务？',
      content: (
        <Space direction="vertical" size={8} style={{ marginTop: 4 }}>
          <Typography.Text type="secondary" style={{ fontSize: 13 }}>
            仅移除当前账号下的任务引用与状态，不会中断其他用户的下载会话。
          </Typography.Text>
          <Checkbox
            onChange={(e) => {
              removeOwnExport = e.target.checked;
            }}
          >
            同时删除我已导出的文件副本
          </Checkbox>
        </Space>
      ),
      okText: '确认删除',
      okButtonProps: { danger: true },
      onOk: () =>
        act(
          () => client.delete(`/tasks/${t.id}`, { params: { removeOwnExport } }),
          removeOwnExport ? '已删除任务及导出文件' : '已删除任务（保留导出文件）',
        ),
    });
  };

  const handleDownload = async (t: TaskView) => {
    try {
      const { data: exports } = await client.get<ExportView[]>(`/tasks/${t.id}/exports`);
      const ready = exports.filter((e) => e.state === 'READY');
      if (ready.length === 1) {
        const href = `/api/v1/tasks/${t.id}/exports/${ready[0].id}/content${token ? `?token=${encodeURIComponent(token)}` : ''}`;
        window.open(href, '_blank');
      } else if (ready.length > 1) {
        navigate(PATHS.taskDetail(t.id));
      } else {
        message.info('请在详情页点击导出文件，再下载到本地');
        navigate(PATHS.taskDetail(t.id));
      }
    } catch (err: any) {
      message.error(err.response?.data?.message ?? '获取导出文件失败');
    }
  };

  return (
    <div>
      <div className="page-header">
        <div>
          <h1 className="page-title">下载任务</h1>
          <p className="page-subtitle">实时追踪分块校验进度，校验完成后可将文件直接下载到本地</p>
        </div>
      </div>

      <div className="surface-card">
        <div className="toolbar-row">
          <Space size={8}>
            <Select
              allowClear
              placeholder="全部状态"
              style={{ width: 148 }}
              options={STATUS_OPTIONS}
              value={status}
              onChange={(v) => {
                setPage(1);
                setStatus(v);
              }}
            />
            <Button icon={<ReloadOutlined />} onClick={() => load(page, status)}>
              刷新
            </Button>
          </Space>
          <Typography.Text type="secondary" className="tabular-nums" style={{ fontSize: 12 }}>
            共 {data?.total ?? 0} 个任务 · 每 3 秒自动同步
          </Typography.Text>
        </div>

        <Table<TaskView>
          rowKey="id"
          size="small"
          loading={loading}
          dataSource={data?.items ?? []}
          locale={{
            emptyText: (
              <Empty
                image={Empty.PRESENTED_IMAGE_SIMPLE}
                description={
                  <span>
                    暂无下载任务，请先前往 <Link to={PATHS.PARSES}>资源解析</Link> 创建任务
                  </span>
                }
              />
            ),
          }}
          pagination={{
            current: page,
            total: data?.total ?? 0,
            pageSize: 20,
            onChange: setPage,
            showSizeChanger: false,
            showTotal: (t) => `共 ${t} 条`,
            style: { padding: '0 16px', margin: '12px 0' },
          }}
          columns={[
            {
              title: '状态',
              dataIndex: 'status',
              width: 110,
              render: (s: TaskStatus, t) => {
                const meta = STATUS_META[s] ?? { color: 'default', label: s };
                return (
                  <Tag
                    color={meta.color}
                    style={{ cursor: 'pointer', margin: 0 }}
                    onClick={() => navigate(PATHS.taskDetail(t.id))}
                  >
                    {meta.label}
                  </Tag>
                );
              },
            },
            {
              title: '校验进度',
              key: 'progress',
              width: 220,
              render: (_, t) => (
                <Progress
                  percent={Math.round(t.progressPercent * 10) / 10}
                  size="small"
                  style={{ margin: 0 }}
                  status={t.status === 'FAILED' ? 'exception' : t.status === 'COMPLETED' ? 'success' : 'active'}
                />
              ),
            },
            {
              title: '已验证 / 已选大小',
              key: 'bytes',
              width: 190,
              render: (_, t) => (
                <span className="tabular-nums">
                  <strong style={{ fontWeight: 600 }}>{formatBytes(t.verifiedBytes)}</strong>
                  <span style={{ color: 'var(--color-text-muted)' }}> / {formatBytes(t.selectedBytes)}</span>
                </span>
              ),
            },
            {
              title: '限速',
              dataIndex: 'speedLimit',
              width: 100,
              render: (v: number | null) => (
                <span className="tabular-nums" style={{ color: v ? 'var(--color-text-primary)' : 'var(--color-text-muted)' }}>
                  {v ? `${formatBytes(v)}/s` : '不限'}
                </span>
              ),
            },
            {
              title: '错误信息',
              dataIndex: 'errorMessage',
              ellipsis: true,
              render: (v: string | null) =>
                v ? <Typography.Text type="danger" style={{ fontSize: 12 }}>{v}</Typography.Text> : <Typography.Text type="secondary">-</Typography.Text>,
            },
            {
              title: '开始时间',
              dataIndex: 'startedAt',
              width: 168,
              render: (v: string | null) => (
                <span className="tabular-nums" style={{ color: 'var(--color-text-secondary)', fontSize: 12 }}>
                  {v ? formatTime(v) : '-'}
                </span>
              ),
            },
            {
              title: '操作',
              key: 'ops',
              width: 250,
              align: 'right',
              render: (_, t) => {
                const a = allowedActions(t);
                const isTerminal = t.status === 'COMPLETED' || t.status === 'FAILED' || t.status === 'DELETED';
                return (
                  <Space size={6}>
                    {t.status === 'COMPLETED' && (
                      <Button
                        type="primary"
                        size="small"
                        icon={<DownloadOutlined />}
                        onClick={() => handleDownload(t)}
                      >
                        下载文件
                      </Button>
                    )}
                    {a.pause && (
                      <Button
                        size="small"
                        onClick={() => act(() => client.post(`/tasks/${t.id}/pause`), '已暂停')}
                      >
                        暂停
                      </Button>
                    )}
                    {a.resume && (
                      <Button
                        type="primary"
                        ghost
                        size="small"
                        onClick={() => act(() => client.post(`/tasks/${t.id}/resume`), '已恢复')}
                      >
                        恢复
                      </Button>
                    )}
                    {a.retry && (
                      <Button
                        type="primary"
                        ghost
                        size="small"
                        onClick={() => act(() => client.post(`/tasks/${t.id}/retry`), '已重试')}
                      >
                        重试
                      </Button>
                    )}
                    {!isTerminal && (
                      <Button size="small" type="text" onClick={() => editLimit(t)}>
                        限速
                      </Button>
                    )}
                    <Button size="small" type="text" onClick={() => navigate(PATHS.taskDetail(t.id))}>
                      详情
                    </Button>
                    {a.del && (
                      <Button size="small" type="text" danger onClick={() => confirmDelete(t)}>
                        删除
                      </Button>
                    )}
                  </Space>
                );
              },
            },
          ]}
        />
      </div>
    </div>
  );
}
