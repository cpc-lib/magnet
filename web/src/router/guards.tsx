import { Navigate, Outlet, useLocation } from 'react-router-dom';
import { useAuth } from '@/store/auth';
import { PATHS } from '@/router/paths';

export function RequireAuth() {
  const token = useAuth((s) => s.token);
  const location = useLocation();

  if (!token) {
    return <Navigate to={PATHS.LOGIN} state={{ from: location }} replace />;
  }

  return <Outlet />;
}

export function GuestOnly() {
  const token = useAuth((s) => s.token);
  const location = useLocation();

  if (token) {
    const fromPath = (location.state as { from?: { pathname?: string } } | null)?.from?.pathname;
    const redirectTo = fromPath && fromPath !== PATHS.LOGIN ? fromPath : PATHS.PARSES;
    return <Navigate to={redirectTo} replace />;
  }

  return <Outlet />;
}
