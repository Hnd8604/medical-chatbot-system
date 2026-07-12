import { CSSProperties, useCallback, useEffect, useState } from "react";
import { motion } from "framer-motion";
import { Navigate } from "react-router-dom";
import {
  apiDelete,
  apiDownload,
  apiGet,
  apiPatch,
  apiPost,
  apiPut,
  ApiError,
  openNotificationStream,
  toQuery,
  todayIso,
} from "../services/api";
import { useAuth } from "../hooks/useAuth";
import { TEXT } from "../lib/constants";
import { isDateLike, isPhoneLike, normalizePatientId, resourceList } from "../lib/formatters";
import type {
  ChatMessageItem,
  ChatMessagesResponse,
  ChatRequestBody,
  ChatResponse,
  ChatSessionListResponse,
  ChatSessionRenameResponse,
  ChatSessionSummary,
  CostSummaryResponse,
  FhirResource,
  MessageFeedback,
  MessageView,
  NotificationItem,
  NotificationListResponse,
  PatientCandidate,
  QuotaStatusResponse,
} from "../lib/types";
import { HistorySidebar } from "../components/chat/HistorySidebar";
import { ChatWindow } from "../components/chat/ChatWindow";
import { PatientInsightPanel } from "../components/chat/PatientInsightPanel";
import { UsagePanel } from "../components/chat/UsagePanel";
import { ExportModal } from "../components/chat/ExportModal";
import { AdminUsersModal } from "../components/chat/AdminUsersModal";
import { Badge } from "../components/ui/Badge";
import { Button } from "../components/ui/Button";
import { Modal } from "../components/ui/Modal";

interface PatientProfileData {
  patient: FhirResource | null;
  encounters: FhirResource[];
  observations: FhirResource[];
  conditions: FhirResource[];
  medications: FhirResource[];
}

interface SelfPatientProfileResponse {
  patientId: string | null;
  patient: FhirResource | null;
  encounters: unknown;
  observations: unknown;
  conditions: unknown;
  medications: unknown;
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
    feedback: item.feedback ?? null,
  };
}

