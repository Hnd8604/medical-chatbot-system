import { FormEvent, useState } from "react";
import { Link, Navigate, useLocation, useNavigate } from "react-router-dom";
import { motion } from "framer-motion";
import { LockKeyhole, ShieldCheck } from "lucide-react";
import { ApiError } from "../services/api";
import { useAuth } from "../hooks/useAuth";
import { TEXT } from "../lib/constants";
import { Button } from "../components/ui/Button";
import { Field, inputClass } from "../components/ui/Field";
import { SectionLabel } from "../components/ui/SectionLabel";
import { Spinner } from "../components/ui/Spinner";

export function LoginPage() {
  const { user, loading, login } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const locationState = location.state as { registrationMessage?: string } | null;
  const [usernameOrEmail, setUsernameOrEmail] = useState("");
  const [password, setPassword] = useState("");
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
    <main className="min-h-screen overflow-hidden bg-background px-5 py-8 text-foreground">
      <div className="mx-auto grid min-h-[calc(100vh-4rem)] max-w-6xl items-center gap-10 lg:grid-cols-[1.05fr_0.95fr]">
        <motion.section
          initial={{ opacity: 0, y: 24 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.55 }}
          className="space-y-7"
        >
          <SectionLabel>FHIR Chat</SectionLabel>
          <div className="space-y-5">
            <h1 className="max-w-3xl font-display text-5xl leading-[1.08] text-foreground md:text-7xl">
              {TEXT.loginTitle} <span className="gradient-text">chính thức</span>
            </h1>
            <p className="max-w-2xl text-lg leading-8 text-muted-foreground">{TEXT.loginSubtitle}</p>
          </div>
          <div className="grid gap-4 sm:grid-cols-2">
            <div className="rounded-2xl border border-border bg-white p-5 shadow-card">
              <ShieldCheck className="mb-4 h-7 w-7 text-accent" />
              <h2 className="font-semibold">Phân quyền rõ ràng</h2>
              <p className="mt-2 text-sm leading-6 text-muted-foreground">
                USER, DOCTOR và ADMIN có trải nghiệm riêng theo đúng phạm vi truy cập.
              </p>
            </div>
            <div className="rounded-2xl bg-foreground p-5 text-white shadow-lift dark-dots">
              <LockKeyhole className="mb-4 h-7 w-7 text-white" />
              <h2 className="font-semibold">Dữ liệu qua Spring</h2>
              <p className="mt-2 text-sm leading-6 text-white/75">
                Frontend chỉ gọi Spring backend, giữ FHIR service phía sau lớp bảo vệ.
              </p>
            </div>
          </div>
        </motion.section>

        <motion.section
          initial={{ opacity: 0, y: 20 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.55, delay: 0.1 }}
          className="rounded-3xl border border-border bg-white p-6 shadow-lift md:p-8"
        >
          <div className="mb-7">
            <p className="font-mono text-xs uppercase tracking-[0.15em] text-accent">Đăng nhập</p>
            <h2 className="mt-2 font-display text-3xl">Truy cập hệ thống</h2>
          </div>

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
              <input
                className={inputClass()}
                value={password}
                onChange={(event) => setPassword(event.target.value)}
                type="password"
                autoComplete="current-password"
                required
              />
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

          <p className="mt-5 text-center text-sm text-muted-foreground">
            Chưa có tài khoản?{" "}
            <Link className="font-semibold text-accent hover:underline" to="/register">
              Đăng ký
            </Link>
          </p>

          <details className="mt-7 rounded-2xl border border-border bg-muted/60 p-4 text-sm">
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
      </div>
    </main>
  );
}
