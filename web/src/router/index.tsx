import { Suspense, lazy } from 'react';
import { Spin } from 'antd';
import { Navigate, createBrowserRouter, type RouteObject } from 'react-router-dom';
import AppLayout from '@/layouts/AppLayout';
import { GuestOnly, RequireAuth } from '@/router/guards';
import { PATHS, type RouteMeta } from '@/router/paths';

const LoginPage = lazy(() => import('@/pages/LoginPage'));
const ParsesPage = lazy(() => import('@/pages/ParsesPage'));
const ParseDetailPage = lazy(() => import('@/pages/ParseDetailPage'));
const TasksPage = lazy(() => import('@/pages/TasksPage'));
const TaskDetailPage = lazy(() => import('@/pages/TaskDetailPage'));
const NotFoundPage = lazy(() => import('@/pages/NotFoundPage'));

function FullscreenFallback() {
  return (
    <div
      style={{
        minHeight: '100vh',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
      }}
    >
      <Spin size="default" />
    </div>
  );
}

export const routes: RouteObject[] = [
  {
    element: <GuestOnly />,
    children: [
      {
        path: PATHS.LOGIN,
        element: (
          <Suspense fallback={<FullscreenFallback />}>
            <LoginPage />
          </Suspense>
        ),
        handle: { title: '登录' } satisfies RouteMeta,
      },
    ],
  },
  {
    path: PATHS.ROOT,
    element: <RequireAuth />,
    children: [
      {
        element: <AppLayout />,
        children: [
          {
            index: true,
            element: <Navigate to={PATHS.PARSES} replace />,
          },
          {
            path: PATHS.PARSES,
            element: <ParsesPage />,
            handle: { title: '资源解析', section: 'parses' } satisfies RouteMeta,
          },
          {
            path: PATHS.PARSE_DETAIL,
            element: <ParseDetailPage />,
            handle: { title: '解析详情', section: 'parses' } satisfies RouteMeta,
          },
          {
            path: PATHS.TASKS,
            element: <TasksPage />,
            handle: { title: '下载任务', section: 'tasks' } satisfies RouteMeta,
          },
          {
            path: PATHS.TASK_DETAIL,
            element: <TaskDetailPage />,
            handle: { title: '任务详情', section: 'tasks' } satisfies RouteMeta,
          },
          {
            path: '*',
            element: <NotFoundPage />,
            handle: { title: '页面未找到' } satisfies RouteMeta,
          },
        ],
      },
    ],
  },
];

export const router = createBrowserRouter(routes);