function patientSearchPath(term: string) {
  const value = term.trim();
  if (!value) {
    return null;
  }
  if (/^(Patient\/)?[A-Za-z0-9.-]{6,}$/i.test(value) && !value.includes(" ")) {
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
  const [deleteTarget, setDeleteTarget] = useState<ChatSessionSummary | null>(null);
  const [deletingSession, setDeletingSession] = useState(false);
  const [mobilePanelOpen, setMobilePanelOpen] = useState(false);
  const [mobileHistoryOpen, setMobileHistoryOpen] = useState(false);
  const [sidebarCollapsed, setSidebarCollapsed] = useState(false);
  const [rightPanelCollapsed, setRightPanelCollapsed] = useState(false);
  const [activeView, setActiveView] = useState<"chat" | "usage">("chat");
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

  const loadSessions = useCallback(
    // background=true: refresh im lặng (sau khi chat) — không bật spinner để
    // sidebar không nhấp nháy "Đang tải..." mỗi lần chatbot trả lời.
    async (options: { background?: boolean } = {}) => {
      if (!options.background) {
        setSessionsLoading(true);
      }
      try {
        const data = await apiGet<ChatSessionListResponse>(
          `/api/chat/sessions${toQuery({ query: sessionQuery.trim() || null, limit: 30 })}`,
        );
        setSessions(data.sessions);
      } catch (error) {
        await handleError(error, "Không thể tải lịch sử hội thoại.");
      } finally {
        if (!options.background) {
          setSessionsLoading(false);
        }
      }
    },
    [handleError, sessionQuery],
  );

  const loadNotifications = useCallback(async () => {
    try {
      setNotifications(await apiGet<NotificationListResponse>("/api/notifications"));
    } catch (error) {
      await handleError(error, "Không thể tải thông báo.");
    }
  }, [handleError]);

  const loadUsage = useCallback(async () => {
    try {
      const today = todayIso();
      const [quotaData, costData] = await Promise.all([
        apiGet<QuotaStatusResponse>("/api/quota/status"),
        apiGet<CostSummaryResponse>(`/api/usage/cost-summary?from=${today}&to=${today}`),
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

  // Hồ sơ FHIR của chính người dùng (SELF) — chỉ hồ sơ đã liên kết với tài khoản.
  // Endpoint /api/me không nhận patient id, nên không thể xem hồ sơ bệnh nhân khác.
  const loadSelfProfile = useCallback(async () => {
    if (isStaff) {
      return;
    }
    setPatientLoading(true);
    try {
      const data = await apiGet<SelfPatientProfileResponse>("/api/me/patient/profile");
      setSelectedPatient(data.patient ?? null);
      setPatientProfile({
        patient: data.patient ?? null,
        encounters: resourceList(data.encounters, "encounters"),
        observations: resourceList(data.observations, "observations"),
        conditions: resourceList(data.conditions, "conditions"),
        medications: resourceList(data.medications, "medications"),
      });
    } catch (error) {
      // Chưa liên kết hồ sơ (404): để panel trống thay vì báo lỗi.
      if (error instanceof ApiError && error.status === 404) {
        setSelectedPatient(null);
        setPatientProfile(emptyProfile);
      } else {
        await handleError(error, "Không thể tải hồ sơ của bạn.");
      }
    } finally {
      setPatientLoading(false);
    }
  }, [handleError, isStaff]);

  useEffect(() => {
    void loadUsage();
  }, [loadUsage]);

  useEffect(() => {
    void loadSelfProfile();
  }, [loadSelfProfile]);

  // Thông báo realtime qua SSE thay cho polling định kỳ.
  // - "connected": kết nối/kết nối lại → load đầy đủ để resync (bắt kịp phần lỡ).
  // - "notification": thêm item mới vào đầu danh sách và tăng số chưa đọc.
  useEffect(() => {
    void loadNotifications();
    const close = openNotificationStream({
      onConnected: () => {
        void loadNotifications();
      },
      onNotification: (raw) => {
        try {
          const item = JSON.parse(raw) as NotificationItem;
          setNotifications((prev) =>
            prev.notifications.some((n) => n.id === item.id)
              ? prev
              : {
                  unread_count: prev.unread_count + 1,
                  notifications: [item, ...prev.notifications],
                },
          );
        } catch {
          // Payload lỗi định dạng: bỏ qua, lần "connected" kế tiếp sẽ resync.
        }
      },
    });
    return close;
  }, [loadNotifications]);

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
        apiGet<FhirResource>(`/api/patients/${encodeURIComponent(id)}`),
        apiGet(`/api/patients/${encodeURIComponent(id)}/encounters?limit=5`),
        apiGet(`/api/patients/${encodeURIComponent(id)}/observations?limit=5`),
        apiGet(`/api/patients/${encodeURIComponent(id)}/conditions?limit=20`),
        apiGet(`/api/patients/${encodeURIComponent(id)}/medications?limit=20`),
      ]);
      setSelectedPatient(patient);
      setPatientResults([]);
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

  function handlePatientSearchTermChange(value: string) {
    setPatientSearchTerm(value);
    if (value.trim() === "") {
      setPatientResults([]);
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
      const data = await apiGet<unknown>(path);
      setPatientResults(extractPatients(data));
    } catch (error) {
      await handleError(error, "Không thể tìm bệnh nhân.");
    } finally {
      setPatientSearching(false);
    }
  }

  async function selectSession(session: ChatSessionSummary) {
    setActiveView("chat");
    setCurrentSessionId(session.id);
    setLastResponse(null);
    try {
      const data = await apiGet<ChatMessagesResponse>(`/api/chat/sessions/${encodeURIComponent(session.id)}/messages`);
      setMessages(data.messages.map(mapHistoryMessage));
      if (isStaff && session.active_patient_id) {
        await loadPatientProfile(session.active_patient_id);
      }
    } catch (error) {
      await handleError(error, "Không thể tải tin nhắn của hội thoại.");
    }
  }

  function newChat() {
    setActiveView("chat");
    setCurrentSessionId(null);
    setLastResponse(null);
    setMessages([]);
  }

  async function renameSession(session: ChatSessionSummary, title: string) {
    const previous = sessions;
    // Cập nhật lạc quan để UI phản hồi tức thì; hoàn tác nếu gọi API thất bại.
    setSessions((current) => current.map((item) => (item.id === session.id ? { ...item, title } : item)));
    try {
      const updated = await apiPatch<ChatSessionRenameResponse>(
        `/api/chat/sessions/${encodeURIComponent(session.id)}`,
        { title },
      );
      setSessions((current) =>
        current.map((item) => (item.id === session.id ? { ...item, title: updated.title } : item)),
      );
    } catch (error) {
      setSessions(previous);
      await handleError(error, "Không thể đổi tên hội thoại.");
    }
  }

  async function confirmDeleteSession() {
    if (!deleteTarget) {
      return;
    }
    const target = deleteTarget;
    setDeletingSession(true);
    try {
      await apiDelete(`/api/chat/sessions/${encodeURIComponent(target.id)}`);
      setSessions((current) => current.filter((item) => item.id !== target.id));
      if (currentSessionId === target.id) {
        newChat();
      }
      setDeleteTarget(null);
    } catch (error) {
      await handleError(error, "Không thể xóa hội thoại.");
    } finally {
      setDeletingSession(false);
    }
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
      const response = await apiPost<ChatResponse>("/api/chat", payload);
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
      } else if (!isStaff) {
        await loadSelfProfile();
      }
      await Promise.all([loadSessions({ background: true }), loadUsage(), loadNotifications()]);
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

  async function submitFeedback(messageId: string, rating: number, comment: string) {
    const trimmed = comment.trim();
    const isUpdate = Boolean(messages.find((item) => item.id === messageId)?.feedback);
    const path = `/api/chat/messages/${encodeURIComponent(messageId)}/feedback`;
    const payload = { rating, comment: trimmed || null };
    if (isUpdate) {
      await apiPut<MessageFeedback>(path, payload);
    } else {
      await apiPost<MessageFeedback>(path, payload);
    }
    setMessages((current) =>
      current.map((item) =>
        item.id === messageId ? { ...item, feedback: { rating, comment: trimmed || null } } : item,
      ),
    );
  }

  async function deleteFeedback(messageId: string) {
    await apiDelete(`/api/chat/messages/${encodeURIComponent(messageId)}/feedback`);
    setMessages((current) =>
      current.map((item) => (item.id === messageId ? { ...item, feedback: null } : item)),
    );
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
      await apiPost<void>(`/api/notifications/${encodeURIComponent(id)}/read`);
      await loadNotifications();
    } catch (error) {
      await handleError(error, "Không thể cập nhật thông báo.");
    }
  }

  async function markAllNotificationsRead() {
    try {
      await apiPost<void>("/api/notifications/read-all");
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
      {globalError ? (
        <div className="absolute left-1/2 top-4 z-40 -translate-x-1/2 rounded-full border border-danger/20 bg-white px-4 py-2 text-sm font-semibold text-danger shadow-card">
          {globalError}
        </div>
      ) : null}

      {activeView === "usage" ? (
        <motion.div
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          className="absolute inset-0 z-20 h-full min-h-0 overflow-hidden lg:pl-[var(--sidebar-width)]"
        >
          <UsagePanel onClose={() => setActiveView("chat")} className="h-full" />
        </motion.div>
      ) : (
        <>
          <motion.div initial={{ opacity: 0 }} animate={{ opacity: 1 }} className="absolute inset-0 h-full min-h-0 overflow-hidden">
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
              onSubmitFeedback={submitFeedback}
              onDeleteFeedback={deleteFeedback}
              onExportSession={(format) => void exportSession(format)}
            />
          </motion.div>

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
              onSearchTermChange={handlePatientSearchTermChange}
              onSearchPatients={() => void searchPatients()}
              onSelectPatient={(patient) => void loadPatientProfile(patient.id || "")}
              className="fixed inset-y-0 right-0 z-30 h-full w-[var(--right-panel-fixed-width)]"
            />
          ) : null}

          <div className="fixed bottom-4 right-4 z-40 xl:hidden">
            <Button type="button" variant="primary" onClick={() => setMobilePanelOpen(true)}>
              Mở panel bệnh nhân
            </Button>
          </div>
        </>
      )}

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
          onNewChat={newChat}
          onSelectSession={(session) => void selectSession(session)}
          onRenameSession={(session, title) => void renameSession(session, title)}
          onDeleteSession={(session) => setDeleteTarget(session)}
          onExportHistory={(format) => setExportModal({ open: true, format })}
          onOpenUsage={() => setActiveView("usage")}
          onOpenAdmin={() => setAdminOpen(true)}
          onLogout={() => void logout()}
          className="pointer-events-auto h-full w-full"
        />
      </div>

      <div className="fixed bottom-4 left-4 z-40 lg:hidden">
        <Button type="button" variant="secondary" onClick={() => setMobileHistoryOpen(true)}>
          Lịch sử
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
              onNewChat={() => {
                newChat();
                setMobileHistoryOpen(false);
              }}
              onSelectSession={(session) => {
                void selectSession(session);
                setMobileHistoryOpen(false);
              }}
              onRenameSession={(session, title) => void renameSession(session, title)}
              onDeleteSession={(session) => setDeleteTarget(session)}
              onExportHistory={(format) => setExportModal({ open: true, format })}
              onOpenUsage={() => {
                setActiveView("usage");
                setMobileHistoryOpen(false);
              }}
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
              onSearchTermChange={handlePatientSearchTermChange}
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

      <Modal
        open={deleteTarget !== null}
        title="Xóa hội thoại"
        description="Hành động này không thể hoàn tác."
        size="sm"
        onClose={() => {
          if (!deletingSession) {
            setDeleteTarget(null);
          }
        }}
      >
        <p className="text-sm text-foreground">
          Bạn có chắc muốn xóa hội thoại{" "}
          <strong>“{deleteTarget?.title || "Hội thoại chưa đặt tên"}”</strong>? Toàn bộ tin nhắn trong hội thoại sẽ bị
          xóa vĩnh viễn.
        </p>
        <div className="mt-6 flex justify-end gap-3">
          <Button type="button" variant="secondary" onClick={() => setDeleteTarget(null)} disabled={deletingSession}>
            {TEXT.cancel}
          </Button>
          <Button type="button" variant="danger" onClick={() => void confirmDeleteSession()} disabled={deletingSession}>
            {deletingSession ? "Đang xóa..." : TEXT.deleteSession}
          </Button>
        </div>
      </Modal>
    </main>
  );
}
