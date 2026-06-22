import { CSSProperties, useCallback, useEffect, useState } from "react";
import { motion } from "framer-motion";
import { Navigate } from "react-router-dom";
import { apiDownload, apiJson, ApiError, toQuery, todayIso } from "../lib/api";
import { useAuth } from "../lib/auth";
import { isDateLike, isPhoneLike, normalizePatientId, resourceList } from "../lib/formatters";
import type {
  ChatMessageItem,
  ChatMessagesResponse,
  ChatRequestBody,
  ChatResponse,
  ChatSessionListResponse,
  ChatSessionSummary,
  CostSummaryResponse,
  FhirResource,
  MessageView,
  NotificationListResponse,
  PatientCandidate,
  QuotaStatusResponse,
} from "../lib/types";
import { HistorySidebar } from "../components/chat/HistorySidebar";
import { ChatWindow } from "../components/chat/ChatWindow";
import { PatientInsightPanel } from "../components/chat/PatientInsightPanel";
import { ExportModal } from "../components/chat/ExportModal";
import { AdminUsersModal } from "../components/chat/AdminUsersModal";
import { Badge } from "../components/ui/Badge";
import { Button } from "../components/ui/Button";

interface PatientProfileData {
  patient: FhirResource | null;
  encounters: FhirResource[];
  observations: FhirResource[];
  conditions: FhirResource[];
  medications: FhirResource[];
}

const emptyProfile: PatientProfileData = {
  patient: null,
  encounters: [],
  observations: [],
  conditions: [],
  medications: [],
};

function createId() {
  return crypto.randomUUID?.() || `${Date.now()}-${Math.random()}`;
}

function mapHistoryMessage(item: ChatMessageItem): MessageView {
  return {
    id: item.id,
    role: item.role.toUpperCase() === "USER" ? "user" : "assistant",
    content: item.content,
    createdAt: item.created_at,
  };
}

function patientSearchPath(term: string) {
  const value = term.trim();
  if (!value) {
    return null;
  }
  if (/^(Patient\/)?demo-patient-|^(Patient\/)?[A-Za-z0-9.-]{6,}$/i.test(value) && !value.includes(" ")) {
    return `/api/patients/${encodeURIComponent(normalizePatientId(value) || value)}`;
  }
  if (isPhoneLike(value)) {
    return `/api/patients${toQuery({ phone: value, limit: 20 })}`;
  }
  if (isDateLike(value)) {
    return `/api/patients${toQuery({ birth_date: value, limit: 20 })}`;
  }
  return `/api/patients${toQuery({ name: value, limit: 20 })}`;
}

function extractPatients(value: unknown): FhirResource[] {
  const resource = value as FhirResource;
  if (resource?.resourceType === "Patient" || resource?.resource_type === "Patient") {
    return [resource];
  }
  return resourceList(value, "patients").filter((item) => item.resourceType === "Patient" || item.resource_type === "Patient");
}

