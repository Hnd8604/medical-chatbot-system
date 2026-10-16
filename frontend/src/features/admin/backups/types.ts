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
  trigger_type: BackupTrigger;
  status: JobStatus;
  triggered_by: string | null;
  started_at: string;
  finished_at: string | null;
  total_size_bytes: number | null;
  upload_target: string | null;
  items: BackupItem[] | null;
  error_message: string | null;
  created_at: string;
}

export interface RestoreItem {
  db: string;
  file: string;
  ok: boolean;
  error?: string;
}

export interface Restore {
  id: string;
  backup_id: string | null;
  status: JobStatus;
  triggered_by: string | null;
  started_at: string;
  finished_at: string | null;
  items: RestoreItem[] | null;
  error_message: string | null;
  created_at: string;
}
