export interface AuditLog {
  id: string;
  userId: string | null;
  sessionId: string | null;
  action: string;
  resourceType: string | null;
  resourceId: string | null;
  metadata: unknown;
  createdAt: string;
}

/** Values the filter selects offer, as known by the backend. */
export interface AuditLogFilterOptions {
  actions: string[];
  resourceTypes: string[];
}

export interface AuditLogFilter {
  userId: string;
  action: string;
  resourceType: string;
  resourceId: string;
  /** Local dates (yyyy-MM-dd); converted to instants for the API. */
  from: string;
  to: string;
}
