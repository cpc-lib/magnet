import { useState } from 'react';
import { Form, Input, Button, Tabs, Typography, message } from 'antd';
import { LockOutlined, UserOutlined } from '@ant-design/icons';
import { useLocation, useNavigate } from 'react-router-dom';
import client from '@/api/client';
import type { AuthResponse } from '@/api/types';
import { PATHS } from '@/router/paths';
import { useAuth } from '@/store/auth';

interface LoginForm {
  username: string;
  password: string;
}

interface RegisterForm extends LoginForm {
  confirmPassword: string;
}

export default function LoginPage() {
  const [tab, setTab] = useState('login');
  const [loading, setLoading] = useState(false);
  const setAuth = useAuth((s) => s.setAuth);
  const navigate = useNavigate();
  const location = useLocation();

  const submit = async (path: string, values: LoginForm) => {
    setLoading(true);
    try {
      const { data } = await client.post<AuthResponse>(path, values);
      setAuth(data.token, data.user);
      const fromPath = (location.state as { from?: { pathname?: string } } | null)?.from?.pathname;
      const target = fromPath && fromPath !== PATHS.LOGIN ? fromPath : PATHS.PARSES;
      navigate(target, { replace: true });
    } catch (err: any) {
      message.error(err.response?.data?.message ?? '操作失败');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div
      style={{
        minHeight: '100vh',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        padding: 24,
        background: 'var(--color-bg-canvas)',
      }}
    >
      <div className="surface-card" style={{ width: '100%', maxWidth: 400, padding: 32 }}>
        <div style={{ marginBottom: 24, textAlign: 'center' }}>
          <div
            style={{
              width: 40,
              height: 40,
              margin: '0 auto 12px',
              borderRadius: 8,
              background: 'var(--color-primary)',
              color: '#fff',
              display: 'inline-flex',
              alignItems: 'center',
              justifyContent: 'center',
              fontWeight: 700,
              fontSize: 20,
            }}
          >
            M
          </div>
          <h1 style={{ margin: 0, fontSize: 20, fontWeight: 600, color: 'var(--color-text-primary)' }}>
            Magnet 下载平台
          </h1>
          <Typography.Text style={{ fontSize: 12, color: 'var(--color-text-muted)' }}>
            多用户 BitTorrent 解析与受控离线下载
          </Typography.Text>
        </div>

        <Tabs
          activeKey={tab}
          onChange={setTab}
          centered
          items={[
            {
              key: 'login',
              label: '账号登录',
              children: (
                <Form<LoginForm> layout="vertical" size="large" onFinish={(v) => submit('/auth/login', v)}>
                  <Form.Item name="username" label="用户名" rules={[{ required: true, message: '请输入用户名' }]}>
                    <Input prefix={<UserOutlined style={{ color: '#94a3b8' }} />} placeholder="请输入用户名" autoFocus />
                  </Form.Item>
                  <Form.Item name="password" label="密码" rules={[{ required: true, message: '请输入密码' }]}>
                    <Input.Password prefix={<LockOutlined style={{ color: '#94a3b8' }} />} placeholder="请输入密码" />
                  </Form.Item>
                  <Button type="primary" htmlType="submit" block loading={loading} style={{ marginTop: 8 }}>
                    登录
                  </Button>
                </Form>
              ),
            },
            {
              key: 'register',
              label: '注册新账号',
              children: (
                <Form<RegisterForm>
                  layout="vertical"
                  size="large"
                  onFinish={(v) => submit('/auth/register', { username: v.username, password: v.password })}
                >
                  <Form.Item name="username" label="用户名" rules={[{ required: true, message: '请输入用户名' }]}>
                    <Input prefix={<UserOutlined style={{ color: '#94a3b8' }} />} placeholder="3-32 位字母或数字" autoFocus />
                  </Form.Item>
                  <Form.Item name="password" label="密码" rules={[{ required: true, message: '请输入密码' }]}>
                    <Input.Password prefix={<LockOutlined style={{ color: '#94a3b8' }} />} placeholder="至少 6 位密码" />
                  </Form.Item>
                  <Form.Item
                    name="confirmPassword"
                    label="确认密码"
                    dependencies={['password']}
                    rules={[
                      { required: true, message: '请再次输入密码' },
                      ({ getFieldValue }) => ({
                        validator: (_, value) =>
                          !value || getFieldValue('password') === value
                            ? Promise.resolve()
                            : Promise.reject(new Error('两次输入的密码不一致')),
                      }),
                    ]}
                  >
                    <Input.Password prefix={<LockOutlined style={{ color: '#94a3b8' }} />} placeholder="再次输入密码" />
                  </Form.Item>
                  <Button type="primary" htmlType="submit" block loading={loading} style={{ marginTop: 8 }}>
                    注册并登录
                  </Button>
                </Form>
              ),
            },
          ]}
        />
      </div>
    </div>
  );
}
