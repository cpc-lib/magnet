import { useEffect, useMemo, useState } from 'react';
import {
  Button,
  Card,
  Checkbox,
  Descriptions,
  Empty,
  Input,
  message,
  Modal,
  Space,
  Spin,
  Tag,
  Tree,
  Typography,
} from 'antd';
import { ArrowLeftOutlined, DownloadOutlined } from '@ant-design/icons';
import { useNavigate, useParams } from 'react-router-dom';
import client from '@/api/client';
import type { CreateTaskRequest, FileView, ParseJobView, ParseStatus, ResourceDetailView, TaskView } from '@/api/types';
import { PATHS } from '@/router/paths';
import { formatBytes, formatTime } from '@/utils/format';

const STATUS_TAG: Record<ParseStatus, { color: string; label: string }> = {
  PENDING: { color: 'default', label: '等待中' },
  RESOLVING: { color: 'processing', label: '解析中' },
  READY: { color: 'success', label: '已就绪' },
  FAILED: { color: 'error', label: '失败' },
  TIMEOUT: { color: 'warning', label: '超时' },
};

interface TreeNode {
  key: string;
  title: string;
  children?: TreeNode[];
  isLeaf?: boolean;
}

function buildTree(files: FileView[]): TreeNode[] {
  interface Dir {
    children: Map<string, Dir>;
    files: FileView[];
  }
  const root: Dir = { children: new Map(), files: [] };
  for (const f of files) {
    const parts = f.relativePath.split('/');
    let dir = root;
    for (let i = 0; i < parts.length - 1; i += 1) {
      if (!dir.children.has(parts[i])) {
        dir.children.set(parts[i], { children: new Map(), files: [] });
      }
      dir = dir.children.get(parts[i])!;
    }
    dir.files.push(f);
  }
  const toNodes = (dir: Dir, prefix: string): TreeNode[] => {
    const nodes: TreeNode[] = [];
    for (const [name, child] of dir.children) {
      const path = prefix ? `${prefix}/${name}` : name;
      nodes.push({ key: path, title: `${name}/`, children: toNodes(child, path) });
    }
    for (const f of dir.files) {
      const name = f.relativePath.split('/').pop()!;
      nodes.push({
        key: f.relativePath,
        title: `${name}（${formatBytes(f.sizeBytes)}）`,
        isLeaf: true,
      });
    }
    return nodes;
  };
  return toNodes(root, '');
}

