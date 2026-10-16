export type BackupTrigger = "AUTO" | "MANUAL";
export type JobStatus = "RUNNING" | "SUCCESS" | "PARTIAL" | "FAILED";

/** One database dumped by a backup run, as reported by backup.sh. */
export interface BackupItem {
  db: string;
  file: string;
  size_bytes: number;
  ok: boolean;
  uploaded: boolean;
}

export interface Backup {
  id: string;
  triggerType: BackupTrigger;
  status: JobStatus;
  triggeredBy: string | null;
  startedAt: string;
  finishedAt: string | null;
  totalSizeBytes: number | null;
  uploadTarget: string | null;
  items: BackupItem[] | null;
  errorMessage: string | null;
  createdAt: string;
}

export interface RestoreItem {
  db: string;
  file: string;
  ok: boolean;
  error?: string;
}

export interface Restore {
  id: string;
  backupId: string | null;
  status: JobStatus;
  triggeredBy: string | null;
  startedAt: string;
  finishedAt: string | null;
  items: RestoreItem[] | null;
  errorMessage: string | null;
  createdAt: string;
}
