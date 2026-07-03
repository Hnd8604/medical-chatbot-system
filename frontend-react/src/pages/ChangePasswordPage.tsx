import { FormEvent, useState } from "react";
import { useNavigate } from "react-router-dom";
import { motion } from "framer-motion";
import { ArrowLeft, Eye, EyeOff, KeyRound } from "lucide-react";
import { ApiError, apiPost } from "../services/api";
import { useAuth } from "../hooks/useAuth";
import { TEXT } from "../lib/constants";
import { Button } from "../components/ui/Button";
import { Field, inputClass } from "../components/ui/Field";
import { Spinner } from "../components/ui/Spinner";

export function ChangePasswordPage() {
  const { logout } = useAuth();
  const navigate = useNavigate();

  const [currentPassword, setCurrentPassword] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [passwordConfirmation, setPasswordConfirmation] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setSubmitting(true);
    setError(null);
    try {
      await apiPost("/api/auth/change-password", {
        current_password: currentPassword,
        new_password: newPassword,
        password_confirmation: passwordConfirmation,
      });
      // Backend thu hồi mọi phiên cũ nên bắt buộc đăng nhập lại bằng mật khẩu mới.
      await logout();
      navigate("/login", {
        replace: true,
        state: { registrationMessage: TEXT.changePasswordSuccess },
      });
    } catch (err) {
      if (err instanceof ApiError) {
        setError(err.detail || "Không thể đổi mật khẩu. Vui lòng thử lại.");
      } else {
        setError("Không thể đổi mật khẩu. Vui lòng thử lại.");
      }
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
          <h1 className="mt-5 font-display text-3xl">{TEXT.changePasswordTitle}</h1>
          <p className="mt-2 text-sm leading-6 text-muted-foreground">{TEXT.changePasswordSubtitle}</p>
        </div>

        <div className="rounded-3xl border border-border bg-white p-6 shadow-lift md:p-8">
          {error ? (
            <p className="mb-5 rounded-xl border border-danger/20 bg-danger/10 px-4 py-3 text-sm font-medium text-danger">
              {error}
            </p>
          ) : null}

          <form className="space-y-5" onSubmit={handleSubmit}>
            <Field label={TEXT.changePasswordCurrentLabel}>
              <div className="relative">
                <input
                  className={inputClass("pr-12")}
                  value={currentPassword}
                  onChange={(event) => setCurrentPassword(event.target.value)}
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
            <Field label={TEXT.changePasswordNewLabel}>
              <input
                className={inputClass()}
                value={newPassword}
                onChange={(event) => setNewPassword(event.target.value)}
                type={showPassword ? "text" : "password"}
                autoComplete="new-password"
                required
              />
            </Field>
            <Field label={TEXT.changePasswordConfirmLabel}>
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
              {TEXT.changePasswordSubmit}
            </Button>
          </form>

          <button
            type="button"
            className="focus-ring mt-6 flex w-full items-center justify-center gap-2 rounded-lg py-1 text-sm font-medium text-accent hover:underline"
            onClick={() => navigate(-1)}
          >
            <ArrowLeft className="h-4 w-4" />
            {TEXT.cancel}
          </button>
        </div>
      </motion.section>
    </main>
  );
}
