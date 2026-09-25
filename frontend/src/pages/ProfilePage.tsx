import { FormEvent, useState } from "react";
import { useNavigate } from "react-router-dom";
import { motion } from "framer-motion";
import { ArrowLeft, Eye, EyeOff, KeyRound, UserRound } from "lucide-react";
import { ApiError, apiPost, apiPut } from "../services/api";
import { useAuth } from "../hooks/useAuth";
import { TEXT } from "../lib/constants";
import { roleLabel } from "../lib/formatters";
import type { AuthUser } from "../lib/types";
import { Button } from "../components/ui/Button";
import { Badge } from "../components/ui/Badge";
import { Field, inputClass } from "../components/ui/Field";
import { Spinner } from "../components/ui/Spinner";

function ErrorBanner({ message }: { message: string }) {
  return (
    <p className="mb-5 rounded-xl border border-danger/20 bg-danger/10 px-4 py-3 text-sm font-medium text-danger">
      {message}
    </p>
  );
}

function SuccessBanner({ message }: { message: string }) {
  return (
    <p className="mb-5 rounded-xl border border-success/20 bg-success/10 px-4 py-3 text-sm font-medium text-success">
      {message}
    </p>
  );
}

function ProfileInfoCard({ user }: { user: AuthUser }) {
  const { restore } = useAuth();

  const [displayName, setDisplayName] = useState(user.display_name);
  const [email, setEmail] = useState(user.email ?? "");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setSubmitting(true);
    setError(null);
    setSuccess(null);
    try {
      await apiPut<AuthUser>("/api/auth/me", {
        display_name: displayName,
        email,
      });
      await restore();
      setSuccess(TEXT.profileSaveSuccess);
    } catch (err) {
      if (err instanceof ApiError) {
        setError(err.detail || "Không thể cập nhật thông tin. Vui lòng thử lại.");
      } else {
        setError("Không thể cập nhật thông tin. Vui lòng thử lại.");
      }
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <section className="rounded-3xl border border-border bg-white p-6 shadow-lift md:p-8">
      <header className="mb-6 flex items-center gap-3">
        <span className="grid h-10 w-10 place-items-center rounded-xl bg-accent/10 text-accent">
          <UserRound className="h-5 w-5" />
        </span>
        <div>
          <h2 className="font-display text-xl">{TEXT.profileInfoSection}</h2>
          <div className="mt-1 flex items-center gap-2 text-sm text-muted-foreground">
            <span>{user.username}</span>
            <Badge tone="blue" className="px-2 py-0.5 text-[10px]">
              {roleLabel(user.role)}
            </Badge>
          </div>
        </div>
      </header>

      {error ? <ErrorBanner message={error} /> : null}
      {success ? <SuccessBanner message={success} /> : null}

      <form className="space-y-5" onSubmit={handleSubmit}>
        <Field label={TEXT.profileUsernameLabel}>
          <input className={inputClass()} value={user.username} type="text" disabled />
        </Field>
        <Field label={TEXT.profileDisplayNameLabel}>
          <input
            className={inputClass()}
            value={displayName}
            onChange={(event) => setDisplayName(event.target.value)}
            type="text"
            minLength={2}
            maxLength={100}
            autoComplete="name"
            required
          />
        </Field>
        <Field label={TEXT.profileEmailLabel}>
          <input
            className={inputClass()}
            value={email}
            onChange={(event) => setEmail(event.target.value)}
            type="email"
            autoComplete="email"
            required
          />
        </Field>
        <Button type="submit" variant="primary" size="lg" className="w-full" disabled={submitting}>
          {submitting ? <Spinner /> : null}
          {TEXT.profileSave}
        </Button>
      </form>
    </section>
  );
}

function ChangePasswordCard() {
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
    <section className="rounded-3xl border border-border bg-white p-6 shadow-lift md:p-8">
      <header className="mb-6 flex items-center gap-3">
        <span className="grid h-10 w-10 place-items-center rounded-xl bg-accent/10 text-accent">
          <KeyRound className="h-5 w-5" />
        </span>
        <div>
          <h2 className="font-display text-xl">{TEXT.changePasswordTitle}</h2>
          <p className="mt-1 text-sm text-muted-foreground">{TEXT.changePasswordSubtitle}</p>
        </div>
      </header>

      {error ? <ErrorBanner message={error} /> : null}

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
    </section>
  );
}

export function ProfilePage() {
  const { user } = useAuth();
  const navigate = useNavigate();

  if (!user) {
    return null;
  }

  return (
    <main className="min-h-screen bg-background px-5 py-10 text-foreground">
      <motion.div
        initial={{ opacity: 0, y: 16 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.4 }}
        className="mx-auto w-full max-w-2xl"
      >
        <div className="mb-8 flex flex-col items-center text-center">
          <span className="grid h-14 w-14 place-items-center rounded-2xl gradient-surface text-white shadow-lift">
            <UserRound className="h-7 w-7" />
          </span>
          <h1 className="mt-5 font-display text-3xl">{TEXT.profileTitle}</h1>
          <p className="mt-2 text-sm leading-6 text-muted-foreground">{TEXT.profileSubtitle}</p>
        </div>

        <div className="grid gap-6">
          <ProfileInfoCard user={user} />
          <ChangePasswordCard />
        </div>

        <button
          type="button"
          className="focus-ring mt-6 flex w-full items-center justify-center gap-2 rounded-lg py-1 text-sm font-medium text-accent hover:underline"
          onClick={() => navigate(-1)}
        >
          <ArrowLeft className="h-4 w-4" />
          {TEXT.cancel}
        </button>
      </motion.div>
    </main>
  );
}
