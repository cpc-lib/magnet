import axios from 'axios';
import { PATHS } from '@/router/paths';
import { useAuth } from '@/store/auth';

const client = axios.create({ baseURL: '/api/v1', timeout: 30000 });

client.interceptors.request.use((config) => {
  const token = useAuth.getState().token;
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

client.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response?.status === 401 && !window.location.pathname.startsWith(PATHS.LOGIN)) {
      useAuth.getState().clear();
      window.location.href = PATHS.LOGIN;
    }
    return Promise.reject(error);
  },
);

export default client;
