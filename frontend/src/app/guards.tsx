import { Navigate, Outlet, useLocation } from "react-router-dom";
import { useAuth } from "@/features/auth/AuthProvider";
import { homePath } from "@/features/auth/labels";
import { Spinner } from "@/shared/ui/Spinner";
import { ForbiddenPage } from "./ForbiddenPage";

function RestoringSession() {
  return (
    <div className="grid min-h-screen place-items-center bg-background">
      <div className="flex items-center gap-3 rounded-2xl border border-border bg-white px-5 py-4 shadow-card">
        <Spinner className="text-accent" />
        <span className="font-medium text-muted-foreground">Đang khôi phục phiên đăng nhập...</span>
      </div>
    </div>
  );
}

/** Signed-in users only; USER accounts are held on onboarding until they link a patient record. */
export function ProtectedRoute() {
  const { user, loading } = useAuth();
  const location = useLocation();

  if (loading) {
    return <RestoringSession />;
  }
  if (!user) {
    return <Navigate to="/login" replace />;
  }
  if (user.role === "USER" && user.onboardingRequired && location.pathname !== "/onboarding") {
    return <Navigate to="/onboarding" replace />;
  }
  return <Outlet />;
}

export function AdminRoute() {
  const { user } = useAuth();
  return user?.role === "ADMIN" ? <Outlet /> : <ForbiddenPage />;
}

/** The chat screen is for patients and doctors; admins work from the dashboard. */
export function NonAdminRoute() {
  const { user } = useAuth();
  return user?.role === "ADMIN" ? <Navigate to="/admin" replace /> : <Outlet />;
}

export function HomeRedirect() {
  const { user } = useAuth();
  return user ? <Navigate to={homePath(user)} replace /> : <Navigate to="/login" replace />;
}