export default function ParseDetailPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const [job, setJob] = useState<ParseJobView | null>(null);
  const [detail, setDetail] = useState<ResourceDetailView | null>(null);
  const [loading, setLoading] = useState(true);
  const [checkedKeys, setCheckedKeys] = useState<string[]>([]);
  const [creating, setCreating] = useState(false);

  useEffect(() => {
    (async () => {
      setLoading(true);
      try {
        const { data: jobData } = await client.get<ParseJobView>(`/parses/${id}`);
        setJob(jobData);
        if (jobData.status === 'READY' && jobData.resourceId) {
          const { data: detailData } = await client.get<ResourceDetailView>(`/resources/${jobData.resourceId}/files`);
          setDetail(detailData);
          setCheckedKeys(detailData.files.map((f) => f.relativePath));
        }
      } catch (err: any) {
        message.error(err.response?.data?.message ?? '加载失败');
      } finally {
        setLoading(false);
      }
    })();
  }, [id]);

  const treeData = useMemo(() => (detail ? buildTree(detail.files) : []), [detail]);

  const pathToIndex = useMemo(() => {
    const m = new Map<string, number>();
    detail?.files.forEach((f) => m.set(f.relativePath, f.fileIndex));
    return m;
  }, [detail]);

  const selectedIndices = checkedKeys
    .map((k) => pathToIndex.get(k))
    .filter((v): v is number => v !== undefined);

  const selectedBytes = useMemo(() => {
    if (!detail) return 0;
    const selectedSet = new Set(selectedIndices);
    return detail.files
      .filter((f) => selectedSet.has(f.fileIndex))
      .reduce((acc, f) => acc + f.sizeBytes, 0);
  }, [detail, selectedIndices]);

  const createTask = () => {
    let subdirectory = '';
    let startImmediately = true;
    Modal.confirm({
      title: `创建下载任务（已选 ${selectedIndices.length} 个文件，共 ${formatBytes(selectedBytes)}）`,
      content: (
        <Space direction="vertical" size={12} style={{ width: '100%', marginTop: 8 }}>
          <Input
            placeholder="目标子目录（可选，留空为根目录）"
            onChange={(e) => {
              subdirectory = e.target.value.trim();
            }}
          />
          <Checkbox
            defaultChecked
            onChange={(e) => {
              startImmediately = e.target.checked;
            }}
          >
            创建后立即开始下载
          </Checkbox>
        </Space>
      ),
      onOk: async () => {
        setCreating(true);
        try {
          const body: CreateTaskRequest = {
            parseId: id!,
            fileIndices: selectedIndices,
            startImmediately,
            ...(subdirectory ? { targetSubdirectory: subdirectory } : {}),
          };
          const { data: created } = await client.post<TaskView>('/tasks', body);
          message.success('下载任务已创建');
          navigate(created?.id ? PATHS.taskDetail(created.id) : PATHS.TASKS);
        } catch (err: any) {
          message.error(err.response?.data?.message ?? '创建失败');
        } finally {
          setCreating(false);
        }
      },
    });
  };

  if (loading) {
    return (
      <div className="detail-container" style={{ padding: '64px 0', textAlign: 'center' }}>
        <Spin size="large" />
      </div>
    );
  }
  if (!job) {
    return (
      <div className="detail-container">
        <Empty description="未找到解析记录" />
      </div>
    );
  }

  const r = detail?.resource;
  return (
    <div className="detail-container">
      <div className="page-header">
        <Space size={12} align="center">
          <Button icon={<ArrowLeftOutlined />} onClick={() => navigate(PATHS.PARSES)}>
            返回
          </Button>
          <div>
            <Space size={8} align="center">
              <h1 className="page-title">{r?.name || job.displayHint || '解析详情'}</h1>
              <Tag color={STATUS_TAG[job.status].color}>{STATUS_TAG[job.status].label}</Tag>
            </Space>
            <p className="page-subtitle">任务 ID：{job.id}</p>
          </div>
        </Space>

        {r && (
          <Button
            type="primary"
            icon={<DownloadOutlined />}
            disabled={selectedIndices.length === 0}
            loading={creating}
            onClick={createTask}
          >
            创建下载任务（{selectedIndices.length} 项 · {formatBytes(selectedBytes)}）
          </Button>
        )}
      </div>

      <Space direction="vertical" size={16} style={{ width: '100%' }}>
        <Card title="解析概况" size="small">
          <Descriptions column={2} size="small">
            <Descriptions.Item label="输入来源">
              <Tag bordered={false}>{job.inputType}</Tag>
            </Descriptions.Item>
            <Descriptions.Item label="状态">
              <Tag color={STATUS_TAG[job.status].color}>{STATUS_TAG[job.status].label}</Tag>
            </Descriptions.Item>
            <Descriptions.Item label="创建时间">
              <span className="tabular-nums">{formatTime(job.createdAt)}</span>
            </Descriptions.Item>
            <Descriptions.Item label="更新时间">
              <span className="tabular-nums">{formatTime(job.updatedAt)}</span>
            </Descriptions.Item>
            {job.errorMessage && (
              <Descriptions.Item label="错误信息" span={2}>
                <Typography.Text type="danger">{job.errorMessage}</Typography.Text>
              </Descriptions.Item>
            )}
          </Descriptions>
        </Card>

        {r && (
          <>
            <Card title="种子元数据" size="small">
              <Descriptions column={2} size="small">
                <Descriptions.Item label="资源名称" span={2}>
                  <Typography.Text strong>{r.name ?? '-'}</Typography.Text>
                </Descriptions.Item>
                <Descriptions.Item label="协议版本">
                  <Tag bordered={false}>{r.versionType}</Tag>
                </Descriptions.Item>
                <Descriptions.Item label="文件总数">
                  <span className="tabular-nums">{r.fileCount ?? '-'}</span>
                </Descriptions.Item>
                <Descriptions.Item label="总大小">
                  <span className="tabular-nums">{formatBytes(r.totalBytes)}</span>
                </Descriptions.Item>
                <Descriptions.Item label="分块大小">
                  <span className="tabular-nums">{r.pieceLength ? formatBytes(r.pieceLength) : '-'}</span>
                </Descriptions.Item>
                <Descriptions.Item label="BTIH (v1)" span={2}>
                  <span className="mono-text">{r.btih ?? '-'}</span>
                </Descriptions.Item>
                {r.btmh && (
                  <Descriptions.Item label="BTMH (v2)" span={2}>
                    <span className="mono-text">{r.btmh}</span>
                  </Descriptions.Item>
                )}
                <Descriptions.Item label="Tracker 数量">
                  <span className="tabular-nums">{r.trackers.length}</span>
                </Descriptions.Item>
                <Descriptions.Item label="元数据来源">{r.metadataSource ?? '-'}</Descriptions.Item>
              </Descriptions>
            </Card>

            <Card
              title={`选择要下载的文件（已选 ${selectedIndices.length} / ${detail.files.length}）`}
              size="small"
              extra={
                <Space size={8}>
                  <Button
                    type="link"
                    size="small"
                    onClick={() => setCheckedKeys(detail.files.map((f) => f.relativePath))}
                  >
                    全选
                  </Button>
                  <Button type="link" size="small" onClick={() => setCheckedKeys([])}>
                    清空
                  </Button>
                </Space>
              }
            >
              <Tree
                treeData={treeData}
                defaultExpandAll
                showLine
                selectable={false}
                checkable
                checkedKeys={checkedKeys}
                onCheck={(keys) => setCheckedKeys(keys as string[])}
              />
            </Card>
          </>
        )}
      </Space>
    </div>
  );
}
