export type AlertSeverity = "INFO" | "WARNING" | "CRITICAL";
export type AlertStatus = "OPEN" | "RESOLVED" | "IGNORED";

export interface Alert {
  id: string;
  source: string;
  alertType: string;
  severity: AlertSeverity;
  status: AlertStatus;
  message: string;
  metadata: unknown;
  createdAt: string;
  resolvedAt: string | null;
  resolvedBy: string | null;
}

export interface AlertFilter {
  status?: AlertStatus;
  severity?: AlertSeverity;
  page: number;
  size: number;
}
