export const PATHS = {
  ROOT: '/',
  LOGIN: '/login',
  PARSES: '/parses',
  PARSE_DETAIL: '/parses/:id',
  TASKS: '/tasks',
  TASK_DETAIL: '/tasks/:id',
  parseDetail: (id: string) => `/parses/${id}`,
  taskDetail: (id: string) => `/tasks/${id}`,
} as const;

export interface RouteMeta {
  title: string;
  section?: 'parses' | 'tasks';
}
