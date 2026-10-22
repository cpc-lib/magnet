import React from 'react';
import { createRoot } from 'react-dom/client';
import { ConfigProvider } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import dayjs from 'dayjs';
import 'dayjs/locale/zh-cn';
import App from '@/App';
import '@/styles.css';

dayjs.locale('zh-cn');

createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <ConfigProvider
      locale={zhCN}
      theme={{
        token: {
          colorPrimary: '#1677ff',
          colorSuccess: '#16a34a',
          colorWarning: '#d97706',
          colorError: '#dc2626',
          colorTextBase: '#0f172a',
          colorBgLayout: '#f8fafc',
          borderRadius: 8,
          fontSize: 14,
        },
        components: {
          Card: {
            headerBg: 'transparent',
            boxShadowTertiary: '0 2px 8px rgba(15, 23, 42, 0.04)',
          },
          Table: {
            headerBg: '#f8fafc',
            headerColor: '#475569',
            rowHoverBg: '#f8fafc',
          },
          Button: {
            fontWeight: 500,
          },
        },
      }}
    >
      <App />
    </ConfigProvider>
  </React.StrictMode>,
);
