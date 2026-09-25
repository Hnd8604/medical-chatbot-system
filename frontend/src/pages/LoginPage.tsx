import { FormEvent, useState } from "react";
import { Link, Navigate, useLocation, useNavigate } from "react-router-dom";
import { motion } from "framer-motion";
import { Eye, EyeOff, Stethoscope } from "lucide-react";
import { ApiError } from "../services/api";
import { useAuth } from "../hooks/useAuth";
import { TEXT } from "../lib/constants";
import { Button } from "../components/ui/Button";
import { Field, inputClass } from "../components/ui/Field";
import { Spinner } from "../components/ui/Spinner";

export function LoginPage() {
  const { user, loading, login } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const locationState = location.state as { registrationMessage?: string } | null;
  const [usernameOrEmail, setUsernameOrEmail] = useState("");
  const [password, setPassword] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  if (!loading && user) {
    return (
      <Navigate
        to={
          user.role === "ADMIN"
            ? "/admin"
            : user.onboarding_required
              ? "/onboarding"
              : "/chat"
        }
        replace
      />
    );
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setSubmitting(true);
    setError(null);
    try {
      const loggedInUser = await login(usernameOrEmail.trim(), password);
      navigate(
        loggedInUser.role === "ADMIN"
          ? "/admin"
          : loggedInUser.onboarding_required
            ? "/onboarding"
            : "/chat",
        { replace: true },
      );
    } catch (err) {
      if (err instanceof ApiError) {
        setError(err.detail || "Không thể đăng nhập. Vui lòng kiểm tra lại thông tin.");
      } else {
        setError("Không thể đăng nhập. Vui lòng thử lại.");
      }
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <main className="grid min-h-screen place-items-center bg-background px-5 py-10 text-foreground">
      <motion.section
        initial={{ opacity: 0, y: 16 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.4 }}
        className="w-full max-w-md"
      >
        <div className="mb-8 flex flex-col items-center text-center">
          <span className="grid h-14 w-14 place-items-center rounded-2xl gradient-surface text-white shadow-lift">
            <Stethoscope className="h-7 w-7" />
          </span>
          <h1 className="mt-5 font-display text-3xl">{TEXT.loginTitle}</h1>
          <p className="mt-2 text-sm leading-6 text-muted-foreground">{TEXT.loginSubtitle}</p>
        </div>

        <div className="rounded-3xl border border-border bg-white p-6 shadow-lift md:p-8">
          <form className="space-y-5" onSubmit={handleSubmit}>
            {locationState?.registrationMessage ? (
              <p className="rounded-xl border border-success/20 bg-success/10 px-4 py-3 text-sm font-medium text-success">
                {locationState.registrationMessage}
              </p>
            ) : null}
            <Field label={TEXT.usernameLabel}>
              <input
                className={inputClass()}
                value={usernameOrEmail}
                onChange={(event) => setUsernameOrEmail(event.target.value)}
                autoComplete="username"
                spellCheck={false}
                required
              />
            </Field>
            <Field label={TEXT.passwordLabel}>
              <div className="relative">
                <input
                  className={inputClass("pr-12")}
                  value={password}
                  onChange={(event) => setPassword(event.target.value)}
                  type={showPassword ? "text" : "password"}
                  autoComplete="current-password"
                  required
                />
                <button
                  type="button"
                  className="focus-ring absolute right-2 top-1/2 grid h-9 w-9 -translate-y-1/2 place-items-center rounded-lg text-muted-foreground hover:bg-muted hover:text-foreground"
                  aria-label={showPassword ? "Ẩn mật khẩu" : "Hiện mật khẩu"}
                  onClick={() => setShowPassword((value) => !value)}
                >
                  {showPassword ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
                </button>
              </div>
            </Field>
            {error ? (
              <p className="rounded-xl border border-danger/20 bg-danger/10 px-4 py-3 text-sm font-medium text-danger">
                {error}
              </p>
            ) : null}
            <Button type="submit" variant="primary" size="lg" className="w-full" disabled={submitting}>
              {submitting ? <Spinner /> : null}
              {TEXT.loginButton}
            </Button>
          </form>

          <p className="mt-4 text-center text-sm">
            <Link className="font-semibold text-accent hover:underline" to="/forgot-password">
              {TEXT.forgotPasswordLink}
            </Link>
          </p>

          <p className="mt-6 text-center text-sm text-muted-foreground">
            Chưa có tài khoản?{" "}
            <Link className="font-semibold text-accent hover:underline" to="/register">
              Đăng ký
            </Link>
          </p>
        </div>

        <details className="mt-6 rounded-2xl border border-border bg-muted/50 p-4 text-sm">
          <summary className="cursor-pointer font-semibold text-foreground">Tài khoản demo local</summary>
          <dl className="mt-4 grid gap-3 text-muted-foreground">
            <div className="flex items-center justify-between gap-3">
              <dt>Người dùng</dt>
              <dd className="font-mono text-foreground">user_demo / UserDemo123!</dd>
            </div>
            <div className="flex items-center justify-between gap-3">
              <dt>Bác sĩ</dt>
              <dd className="font-mono text-foreground">doctor_demo / DoctorDemo123!</dd>
            </div>
            <div className="flex items-center justify-between gap-3">
              <dt>Quản trị viên</dt>
              <dd className="font-mono text-foreground">admin_demo / AdminDemo123!</dd>
            </div>
          </dl>
        </details>
      </motion.section>
    </main>
  );
}
