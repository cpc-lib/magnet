import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import type { UserView } from '@/api/types';

interface AuthState {
  token: string | null;
  user: UserView | null;
  setAuth: (token: string, user: UserView) => void;
  clear: () => void;
}

export const useAuth = create<AuthState>()(
  persist(
    (set) => ({
      token: null,
      user: null,
      setAuth: (token, user) => set({ token, user }),
      clear: () => set({ token: null, user: null }),
    }),
    { name: 'magnet-auth' },
  ),
);
