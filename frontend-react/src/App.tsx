import { Navigate, Route, Routes, useLocation } from "react-router-dom";
import { useAuth } from "./lib/auth";
import { Spinner } from "./components/ui/Spinner";
import { ForbiddenPage } from "./components/dashboard/ForbiddenPage";
import { LoginPage } from "./routes/LoginPage";
import { RegisterPage } from "./routes/RegisterPage";
import { OnboardingPage } from "./routes/OnboardingPage";
import { ChatPage } from "./routes/ChatPage";
import { UsagePage } from "./routes/UsagePage";
import { AdminDashboardPage } from "./routes/AdminDashboardPage";
import { AdminUsersPage } from "./routes/AdminUsersPage";
import { AdminConfigPage } from "./routes/AdminConfigPage";
import { AdminUsageCostPage } from "./routes/AdminUsageCostPage";
import { AdminAuditLogsPage } from "./routes/AdminAuditLogsPage";

function ProtectedRoute({ children }: { children: React.ReactNode }) {
  const { user, loading } = useAuth();
  const location = useLocation();

  if (loading) {
    return (
      <div className="grid min-h-screen place-items-center bg-background">
        <div className="flex items-center gap-3 rounded-2xl border border-border bg-white px-5 py-4 shadow-card">
          <Spinner className="text-accent" />
          <span className="font-medium text-muted-foreground">Đang khôi phục phiên đăng nhập...</span>
        </div>
      </div>
    );
  }

  if (!user) {
    return <Navigate to="/login" replace />;
  }

  if (user.role === "USER" && user.onboarding_required && location.pathname !== "/onboarding") {
    return <Navigate to="/onboarding" replace />;
  }

  return children;
}

function AdminRoute({ children }: { children: React.ReactNode }) {
  const { user } = useAuth();

  if (user?.role !== "ADMIN") {
    return <ForbiddenPage />;
  }

  return children;
}

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/register" element={<RegisterPage />} />
      <Route
        path="/onboarding"
        element={
          <ProtectedRoute>
            <OnboardingPage />
          </ProtectedRoute>
        }
      />
      <Route
        path="/chat"
        element={
          <ProtectedRoute>
            <ChatPage />
          </ProtectedRoute>
        }
      />
      <Route
        path="/usage"
        element={
          <ProtectedRoute>
            <UsagePage />
          </ProtectedRoute>
        }
      />
      <Route
        path="/admin"
        element={
          <ProtectedRoute>
            <AdminRoute>
              <AdminDashboardPage />
            </AdminRoute>
          </ProtectedRoute>
        }
      />
      <Route
        path="/admin/users"
        element={
          <ProtectedRoute>
            <AdminRoute>
              <AdminUsersPage />
            </AdminRoute>
          </ProtectedRoute>
        }
      />
      <Route
        path="/admin/config"
        element={
          <ProtectedRoute>
            <AdminRoute>
              <AdminConfigPage />
            </AdminRoute>
          </ProtectedRoute>
        }
      />
      <Route
        path="/admin/usage-cost"
        element={
          <ProtectedRoute>
            <AdminRoute>
              <AdminUsageCostPage />
            </AdminRoute>
          </ProtectedRoute>
        }
      />
      <Route
        path="/admin/audit-logs"
        element={
          <ProtectedRoute>
            <AdminRoute>
              <AdminAuditLogsPage />
            </AdminRoute>
          </ProtectedRoute>
        }
      />
      <Route path="*" element={<Navigate to="/chat" replace />} />
    </Routes>
  );
}
