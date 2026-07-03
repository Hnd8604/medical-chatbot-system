import { FormEvent, useState } from "react";
import { Link, Navigate, useNavigate } from "react-router-dom";
import { motion } from "framer-motion";
import { Eye, EyeOff, KeyRound } from "lucide-react";
import { ApiError, apiPost } from "../services/api";
import { useAuth } from "../hooks/useAuth";
import { TEXT } from "../lib/constants";
import { Button } from "../components/ui/Button";
import { Field, inputClass } from "../components/ui/Field";
import { Spinner } from "../components/ui/Spinner";

type Step = "email" | "code" | "password";

export function ForgotPasswordPage() {
  const { user, loading } = useAuth();
  const navigate = useNavigate();

  const [step, setStep] = useState<Step>("email");
  const [email, setEmail] = useState("");
  const [code, setCode] = useState("");
  const [resetTicket, setResetTicket] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [passwordConfirmation, setPasswordConfirmation] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [info, setInfo] = useState<string | null>(null);

  // Người dùng đã đăng nhập thì không cần luồng quên mật khẩu.
  if (!loading && user) {
    return <Navigate to={user.role === "ADMIN" ? "/admin" : "/chat"} replace />;
  }

  const subtitle =
    step === "email"
      ? TEXT.forgotPasswordEmailSubtitle
      : step === "code"
        ? TEXT.forgotPasswordCodeSubtitle
        : TEXT.forgotPasswordResetSubtitle;

  function fail(err: unknown, fallback: string) {
    if (err instanceof ApiError) {
      setError(err.detail || fallback);
    } else {
      setError(fallback);
    }
  }

  async function handleSendCode(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setSubmitting(true);
    setError(null);
    try {
      const response = await apiPost<{ message: string }>("/api/auth/forgot-password", {
        email: email.trim(),
      });
      setInfo(response.message);
      setStep("code");
    } catch (err) {
      fail(err, "Không thể gửi mã. Vui lòng thử lại.");
    } finally {
      setSubmitting(false);
    }
  }

  async function handleVerifyCode(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setSubmitting(true);
    setError(null);
    try {
      const response = await apiPost<{ reset_ticket: string; message: string }>(
        "/api/auth/verify-reset-code",
        { email: email.trim(), code: code.trim() },
      );
      setResetTicket(response.reset_ticket);
      setInfo(null);
      setStep("password");
    } catch (err) {
      fail(err, "Mã xác thực không đúng hoặc đã hết hạn.");
    } finally {
      setSubmitting(false);
    }
  }

  async function handleResend() {
    setSubmitting(true);
    setError(null);
    try {
      const response = await apiPost<{ message: string }>("/api/auth/forgot-password", {
        email: email.trim(),
      });
      setInfo(response.message);
    } catch (err) {
      fail(err, "Không thể gửi lại mã. Vui lòng thử lại.");
    } finally {
      setSubmitting(false);
    }
  }

  async function handleResetPassword(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setSubmitting(true);
    setError(null);
    try {
      await apiPost("/api/auth/reset-password", {
        reset_ticket: resetTicket,
        new_password: newPassword,
        password_confirmation: passwordConfirmation,
      });
      navigate("/login", {
        replace: true,
        state: { registrationMessage: TEXT.forgotPasswordSuccess },
      });
    } catch (err) {
      fail(err, "Không thể đặt lại mật khẩu. Vui lòng thử lại.");
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
            <KeyRound className="h-7 w-7" />
          </span>
          <h1 className="mt-5 font-display text-3xl">{TEXT.forgotPasswordTitle}</h1>
          <p className="mt-2 text-sm leading-6 text-muted-foreground">{subtitle}</p>
        </div>

        <div className="rounded-3xl border border-border bg-white p-6 shadow-lift md:p-8">
          {info ? (
            <p className="mb-5 rounded-xl border border-success/20 bg-success/10 px-4 py-3 text-sm font-medium text-success">
              {info}
            </p>
          ) : null}
          {error ? (
            <p className="mb-5 rounded-xl border border-danger/20 bg-danger/10 px-4 py-3 text-sm font-medium text-danger">
              {error}
            </p>
          ) : null}

          {step === "email" ? (
            <form className="space-y-5" onSubmit={handleSendCode}>
              <Field label={TEXT.forgotPasswordEmailLabel}>
                <input
                  className={inputClass()}
                  value={email}
                  onChange={(event) => setEmail(event.target.value)}
                  type="email"
                  autoComplete="email"
                  spellCheck={false}
                  required
                />
              </Field>
              <Button type="submit" variant="primary" size="lg" className="w-full" disabled={submitting}>
                {submitting ? <Spinner /> : null}
                {TEXT.forgotPasswordSendCode}
              </Button>
            </form>
          ) : null}

          {step === "code" ? (
            <form className="space-y-5" onSubmit={handleVerifyCode}>
              <Field label={TEXT.forgotPasswordCodeLabel}>
                <input
                  className={inputClass("tracking-[0.5em] text-center font-mono")}
                  value={code}
                  onChange={(event) => setCode(event.target.value.replace(/\D/g, "").slice(0, 6))}
                  inputMode="numeric"
                  autoComplete="one-time-code"
                  maxLength={6}
                  required
                />
              </Field>
              <Button type="submit" variant="primary" size="lg" className="w-full" disabled={submitting}>
                {submitting ? <Spinner /> : null}
                {TEXT.forgotPasswordVerifyCode}
              </Button>
              <button
                type="button"
                className="focus-ring w-full rounded-lg py-1 text-sm font-medium text-accent hover:underline disabled:opacity-60"
                onClick={handleResend}
                disabled={submitting}
              >
                {TEXT.forgotPasswordResend}
              </button>
            </form>
          ) : null}

          {step === "password" ? (
            <form className="space-y-5" onSubmit={handleResetPassword}>
              <Field label={TEXT.forgotPasswordNewPasswordLabel}>
                <div className="relative">
                  <input
                    className={inputClass("pr-12")}
                    value={newPassword}
                    onChange={(event) => setNewPassword(event.target.value)}
                    type={showPassword ? "text" : "password"}
                    autoComplete="new-password"
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
              <Field label={TEXT.forgotPasswordConfirmLabel}>
                <input
                  className={inputClass()}
                  value={passwordConfirmation}
                  onChange={(event) => setPasswordConfirmation(event.target.value)}
                  type={showPassword ? "text" : "password"}
                  autoComplete="new-password"
                  required
                />
              </Field>
              <Button type="submit" variant="primary" size="lg" className="w-full" disabled={submitting}>
                {submitting ? <Spinner /> : null}
                {TEXT.forgotPasswordSubmit}
              </Button>
            </form>
          ) : null}

          <p className="mt-6 text-center text-sm text-muted-foreground">
            <Link className="font-semibold text-accent hover:underline" to="/login">
              {TEXT.backToLogin}
            </Link>
          </p>
        </div>
      </motion.section>
    </main>
  );
}
