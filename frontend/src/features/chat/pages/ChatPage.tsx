import { CSSProperties, useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { motion } from "framer-motion";
import { PanelRightClose, PanelRightOpen } from "lucide-react";
import { useAuth, useSignedInUser } from "@/features/auth/AuthProvider";
import { isStaff } from "@/features/auth/labels";
import { notificationKeys } from "@/features/notifications/api";
import { NotificationPopover } from "@/features/notifications/components/NotificationPopover";
import { useNotifications } from "@/features/notifications/hooks";
import { PatientInsightPanel } from "@/features/patients/components/PatientInsightPanel";
import { evidencePatient, normalizePatientId } from "@/features/patients/fhir";
import { usePatientPanel } from "@/features/patients/hooks";
import type { PatientCandidate } from "@/features/patients/types";
import { usageKeys } from "@/features/usage/api";
import { UsagePanel } from "@/features/usage/components/UsagePanel";
import { errorMessage } from "@/shared/api/client";
import { Badge } from "@/shared/ui/Badge";
import { Button } from "@/shared/ui/Button";
import { ChatWindow } from "../components/ChatWindow";
import { DeleteSessionModal } from "../components/DeleteSessionModal";
import { ExportHistoryModal } from "../components/ExportHistoryModal";
import { HistorySidebar } from "../components/HistorySidebar";
import { useChatSessions } from "../hooks/useChatSessions";
import { useConversation } from "../hooks/useConversation";
import type { ChatSession, ExportFormat } from "../types";

function MobileSheet({ title, side, onClose, children }: { title: string; side: "left" | "right"; onClose: () => void; children: React.ReactNode }) {
  return (
    <div className={`fixed inset-0 z-50 bg-foreground/50 p-3 backdrop-blur-sm ${side === "left" ? "lg:hidden" : "xl:hidden"}`}>
      <div className={`h-full max-w-md overflow-hidden rounded-2xl bg-white shadow-lift ${side === "right" ? "ml-auto" : ""}`}>
        <div className="flex items-center justify-between border-b border-border p-4">
          <Badge tone="blue">{title}</Badge>
          <Button type="button" variant="ghost" onClick={onClose}>
            Đóng
          </Button>
        </div>
        {children}
      </div>
    </div>
  );
}

export function ChatPage() {
  const user = useSignedInUser();
  const { logout } = useAuth();
  const staff = isStaff(user);
  const queryClient = useQueryClient();

  const sessions = useChatSessions();
  const conversation = useConversation();
  const patientPanel = usePatientPanel(staff);
  const notifications = useNotifications();

  const [view, setView] = useState<"chat" | "usage">("chat");
  const [actionError, setActionError] = useState<string | null>(null);
  const [sidebarCollapsed, setSidebarCollapsed] = useState(false);
  const [rightPanelOpen, setRightPanelOpen] = useState(true);
  const [mobileHistoryOpen, setMobileHistoryOpen] = useState(false);
  const [mobilePanelOpen, setMobilePanelOpen] = useState(false);
  const [notificationOpen, setNotificationOpen] = useState(false);
  const [exportFormat, setExportFormat] = useState<ExportFormat | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<ChatSession | null>(null);

  const loadError = sessions.error ?? patientPanel.error ?? notifications.error;
  const banner = actionError ?? (loadError ? errorMessage(loadError, "Không thể tải dữ liệu.") : null);

  async function ask(message: string, options: { patientId?: string | null; visibleMessage?: string } = {}) {
    setActionError(null);
    try {
      const response = await conversation.send(message, {
        patientId: staff ? (options.patientId ?? patientPanel.activePatientId) : null,
        visibleMessage: options.visibleMessage,
      });
      if (staff && response.patient_id) {
        patientPanel.selectPatient(response.patient_id);
      }
      // Each answer changes the history list, today's usage and possibly quota notifications.
      void sessions.refresh();
      void queryClient.invalidateQueries({ queryKey: usageKeys.all });
      void queryClient.invalidateQueries({ queryKey: notificationKeys.list() });
    } catch (error) {
      setActionError(errorMessage(error, "Không thể gửi câu hỏi."));
    }
  }

  async function selectCandidate(candidate: PatientCandidate, pendingQuestion: string | null) {
    const patientId = normalizePatientId(candidate.id);
    if (!patientId) {
      return;
    }
    if (staff) {
      setRightPanelOpen(true);
      patientPanel.selectPatient(patientId);
    }
    const question = pendingQuestion?.trim();
    if (question) {
      await ask(question, {
        patientId,
        visibleMessage: `Chọn Patient/${patientId}${candidate.name ? ` - ${candidate.name}` : ""}`,
      });
    }
  }

  async function openSession(session: ChatSession) {
    setView("chat");
    setActionError(null);
    try {
      await conversation.open(session.id);
      if (staff && session.active_patient_id) {
        patientPanel.selectPatient(session.active_patient_id);
      }
    } catch (error) {
      setActionError(errorMessage(error, "Không thể tải tin nhắn của hội thoại."));
    }
  }

  function newChat() {
    setView("chat");
    conversation.reset();
  }

  async function deleteSession(session: ChatSession) {
    await sessions.remove(session.id);
    if (conversation.sessionId === session.id) {
      newChat();
    }
  }

  const sidebarProps = {
    user,
    sessions: sessions.sessions,
    activeSessionId: conversation.sessionId,
    query: sessions.query,
    loading: sessions.loading,
    onQueryChange: sessions.setQuery,
    onRenameSession: (session: ChatSession, title: string) => sessions.rename(session.id, title),
    onDeleteSession: setDeleteTarget,
    onExportHistory: setExportFormat,
    onLogout: () => void logout(),
  };
  const fallbackPatient = evidencePatient(conversation.lastResponse?.evidence);
  const shellStyle = {
    "--sidebar-width": sidebarCollapsed ? "76px" : "320px",
    "--right-panel-fixed-width": "380px",
  } as CSSProperties;

  return (
    <main className="chat-shell relative h-screen overflow-hidden bg-background text-foreground" style={shellStyle}>
      {banner ? (
        <div className="absolute left-1/2 top-4 z-40 -translate-x-1/2 rounded-full border border-danger/20 bg-white px-4 py-2 text-sm font-semibold text-danger shadow-card">
          {banner}
        </div>
      ) : null}

      {view === "usage" ? (
        <motion.div
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          className="absolute inset-0 z-20 h-full min-h-0 overflow-hidden lg:pl-[var(--sidebar-width)]"
        >
          <UsagePanel onClose={() => setView("chat")} className="h-full" />
        </motion.div>
      ) : (
        <>
          <motion.div initial={{ opacity: 0 }} animate={{ opacity: 1 }} className="absolute inset-0 h-full min-h-0 overflow-hidden">
            <ChatWindow
              staff={staff}
              displayName={user.display_name || user.username}
              messages={conversation.messages}
              sending={conversation.sending}
              rightPanelOpen={rightPanelOpen}
              onSubmitMessage={(message) => void ask(message)}
              onSelectCandidate={(candidate, pendingQuestion) => void selectCandidate(candidate, pendingQuestion)}
              onRate={conversation.rate}
              onRemoveRating={conversation.removeRating}
              actions={
                <>
                  <Button
                    type="button"
                    variant="ghost"
                    size="icon"
                    className="pointer-events-auto hidden xl:inline-flex"
                    onClick={() => setRightPanelOpen((open) => !open)}
                    aria-label={rightPanelOpen ? "Đóng panel bên phải" : "Mở panel bên phải"}
                    title={rightPanelOpen ? "Đóng panel bên phải" : "Mở panel bên phải"}
                  >
                    {rightPanelOpen ? <PanelRightClose className="h-5 w-5" /> : <PanelRightOpen className="h-5 w-5" />}
                  </Button>
                  <div className="pointer-events-auto">
                    <NotificationPopover
                      open={notificationOpen}
                      notifications={notifications.notifications}
                      unreadCount={notifications.unread_count}
                      onToggle={() => setNotificationOpen((open) => !open)}
                      onMarkRead={notifications.markRead}
                      onMarkAllRead={notifications.markAllRead}
                    />
                  </div>
                </>
              }
            />
          </motion.div>

          {rightPanelOpen ? (
            <PatientInsightPanel
              staff={staff}
              panel={patientPanel}
              fallbackPatient={fallbackPatient}
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
          {...sidebarProps}
          collapsed={sidebarCollapsed}
          onToggleCollapsed={() => setSidebarCollapsed((collapsed) => !collapsed)}
          onNewChat={newChat}
          onSelectSession={(session) => void openSession(session)}
          onOpenUsage={() => setView("usage")}
          className="pointer-events-auto h-full w-full"
        />
      </div>

      <div className="fixed bottom-4 left-4 z-40 lg:hidden">
        <Button type="button" variant="secondary" onClick={() => setMobileHistoryOpen(true)}>
          Lịch sử
        </Button>
      </div>

      {mobileHistoryOpen ? (
        <MobileSheet title="Lịch sử" side="left" onClose={() => setMobileHistoryOpen(false)}>
          <HistorySidebar
            {...sidebarProps}
            collapsed={false}
            onToggleCollapsed={() => setMobileHistoryOpen(false)}
            onNewChat={() => {
              newChat();
              setMobileHistoryOpen(false);
            }}
            onSelectSession={(session) => {
              void openSession(session);
              setMobileHistoryOpen(false);
            }}
            onOpenUsage={() => {
              setView("usage");
              setMobileHistoryOpen(false);
            }}
            className="h-[calc(100%-65px)] border-r-0"
          />
        </MobileSheet>
      ) : null}

      {mobilePanelOpen ? (
        <MobileSheet title="Thông tin" side="right" onClose={() => setMobilePanelOpen(false)}>
          <PatientInsightPanel staff={staff} panel={patientPanel} fallbackPatient={fallbackPatient} mobile />
        </MobileSheet>
      ) : null}

      <ExportHistoryModal format={exportFormat} onClose={() => setExportFormat(null)} />
      <DeleteSessionModal session={deleteTarget} onConfirm={deleteSession} onClose={() => setDeleteTarget(null)} />
    </main>
  );
}
