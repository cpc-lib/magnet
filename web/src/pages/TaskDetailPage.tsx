import { useCallback, useEffect, useRef, useState } from 'react';
import {
  Button,
  Card,
  Checkbox,
  Descriptions,
  Empty,
  message,
  Modal,
  Progress,
  Space,
  Table,
  Tag,
  Typography,
} from 'antd';
import { ArrowLeftOutlined, DownloadOutlined, ReloadOutlined } from '@ant-design/icons';
import { useNavigate, useParams } from 'react-router-dom';
import client from '@/api/client';
import { PATHS } from '@/router/paths';
import { useAuth } from '@/store/auth';
import type { ExportView, TaskFileDetail, TaskStatus, TaskView } from '@/api/types';
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

export default function TaskDetailPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const token = useAuth((s) => s.token);
  const [task, setTask] = useState<TaskView | null>(null);
  const [files, setFiles] = useState<TaskFileDetail[]>([]);
  const [exports, setExports] = useState<ExportView[]>([]);
  const [loading, setLoading] = useState(false);
  const timer = useRef<ReturnType<typeof setInterval>>();

  const loadAll = useCallback(async () => {
    if (!id) return;
    setLoading(true);
    try {
      const [taskRes, filesRes, exportsRes] = await Promise.all([
        client.get<TaskView>(`/tasks/${id}`),
        client.get<TaskFileDetail[]>(`/tasks/${id}/files`),
        client.get<ExportView[]>(`/tasks/${id}/exports`),
      ]);
      setTask(taskRes.data);
      setFiles(filesRes.data);
      setExports(exportsRes.data);
    } catch (err: any) {
      message.error(err.response?.data?.message ?? '加载任务详情失败');
    } finally {
      setLoading(false);
    }
  }, [id]);

  useEffect(() => {
    loadAll();
  }, [loadAll]);

  useEffect(() => {
    if (!id || !task) return;
    const isTerminal = task.status === 'COMPLETED' || task.status === 'FAILED' || task.status === 'DELETED';
    if (isTerminal) {
      return;
    }

    let es: EventSource | null = null;
    if (typeof EventSource !== 'undefined' && token) {
      es = new EventSource(`/api/v1/tasks/${id}/events?token=${encodeURIComponent(token)}`);
      es.addEventListener('SNAPSHOT', () => {
        loadAll();
      });
      es.onerror = () => {
        es?.close();
      };
    }

    timer.current = setInterval(() => {
      if (document.visibilityState === 'visible') {
        loadAll();
      }
    }, 3000);

    return () => {
      es?.close();
      clearInterval(timer.current);
    };
  }, [id, task?.status, token, loadAll]);

  const act = async (fn: () => Promise<unknown>, ok: string) => {
    try {
      await fn();
      message.success(ok);
      await loadAll();
    } catch (err: any) {
      message.error(err.response?.data?.message ?? '操作失败');
    }
  };

  const confirmDelete = () => {
    if (!task) return;
    let removeOwnExport = false;
    Modal.confirm({
      title: '确认删除该下载任务？',
      content: (
        <Space direction="vertical" size={8} style={{ marginTop: 4 }}>
          <Typography.Text type="secondary" style={{ fontSize: 13 }}>
            删除任务不会影响其他正在共享该资源的用户。
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
      onOk: async () => {
        await client.delete(`/tasks/${task.id}`, { params: { removeOwnExport } });
        message.success(removeOwnExport ? '任务及导出文件已删除' : '任务已删除（保留导出文件）');
        navigate(PATHS.TASKS);
      },
    });
  };

  const exportByIndex = new Map<number, ExportView>(exports.map((e) => [e.fileIndex, e]));
  const actions = task ? allowedActions(task) : { pause: false, resume: false, retry: false, del: false };
  const statusMeta = task ? (STATUS_META[task.status] ?? { color: 'default', label: task.status }) : null;

  return (
    <div className="detail-container">
      <div className="page-header">
        <Space size={12} align="center">
          <Button icon={<ArrowLeftOutlined />} onClick={() => navigate(PATHS.TASKS)}>
            返回
          </Button>
          <div>
            <Space size={8} align="center">
              <h1 className="page-title">任务详情</h1>
              {statusMeta && <Tag color={statusMeta.color}>{statusMeta.label}</Tag>}
            </Space>
            {task && <p className="page-subtitle">任务 ID：{task.id}</p>}
          </div>
        </Space>

        <Space size={8}>
          <Button icon={<ReloadOutlined />} loading={loading} onClick={loadAll}>
            刷新
          </Button>
          <Button
            disabled={!actions.pause || !task}
            onClick={() => task && act(() => client.post(`/tasks/${task.id}/pause`), '已暂停')}
          >
            暂停
          </Button>
          <Button
            type="primary"
            ghost
            disabled={!actions.resume || !task}
            onClick={() => task && act(() => client.post(`/tasks/${task.id}/resume`), '已恢复')}
          >
            恢复下载
          </Button>
          <Button
            type="primary"
            ghost
            disabled={!actions.retry || !task}
            onClick={() => task && act(() => client.post(`/tasks/${task.id}/retry`), '已重试')}
          >
            重试任务
          </Button>
          <Button danger disabled={!actions.del || !task} onClick={confirmDelete}>
            删除
          </Button>
        </Space>
      </div>

      <Space direction="vertical" size={16} style={{ width: '100%' }}>
        {task && (
          <Card title="进度与状态" size="small">
            <Descriptions column={3} size="small">
              <Descriptions.Item label="已验证 / 已选">
                <span className="tabular-nums">
                  <strong>{formatBytes(task.verifiedBytes)}</strong> / {formatBytes(task.selectedBytes)}
                </span>
              </Descriptions.Item>
              <Descriptions.Item label="整体校验进度" span={2}>
                <Progress
                  percent={Math.round(task.progressPercent * 10) / 10}
                  size="small"
                  style={{ maxWidth: 280, margin: 0 }}
                  status={task.status === 'FAILED' ? 'exception' : task.status === 'COMPLETED' ? 'success' : 'active'}
                />
              </Descriptions.Item>
              <Descriptions.Item label="开始时间">
                <span className="tabular-nums">{task.startedAt ? formatTime(task.startedAt) : '-'}</span>
              </Descriptions.Item>
              <Descriptions.Item label="完成时间">
                <span className="tabular-nums">{task.completedAt ? formatTime(task.completedAt) : '-'}</span>
              </Descriptions.Item>
              <Descriptions.Item label="目标子目录">{task.targetSubdirectory || '根目录'}</Descriptions.Item>
              {task.errorMessage && (
                <Descriptions.Item label="错误信息" span={3}>
                  <Typography.Text type="danger">{task.errorMessage}</Typography.Text>
                </Descriptions.Item>
              )}
            </Descriptions>
          </Card>
        )}

        <Card title="文件清单与本地下载" size="small">
          <Table<TaskFileDetail>
            rowKey="fileIndex"
            size="small"
            loading={loading}
            dataSource={files}
            locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无文件记录" /> }}
            pagination={false}
            columns={[
              {
                title: '#',
                dataIndex: 'fileIndex',
                width: 56,
                render: (idx: number) => <span className="tabular-nums" style={{ color: 'var(--color-text-muted)' }}>{idx}</span>,
              },
              {
                title: '文件路径',
                dataIndex: 'relativePath',
                ellipsis: true,
                render: (v: string, r) => (
                  <Typography.Text strong={r.selected}>
                    {v || exportByIndex.get(r.fileIndex)?.relativePath || `文件 #${r.fileIndex}`}
                  </Typography.Text>
                ),
              },
              {
                title: '文件校验进度',
                key: 'fileProgress',
                width: 180,
                render: (_, r) => {
                  if (!r.selected) {
                    return <Typography.Text type="secondary">-</Typography.Text>;
                  }
                  const exp = exportByIndex.get(r.fileIndex);
                  const total = r.sizeBytes || exp?.bytes || 0;
                  const verified = exp?.state === 'READY' ? exp.bytes : r.verifiedBytes;
                  const pct = total > 0 ? Math.min(100, Math.round((verified * 1000) / total) / 10) : 0;
                  return (
                    <Progress
                      percent={pct}
                      size="small"
                      style={{ margin: 0 }}
                      status={exp?.state === 'FAILED' ? 'exception' : pct >= 100 ? 'success' : 'active'}
                    />
                  );
                },
              },
              {
                title: '已验证 / 大小',
                key: 'size',
                width: 200,
                render: (_, r) => {
                  const exp = exportByIndex.get(r.fileIndex);
                  const total = r.sizeBytes || exp?.bytes || 0;
                  const verified = exp?.state === 'READY' ? exp.bytes : r.verifiedBytes;
                  return (
                    <span className="tabular-nums">
                      {formatBytes(verified)} / {formatBytes(total)}
                    </span>
                  );
                },
              },
              {
                title: '导出状态',
                key: 'exportState',
                width: 110,
                render: (_, r) => {
                  if (!r.selected) {
                    return <Tag bordered={false}>未选</Tag>;
                  }
                  const exp = exportByIndex.get(r.fileIndex);
                  const state = exp?.state ?? r.exportState;
                  if (state === 'READY') return <Tag color="success">可下载</Tag>;
                  if (state === 'FAILED') return <Tag color="error">导出失败</Tag>;
                  return <Tag color="default">准备中</Tag>;
                },
              },
              {
                title: '操作',
                key: 'download',
                width: 140,
                align: 'right',
                render: (_, r) => {
                  const exp = exportByIndex.get(r.fileIndex);
                  if (!exp || exp.state !== 'READY' || !id) {
                    return (
                      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                        {exp?.errorMessage ?? (r.selected ? '等待完成校验' : '-')}
                      </Typography.Text>
                    );
                  }
                  const href = `/api/v1/tasks/${id}/exports/${exp.id}/content${token ? `?token=${encodeURIComponent(token)}` : ''}`;
                  return (
                    <Button type="primary" size="small" icon={<DownloadOutlined />} href={href} target="_blank">
                      下载到本地
                    </Button>
                  );
                },
              },
            ]}
          />
        </Card>
      </Space>
    </div>
  );
}
