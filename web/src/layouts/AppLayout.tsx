import { Suspense, useEffect } from 'react';
import { Button, Space, Spin, Tag, Typography } from 'antd';
import {
  CloudDownloadOutlined,
  CompassOutlined,
  LogoutOutlined,
  UserOutlined,
} from '@ant-design/icons';
import { Link, NavLink, Outlet, useMatches, useNavigate } from 'react-router-dom';
import { useAuth } from '@/store/auth';
import { PATHS, type RouteMeta } from '@/router/paths';

const NAV_ITEMS = [
  {
    to: PATHS.PARSES,
    label: '资源解析',
    icon: <CompassOutlined />,
  },
  {
    to: PATHS.TASKS,
    label: '下载任务',
    icon: <CloudDownloadOutlined />,
  },
];

export default function AppLayout() {
  const user = useAuth((s) => s.user);
  const clear = useAuth((s) => s.clear);
  const navigate = useNavigate();
  const matches = useMatches();

  useEffect(() => {
    const activeMeta = [...matches]
      .reverse()
      .map((m) => m.handle as RouteMeta | undefined)
      .find((meta) => meta?.title);

    document.title = activeMeta?.title
      ? `${activeMeta.title} · Magnet 下载平台`
      : 'Magnet 下载平台';
  }, [matches]);

  return (
    <div style={{ minHeight: '100vh', display: 'flex', flexDirection: 'column' }}>
      <header className="app-header">
        <Space size={24} align="center">
          <Link
            to={PATHS.PARSES}
            style={{ display: 'flex', alignItems: 'center', gap: 8, textDecoration: 'none' }}
          >
            <div
              style={{
                width: 28,
                height: 28,
                borderRadius: 8,
                background: 'var(--color-primary)',
                color: '#fff',
                display: 'inline-flex',
                alignItems: 'center',
                justifyContent: 'center',
                fontWeight: 700,
                fontSize: 14,
              }}
            >
              M
            </div>
            <Typography.Text
              style={{ fontSize: 16, fontWeight: 600, color: 'var(--color-text-primary)' }}
            >
              Magnet 下载平台
            </Typography.Text>
          </Link>

          <nav style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
            {NAV_ITEMS.map((item) => (
              <NavLink
                key={item.to}
                to={item.to}
                className={({ isActive }) => `app-nav-link ${isActive ? 'is-active' : ''}`}
              >
                {item.icon}
                <span>{item.label}</span>
              </NavLink>
            ))}
          </nav>
        </Space>

        <Space size={12} align="center">
          <Tag icon={<UserOutlined />} bordered={false} style={{ margin: 0, padding: '2px 8px' }}>
            {user?.username}
          </Tag>
          <Button
            size="small"
            type="text"
            icon={<LogoutOutlined />}
            onClick={() => {
              clear();
              navigate(PATHS.LOGIN, { replace: true });
            }}
          >
            退出
          </Button>
        </Space>
      </header>

      <main className="app-main">
        <Suspense
          fallback={
            <div style={{ padding: 64, textAlign: 'center' }}>
              <Spin size="default" />
            </div>
          }
        >
          <Outlet />
        </Suspense>
      </main>
    </div>
  );
}
