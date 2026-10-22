import { Button, Space, Typography } from 'antd';
import { ArrowLeftOutlined, CompassOutlined } from '@ant-design/icons';
import { useLocation, useNavigate } from 'react-router-dom';
import { PATHS } from '@/router/paths';

export default function NotFoundPage() {
  const navigate = useNavigate();
  const location = useLocation();

  return (
    <div className="detail-container" style={{ paddingTop: 48 }}>
      <div
        className="surface-card"
        style={{
          padding: '48px 32px',
          textAlign: 'center',
          maxWidth: 480,
          margin: '0 auto',
        }}
      >
        <div
          className="mono-text"
          style={{
            display: 'inline-block',
            padding: '4px 10px',
            borderRadius: 6,
            background: '#f1f5f9',
            color: 'var(--color-text-secondary)',
            fontWeight: 600,
            marginBottom: 12,
          }}
        >
          404 NOT FOUND
        </div>
        <h1 className="page-title" style={{ marginBottom: 8 }}>
          页面不存在或已移除
        </h1>
        <Typography.Paragraph
          style={{ fontSize: 14, color: 'var(--color-text-muted)', marginBottom: 24 }}
        >
          无法匹配路径 <code className="mono-text">{location.pathname}</code>，请检查访问地址是否正确。
        </Typography.Paragraph>
        <Space size={12}>
          <Button type="primary" icon={<CompassOutlined />} onClick={() => navigate(PATHS.PARSES)}>
            返回资源解析
          </Button>
          <Button icon={<ArrowLeftOutlined />} onClick={() => navigate(-1)}>
            返回上一页
          </Button>
        </Space>
      </div>
    </div>
  );
}
