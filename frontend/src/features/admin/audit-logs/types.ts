export interface AuditLog {
  id: string;
  user_id: string | null;
  session_id: string | null;
  action: string;
  resource_type: string | null;
  resource_id: string | null;
  metadata: unknown;
  created_at: string;
}

/** Values the filter selects offer, as known by the backend. */
export interface AuditLogFilterOptions {
  actions: string[];
  resource_types: string[];
}

export interface AuditLogFilter {
  user_id: string;
  action: string;
  resource_type: string;
  resource_id: string;
  /** Local dates (yyyy-MM-dd); converted to instants for the API. */
  from: string;
  to: string;
}
