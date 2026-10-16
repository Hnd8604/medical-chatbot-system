import { lazy, Suspense, type ComponentType } from "react";
import { Route, Routes } from "react-router-dom";
import { LoadingPanel } from "@/shared/ui/LoadingPanel";
import { DashboardLayout } from "./DashboardLayout";
import { AdminRoute, HomeRedirect, NonAdminRoute, ProtectedRoute } from "./guards";

/** Code-splits a page exported by name. */
function page<K extends string>(load: () => Promise<Record<K, ComponentType>>, name: K) {
  return lazy(() => load().then((module) => ({ default: module[name] })));
}

const LoginPage = page(() => import("@/features/auth/pages/LoginPage"), "LoginPage");
const RegisterPage = page(() => import("@/features/auth/pages/RegisterPage"), "RegisterPage");
const ForgotPasswordPage = page(() => import("@/features/auth/pages/ForgotPasswordPage"), "ForgotPasswordPage");
const OnboardingPage = page(() => import("@/features/auth/pages/OnboardingPage"), "OnboardingPage");
const ProfilePage = page(() => import("@/features/auth/pages/ProfilePage"), "ProfilePage");
const ChangePasswordPage = page(() => import("@/features/auth/pages/ChangePasswordPage"), "ChangePasswordPage");
const ChatPage = page(() => import("@/features/chat/pages/ChatPage"), "ChatPage");
const UsagePage = page(() => import("@/features/usage/pages/UsagePage"), "UsagePage");
const AdminDashboardPage = page(() => import("@/features/admin/dashboard/pages/AdminDashboardPage"), "AdminDashboardPage");
const AdminAnalyticsPage = page(() => import("@/features/admin/analytics/pages/AdminAnalyticsPage"), "AdminAnalyticsPage");
const AdminAlertsPage = page(() => import("@/features/admin/alerts/pages/AdminAlertsPage"), "AdminAlertsPage");
const AdminUsersPage = page(() => import("@/features/admin/users/pages/AdminUsersPage"), "AdminUsersPage");
const QuotaPoliciesPage = page(() => import("@/features/admin/quota-policies/pages/QuotaPoliciesPage"), "QuotaPoliciesPage");
const AdminUsageCostPage = page(() => import("@/features/admin/usage-cost/pages/AdminUsageCostPage"), "AdminUsageCostPage");
const AdminAuditLogsPage = page(() => import("@/features/admin/audit-logs/pages/AdminAuditLogsPage"), "AdminAuditLogsPage");
const AdminBackupPage = page(() => import("@/features/admin/backups/pages/AdminBackupPage"), "AdminBackupPage");

export function App() {
  return (
    <Suspense fallback={<LoadingPanel label="Đang tải..." className="min-h-screen rounded-none border-0" />}>
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route path="/register" element={<RegisterPage />} />
        <Route path="/forgot-password" element={<ForgotPasswordPage />} />

        <Route element={<ProtectedRoute />}>
          <Route path="/onboarding" element={<OnboardingPage />} />
          <Route path="/profile" element={<ProfilePage />} />
          <Route path="/change-password" element={<ChangePasswordPage />} />
          <Route element={<NonAdminRoute />}>
            <Route path="/chat" element={<ChatPage />} />
          </Route>
          <Route element={<DashboardLayout />}>
            <Route path="/usage" element={<UsagePage />} />
          </Route>
          <Route path="/admin" element={<AdminRoute />}>
            <Route element={<DashboardLayout />}>
              <Route index element={<AdminDashboardPage />} />
              <Route path="analytics" element={<AdminAnalyticsPage />} />
              <Route path="alerts" element={<AdminAlertsPage />} />
              <Route path="users" element={<AdminUsersPage />} />
              <Route path="config" element={<QuotaPoliciesPage />} />
              <Route path="usage-cost" element={<AdminUsageCostPage />} />
              <Route path="audit-logs" element={<AdminAuditLogsPage />} />
              <Route path="backup" element={<AdminBackupPage />} />
            </Route>
          </Route>
          <Route path="*" element={<HomeRedirect />} />
        </Route>
      </Routes>
    </Suspense>
  );
}