export function ChatPage() {
  const { user, logout } = useAuth();
  const [sessions, setSessions] = useState<ChatSessionSummary[]>([]);
  const [sessionQuery, setSessionQuery] = useState("");
  const [sessionsLoading, setSessionsLoading] = useState(false);
  const [currentSessionId, setCurrentSessionId] = useState<string | null>(null);
  const [messages, setMessages] = useState<MessageView[]>([]);
  const [sending, setSending] = useState(false);
  const [selectedPatient, setSelectedPatient] = useState<FhirResource | null>(null);
  const [patientResults, setPatientResults] = useState<FhirResource[]>([]);
  const [patientSearchTerm, setPatientSearchTerm] = useState("");
  const [patientProfile, setPatientProfile] = useState<PatientProfileData>(emptyProfile);
  const [patientLoading, setPatientLoading] = useState(false);
  const [patientSearching, setPatientSearching] = useState(false);
  const [lastResponse, setLastResponse] = useState<ChatResponse | null>(null);
  const [notifications, setNotifications] = useState<NotificationListResponse>({ unread_count: 0, notifications: [] });
  const [notificationOpen, setNotificationOpen] = useState(false);
  const [quota, setQuota] = useState<QuotaStatusResponse | null>(null);
  const [cost, setCost] = useState<CostSummaryResponse | null>(null);
  const [exportModal, setExportModal] = useState<{ open: boolean; format: "pdf" | "csv" }>({ open: false, format: "pdf" });
  const [adminOpen, setAdminOpen] = useState(false);
  const [mobilePanelOpen, setMobilePanelOpen] = useState(false);
  const [mobileHistoryOpen, setMobileHistoryOpen] = useState(false);
  const [sidebarCollapsed, setSidebarCollapsed] = useState(false);
  const [rightPanelCollapsed, setRightPanelCollapsed] = useState(false);
  const [globalError, setGlobalError] = useState<string | null>(null);

  const isStaff = user?.role === "DOCTOR" || user?.role === "ADMIN";

  const handleError = useCallback(
    async (error: unknown, fallback: string) => {
      if (error instanceof ApiError && error.status === 401) {
        await logout();
        return;
      }
      setGlobalError(error instanceof Error ? error.message : fallback);
    },
    [logout],
  );

  const loadSessions = useCallback(async () => {
    setSessionsLoading(true);
    try {
      const data = await apiJson<ChatSessionListResponse>(
        `/api/chat/sessions${toQuery({ query: sessionQuery.trim() || null, limit: 30 })}`,
      );
      setSessions(data.sessions);
    } catch (error) {
      await handleError(error, "Không thể tải lịch sử hội thoại.");
    } finally {
      setSessionsLoading(false);
    }
  }, [handleError, sessionQuery]);

  const loadNotifications = useCallback(async () => {
    try {
      setNotifications(await apiJson<NotificationListResponse>("/api/notifications"));
    } catch (error) {
      await handleError(error, "Không thể tải thông báo.");
    }
  }, [handleError]);

  const loadUsage = useCallback(async () => {
    try {
      const today = todayIso();
      const [quotaData, costData] = await Promise.all([
        apiJson<QuotaStatusResponse>("/api/quota/status"),
        apiJson<CostSummaryResponse>(`/api/usage/cost-summary?from=${today}&to=${today}`),
      ]);
      setQuota(quotaData);
      setCost(costData);
    } catch (error) {
      await handleError(error, "Không thể tải mức sử dụng.");
    }
  }, [handleError]);

  useEffect(() => {
    const timer = window.setTimeout(() => {
      void loadSessions();
    }, 300);
    return () => window.clearTimeout(timer);
  }, [loadSessions]);

  useEffect(() => {
    void loadNotifications();
    void loadUsage();
    const timer = window.setInterval(() => {
      void loadNotifications();
    }, 60000);
    return () => window.clearInterval(timer);
  }, [loadNotifications, loadUsage]);

  if (!user) {
    return <Navigate to="/login" replace />;
  }
  const currentUser = user;

  async function loadPatientProfile(patientId: string) {
    if (!isStaff) {
      return;
    }
    const id = normalizePatientId(patientId);
    if (!id) {
      return;
    }
    setPatientLoading(true);
    try {
      const [patient, encounters, observations, conditions, medications] = await Promise.all([
        apiJson<FhirResource>(`/api/patients/${encodeURIComponent(id)}`),
        apiJson(`/api/patients/${encodeURIComponent(id)}/encounters?limit=5`),
        apiJson(`/api/patients/${encodeURIComponent(id)}/observations?limit=5`),
        apiJson(`/api/patients/${encodeURIComponent(id)}/conditions?limit=20`),
        apiJson(`/api/patients/${encodeURIComponent(id)}/medications?limit=20`),
      ]);
      setSelectedPatient(patient);
      setPatientProfile({
        patient,
        encounters: resourceList(encounters, "encounters"),
        observations: resourceList(observations, "observations"),
        conditions: resourceList(conditions, "conditions"),
        medications: resourceList(medications, "medications"),
      });
    } catch (error) {
      await handleError(error, "Không thể tải hồ sơ bệnh nhân.");
    } finally {
      setPatientLoading(false);
    }
  }

  async function searchPatients() {
    if (!isStaff) {
      return;
    }
    const path = patientSearchPath(patientSearchTerm);
    if (!path) {
      setPatientResults([]);
      return;
    }
    setPatientSearching(true);
    try {
      const data = await apiJson<unknown>(path);
      setPatientResults(extractPatients(data));
    } catch (error) {
      await handleError(error, "Không thể tìm bệnh nhân.");
    } finally {
      setPatientSearching(false);
    }
  }

  async function selectSession(session: ChatSessionSummary) {
    setCurrentSessionId(session.id);
    setLastResponse(null);
    try {
      const data = await apiJson<ChatMessagesResponse>(`/api/chat/sessions/${encodeURIComponent(session.id)}/messages`);
      setMessages(data.messages.map(mapHistoryMessage));
      if (isStaff && session.active_patient_id) {
        await loadPatientProfile(session.active_patient_id);
      }
    } catch (error) {
      await handleError(error, "Không thể tải tin nhắn của hội thoại.");
    }
  }

  function newChat() {
    setCurrentSessionId(null);
    setLastResponse(null);
    setMessages([]);
  }

  async function submitMessage(
    message: string,
    options: { patientIdOverride?: string | null; visibleMessage?: string } = {},
  ) {
    setSending(true);
    setGlobalError(null);
    const userMessage: MessageView = {
      id: createId(),
      role: "user",
      content: options.visibleMessage || message,
      createdAt: new Date().toISOString(),
    };
    const pendingId = createId();
    setMessages((current) => [
      ...current,
      userMessage,
      { id: pendingId, role: "assistant", content: "Đang xử lý câu hỏi...", pending: true },
    ]);

    try {
      const payload: ChatRequestBody = {
        session_id: currentSessionId,
        patient_id: isStaff ? options.patientIdOverride ?? selectedPatient?.id ?? null : null,
        message,
      };
      const response = await apiJson<ChatResponse>("/api/chat", {
        method: "POST",
        body: JSON.stringify(payload),
      });
      setCurrentSessionId(response.session_id);
      setLastResponse(response);
      setMessages((current) =>
        current.map((item) =>
          item.id === pendingId
            ? {
                id: response.message_id || pendingId,
                role: "assistant",
                content: response.answer || "Không có nội dung phản hồi.",
                createdAt: new Date().toISOString(),
                response,
              }
            : item,
        ),
      );
      if (isStaff && response.patient_id) {
        await loadPatientProfile(response.patient_id);
      }
      await Promise.all([loadSessions(), loadUsage(), loadNotifications()]);
    } catch (error) {
      const messageText = error instanceof Error ? error.message : "Không thể gửi câu hỏi.";
      setMessages((current) =>
        current.map((item) =>
          item.id === pendingId
            ? {
                id: pendingId,
                role: "error",
                content: messageText,
                createdAt: new Date().toISOString(),
              }
            : item,
        ),
      );
      await handleError(error, "Không thể gửi câu hỏi.");
    } finally {
      setSending(false);
    }
  }

  async function selectPatientCandidate(candidate: PatientCandidate, pendingQuestion: string | null) {
    const patientId = normalizePatientId(candidate.id);
    if (!patientId) {
      return;
    }

    if (isStaff) {
      setRightPanelCollapsed(false);
      await loadPatientProfile(patientId);
    }

    const question = pendingQuestion?.trim();
    if (question) {
      await submitMessage(question, {
        patientIdOverride: patientId,
        visibleMessage: `Chọn Patient/${patientId}${candidate.name ? ` - ${candidate.name}` : ""}`,
      });
    }
  }

  async function exportSession(format: "pdf" | "csv") {
    if (!currentSessionId) {
      return;
    }
    try {
      await apiDownload(
        `/api/chat/sessions/${encodeURIComponent(currentSessionId)}/export?format=${format}`,
        `phien_${currentSessionId}.${format}`,
      );
    } catch (error) {
      await handleError(error, "Không thể xuất phiên hiện tại.");
    }
  }

  async function markNotificationRead(id: string) {
    try {
      await apiJson<void>(`/api/notifications/${encodeURIComponent(id)}/read`, { method: "POST" });
      await loadNotifications();
    } catch (error) {
      await handleError(error, "Không thể cập nhật thông báo.");
    }
  }

  async function markAllNotificationsRead() {
    try {
      await apiJson<void>("/api/notifications/read-all", { method: "POST" });
      await loadNotifications();
    } catch (error) {
      await handleError(error, "Không thể cập nhật thông báo.");
    }
  }

  const shellStyle = {
    "--sidebar-width": sidebarCollapsed ? "76px" : "320px",
    "--right-panel-fixed-width": "380px",
  } as CSSProperties;

  return (
    <main className="chat-shell relative h-screen overflow-hidden bg-background text-foreground" style={shellStyle}>
      <motion.div initial={{ opacity: 0 }} animate={{ opacity: 1 }} className="absolute inset-0 h-full min-h-0 overflow-hidden">
        {globalError ? (
          <div className="absolute left-1/2 top-4 z-40 -translate-x-1/2 rounded-full border border-danger/20 bg-white px-4 py-2 text-sm font-semibold text-danger shadow-card">
            {globalError}
          </div>
        ) : null}
        <ChatWindow
          role={currentUser.role}
          messages={messages}
          currentSessionId={currentSessionId}
          displayName={currentUser.display_name || currentUser.username}
          sending={sending}
          selectedPatientId={selectedPatient?.id || null}
          notifications={notifications.notifications}
          unreadCount={notifications.unread_count}
          notificationOpen={notificationOpen}
          rightPanelCollapsed={rightPanelCollapsed}
          onToggleNotifications={() => setNotificationOpen((value) => !value)}
          onToggleRightPanel={() => setRightPanelCollapsed((value) => !value)}
          onMarkNotificationRead={(id) => void markNotificationRead(id)}
          onMarkAllNotificationsRead={() => void markAllNotificationsRead()}
          onSelectPatientCandidate={(candidate, pendingQuestion) => void selectPatientCandidate(candidate, pendingQuestion)}
          onSubmitMessage={(value) => void submitMessage(value)}
          onExportSession={(format) => void exportSession(format)}
        />
      </motion.div>

      <div className="pointer-events-none fixed inset-y-0 left-0 z-30 hidden w-[var(--sidebar-width)] lg:block">
        <HistorySidebar
          user={currentUser}
          sessions={sessions}
          activeSessionId={currentSessionId}
          query={sessionQuery}
          loading={sessionsLoading}
          collapsed={sidebarCollapsed}
          onToggleCollapsed={() => setSidebarCollapsed((value) => !value)}
          onQueryChange={setSessionQuery}
          onRefresh={() => void loadSessions()}
          onNewChat={newChat}
          onSelectSession={(session) => void selectSession(session)}
          onExportHistory={(format) => setExportModal({ open: true, format })}
          onOpenAdmin={() => setAdminOpen(true)}
          onLogout={() => void logout()}
          className="pointer-events-auto h-full w-full"
        />
      </div>

      {!rightPanelCollapsed ? (
        <PatientInsightPanel
          role={currentUser.role}
          searchTerm={patientSearchTerm}
          patientResults={patientResults}
          selectedPatient={selectedPatient}
          profile={patientProfile}
          lastResponse={lastResponse}
          loadingProfile={patientLoading}
          searchingPatients={patientSearching}
          quota={quota}
          cost={cost}
          onSearchTermChange={setPatientSearchTerm}
          onSearchPatients={() => void searchPatients()}
          onSelectPatient={(patient) => void loadPatientProfile(patient.id || "")}
          className="fixed inset-y-0 right-0 z-30 h-full w-[var(--right-panel-fixed-width)]"
        />
      ) : null}

      <div className="fixed bottom-4 left-4 z-40 lg:hidden">
        <Button type="button" variant="secondary" onClick={() => setMobileHistoryOpen(true)}>
          Lịch sử
        </Button>
      </div>

      <div className="fixed bottom-4 right-4 z-40 xl:hidden">
        <Button type="button" variant="primary" onClick={() => setMobilePanelOpen(true)}>
          Mở panel bệnh nhân
        </Button>
      </div>

      {mobileHistoryOpen ? (
        <div className="fixed inset-0 z-50 bg-foreground/50 p-3 backdrop-blur-sm lg:hidden">
          <div className="h-full max-w-md overflow-hidden rounded-2xl bg-white shadow-lift">
            <div className="flex items-center justify-between border-b border-border p-4">
              <Badge tone="blue">Lịch sử</Badge>
              <Button type="button" variant="ghost" onClick={() => setMobileHistoryOpen(false)}>
                Đóng
              </Button>
            </div>
            <HistorySidebar
              user={currentUser}
              sessions={sessions}
              activeSessionId={currentSessionId}
              query={sessionQuery}
              loading={sessionsLoading}
              collapsed={false}
              onToggleCollapsed={() => setMobileHistoryOpen(false)}
              onQueryChange={setSessionQuery}
              onRefresh={() => void loadSessions()}
              onNewChat={() => {
                newChat();
                setMobileHistoryOpen(false);
              }}
              onSelectSession={(session) => {
                void selectSession(session);
                setMobileHistoryOpen(false);
              }}
              onExportHistory={(format) => setExportModal({ open: true, format })}
              onOpenAdmin={() => setAdminOpen(true)}
              onLogout={() => void logout()}
              className="h-[calc(100%-65px)] border-r-0"
            />
          </div>
        </div>
      ) : null}

      {mobilePanelOpen ? (
        <div className="fixed inset-0 z-50 bg-foreground/50 p-3 backdrop-blur-sm xl:hidden">
          <div className="ml-auto h-full max-w-md overflow-hidden rounded-2xl bg-white shadow-lift">
            <div className="flex items-center justify-between border-b border-border p-4">
              <Badge tone="blue">Thông tin</Badge>
              <Button type="button" variant="ghost" onClick={() => setMobilePanelOpen(false)}>
                Đóng
              </Button>
            </div>
            <PatientInsightPanel
              role={currentUser.role}
              searchTerm={patientSearchTerm}
              patientResults={patientResults}
              selectedPatient={selectedPatient}
              profile={patientProfile}
              lastResponse={lastResponse}
              loadingProfile={patientLoading}
              searchingPatients={patientSearching}
              quota={quota}
              cost={cost}
              onSearchTermChange={setPatientSearchTerm}
              onSearchPatients={() => void searchPatients()}
              onSelectPatient={(patient) => void loadPatientProfile(patient.id || "")}
              mobile
            />
          </div>
        </div>
      ) : null}

      <ExportModal
        open={exportModal.open}
        format={exportModal.format}
        onClose={() => setExportModal((current) => ({ ...current, open: false }))}
        onError={setGlobalError}
      />
      <AdminUsersModal open={adminOpen} onClose={() => setAdminOpen(false)} onError={setGlobalError} />
    </main>
  );
}
