export type UserRole = "USER" | "DOCTOR" | "ADMIN";
export type UserStatus = "ACTIVE" | "LOCKED" | "DISABLED";

export interface AuthUser {
  id: string;
  username: string;
  email: string;
  display_name: string;
  role: UserRole;
  status: UserStatus;
}

export interface AuthLoginResponse {
  access_token: string;
  token_type: "Bearer";
  expires_in_seconds: number;
  user: AuthUser;
}

export interface ChatSessionSummary {
  id: string;
  title: string | null;
  created_at: string;
  updated_at: string;
  active_patient_id: string | null;
  message_count: number;
  last_message_preview: string | null;
}

export interface ChatSessionListResponse {
  sessions: ChatSessionSummary[];
}

export interface ChatMessageItem {
  id: string;
  role: "USER" | "ASSISTANT" | "SYSTEM" | string;
  content: string;
  created_at: string;
}

export interface ChatMessagesResponse {
  session_id: string;
  messages: ChatMessageItem[];
}

export interface ChatResponse {
  session_id: string;
  message_id: string | null;
  answer: string;
  intent: string | null;
  tool_name: string | null;
  intent_source: string | null;
  answer_source: string | null;
  answer_reason: string | null;
  patient_id: string | null;
  observation_type: string | null;
  all_patients: boolean | null;
  patient_search: unknown;
  needs_patient_selection: boolean | null;
  patient_candidates: PatientCandidate[] | null;
  pending_question: string | null;
  evidence: unknown;
  answer_usage: unknown;
  usage: unknown;
  saved_usage: unknown;
}

export interface PatientCandidate {
  id: string;
  name?: string | null;
  gender?: string | null;
  birth_date?: string | null;
  phone?: string | null;
  identifier?: string | null;
}

export interface ChatRequestBody {
  session_id?: string | null;
  patient_id?: string | null;
  message: string;
}

export interface NotificationItem {
  id: string;
  type: string;
  title: string;
  content: string;
  is_read: boolean;
  created_at: string;
}

export interface NotificationListResponse {
  unread_count: number;
  notifications: NotificationItem[];
}

export interface QuotaStatusResponse {
  user: string;
  policy: string;
  daily_request_limit: number;
  daily_token_limit: number;
  daily_cost_limit_usd: number;
  used_requests: number;
  used_input_tokens: number;
  used_output_tokens: number;
  used_tokens: number;
  used_cost_usd: number;
  remaining_requests: number;
  remaining_tokens: number;
  remaining_cost_usd: number;
  allowed: boolean;
  blocked_reason: string | null;
}

export interface CostSummaryResponse {
  from: string;
  to: string;
  request_count: number;
  input_tokens: number;
  output_tokens: number;
  total_tokens: number;
  estimated_cost_usd: number;
  models: unknown[];
  days: unknown[];
  missing_pricing_models: unknown[];
}

export interface PageResponse<T> {
  content: T[];
  totalElements?: number;
  total_elements?: number;
  totalPages?: number;
  total_pages?: number;
  number?: number;
  size?: number;
}

export interface AuditLogItem {
  id?: string;
  action?: string;
  resourceType?: string | null;
  resource_type?: string | null;
  resourceId?: string | null;
  resource_id?: string | null;
  createdAt?: string | null;
  created_at?: string | null;
  userId?: string | null;
  user_id?: string | null;
  sessionId?: string | null;
  session_id?: string | null;
  metadataJson?: unknown;
  metadata_json?: unknown;
}

export interface CacheMetricsResponse {
  total_requests?: number;
  total_cache_hits?: number;
  total_saved_tokens?: number;
  total_saved_cost_usd?: number;
  hit_rate_percentage?: number;
}

export interface IntentAnalytics {
  date: string;
  intent: string;
  count: number;
}

export interface ErrorAnalytics {
  date: string;
  service: string;
  errorType: string;
  count: number;
}

export interface PerformanceAnalytics {
  model: string;
  avgLatency: number;
  p95Latency: number;
  p99Latency: number;
}

export interface AdminAlertItem {
  id?: string;
  source?: string | null;
  alertType?: string | null;
  alert_type?: string | null;
  severity?: string | null;
  status?: string | null;
  message?: string | null;
  createdAt?: string | null;
  created_at?: string | null;
}

export interface PatientLinkItem {
  fhir_patient_id?: string | null;
  relationship?: string | null;
  is_primary?: boolean | null;
}

export interface AdminUserItem {
  id: string;
  username: string;
  email: string;
  display_name: string;
  role: UserRole;
  status: UserStatus;
  created_at: string;
  updated_at: string;
  patient_links?: PatientLinkItem[];
}

export interface AdminUserListResponse {
  page: number;
  size: number;
  total_elements: number;
  total_pages: number;
  users: AdminUserItem[];
}

export interface FhirBundle {
  resourceType?: "Bundle";
  entry?: Array<{ resource?: FhirResource }>;
}

export interface FhirResource {
  resourceType?: string;
  resource_type?: string;
  id?: string;
  name?: string | Array<{ family?: string; given?: string[]; text?: string }>;
  names?: Array<{ family?: string; given?: string[]; text?: string }>;
  gender?: string;
  birthDate?: string;
  birth_date?: string;
  telecom?: Array<{ system?: string; value?: string }>;
  phone?: string;
  identifier?: string | Array<{ system?: string; value?: string }>;
  code?: string | { text?: string; coding?: Array<{ display?: string; code?: string }> };
  medication?: string;
  status?: string;
  effectiveDateTime?: string;
  effective_time?: string | { start?: string; end?: string };
  issued?: string;
  onsetDateTime?: string;
  onset?: string;
  recordedDate?: string;
  recorded_date?: string;
  authored_on?: string;
  period?: { start?: string; end?: string };
  valueQuantity?: { value?: number; unit?: string };
  value?: { value?: number | string; unit?: string };
  valueString?: string;
  value_string?: string;
  medicationCodeableConcept?: { text?: string; coding?: Array<{ display?: string }> };
  reasonCode?: Array<{ text?: string; coding?: Array<{ display?: string }> }>;
  reason_code?: Array<{ text?: string; display?: string; coding?: Array<{ display?: string }> }>;
  type?: Array<{ text?: string; display?: string; coding?: Array<{ display?: string }> }>;
  service_type?: { text?: string; display?: string; coding?: Array<{ display?: string }> };
  clinical_status?: string;
  dosage?: string[];
  components?: Array<{ code?: string; value?: { value?: number | string; unit?: string }; value_string?: string }>;
  [key: string]: unknown;
}

export interface MessageView {
  id: string;
  role: "user" | "assistant" | "error";
  content: string;
  createdAt?: string;
  pending?: boolean;
  response?: ChatResponse;
}
