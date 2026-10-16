export type AlertSeverity = "INFO" | "WARNING" | "CRITICAL";
export type AlertStatus = "OPEN" | "RESOLVED" | "IGNORED";

export interface Alert {
  id: string;
  source: string;
  alert_type: string;
  severity: AlertSeverity;
  status: AlertStatus;
  message: string;
  metadata: unknown;
  created_at: string;
  resolved_at: string | null;
  resolved_by: string | null;
}

export interface AlertFilter {
  status?: AlertStatus;
  severity?: AlertSeverity;
  page: number;
  size: number;
}
