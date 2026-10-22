export interface UserView {
  id: string;
  username: string;
  role: string;
}

export interface AuthResponse {
  token: string;
  user: UserView;
}

export type ParseStatus = 'PENDING' | 'RESOLVING' | 'READY' | 'FAILED' | 'TIMEOUT';

export interface ParseJobView {
  id: string;
  resourceId: string;
  inputType: 'MAGNET' | 'TORRENT';
  displayHint: string | null;
  status: ParseStatus;
  errorCode: string | null;
  errorMessage: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface PageView<T> {
  items: T[];
  total: number;
  page: number;
  size: number;
}

export interface FileView {
  fileIndex: number;
  relativePath: string;
  sizeBytes: number;
  v2PiecesRootHex: string | null;
}

export interface ResourceView {
  id: string;
  name: string | null;
  versionType: 'V1' | 'V2' | 'HYBRID';
  btih: string | null;
  btmh: string | null;
  pieceLength: number | null;
  totalBytes: number | null;
  privateFlag: boolean;
  metadataSource: string | null;
  trackers: string[];
  fileCount: number | null;
  metadataReady: boolean;
  createdAt: string;
}

export interface ResourceDetailView {
  resource: ResourceView;
  files: FileView[];
}

export interface ErrorBody {
  code: string;
  message: string;
}

export type TaskStatus =
  | 'CREATED'
  | 'QUEUED'
  | 'STARTING'
  | 'DOWNLOADING'
  | 'VERIFYING'
  | 'COMPLETED'
  | 'PAUSING'
  | 'PAUSED'
  | 'RETRY_WAIT'
  | 'REPAIRING'
  | 'FAILED'
  | 'DELETING'
  | 'DELETED';

export interface TaskView {
  id: string;
  resourceId: string;
  status: TaskStatus;
  selectedBytes: number;
  verifiedBytes: number;
  progressPercent: number;
  priority: number;
  speedLimit: number | null;
  targetSubdirectory: string;
  errorCode: string | null;
  errorMessage: string | null;
  version: number;
  createdAt: string;
  updatedAt: string;
  startedAt: string | null;
  completedAt: string | null;
}

export interface TaskFileView {
  fileIndex: number;
  selected: boolean;
  verifiedBytes: number;
  exportState: string;
  relativePath?: string;
  sizeBytes?: number;
}

export interface TaskFileDetail extends TaskFileView {
  relativePath: string;
  sizeBytes: number;
}

export interface ExportView {
  id: string;
  fileIndex: number;
  relativePath: string;
  state: 'PENDING' | 'READY' | 'FAILED' | 'DELETED';
  bytes: number;
  errorMessage: string | null;
}

export interface CreateTaskRequest {
  parseId: string;
  fileIndices: number[];
  targetSubdirectory?: string;
  startImmediately?: boolean;
  priority?: number;
}
