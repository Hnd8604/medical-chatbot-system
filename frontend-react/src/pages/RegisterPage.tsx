import { FormEvent, useState } from "react";
import { Link, Navigate, useNavigate } from "react-router-dom";
import { motion } from "framer-motion";
import { Eye, EyeOff, ShieldPlus, UserRoundPlus } from "lucide-react";
import { apiPost, ApiError } from "../services/api";
import { useAuth } from "../hooks/useAuth";
import type { AuthRegisterResponse } from "../lib/types";
import { Button } from "../components/ui/Button";
import { Field, inputClass } from "../components/ui/Field";
import { SectionLabel } from "../components/ui/SectionLabel";
import { Spinner } from "../components/ui/Spinner";

interface RegisterForm {
  displayName: string;
  username: string;
  email: string;
  password: string;
  passwordConfirmation: string;
}

const initialForm: RegisterForm = {
  displayName: "",
  username: "",
  email: "",
  password: "",
  passwordConfirmation: "",
};

function validateForm(form: RegisterForm) {
  const username = form.username.trim();
  const email = form.email.trim();

  if (!/^[A-Za-z0-9._-]{3,30}$/.test(username)) {
    return "Tên đăng nhập phải có 3-30 ký tự và chỉ gồm chữ, số, dấu chấm, gạch dưới hoặc gạch ngang.";
  }
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
    return "Email không hợp lệ.";
  }
  if (new TextEncoder().encode(form.password).length < 8 || new TextEncoder().encode(form.password).length > 72) {
    return "Mật khẩu phải có từ 8 đến 72 byte.";
  }
  if (!/\p{L}/u.test(form.password) || !/\d/.test(form.password)) {
    return "Mật khẩu phải có ít nhất một chữ và một số.";
  }
  if (form.password !== form.passwordConfirmation) {
    return "Xác nhận mật khẩu không khớp.";
  }
  return null;
}

export function RegisterPage() {
  const { user, loading } = useAuth();
  const navigate = useNavigate();
  const [form, setForm] = useState<RegisterForm>(initialForm);
  const [showPassword, setShowPassword] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  if (!loading && user) {
    return <Navigate to={user.role === "USER" && user.onboarding_required ? "/onboarding" : "/chat"} replace />;
  }

  function updateField<K extends keyof RegisterForm>(key: K, value: RegisterForm[K]) {
    setForm((current) => ({ ...current, [key]: value }));
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const validation = validateForm(form);
    if (validation) {
      setError(validation);
      return;
    }

    setSubmitting(true);
    setError(null);
    try {
      const response = await apiPost<AuthRegisterResponse>("/api/auth/register", {
        display_name: form.displayName.trim(),
        username: form.username.trim(),
        email: form.email.trim(),
        password: form.password,
        password_confirmation: form.passwordConfirmation,
      });
      navigate("/login", {
        replace: true,
        state: { registrationMessage: response.message },
      });
    } catch (err) {
      if (err instanceof ApiError) {
        setError(err.detail || "Không thể đăng ký tài khoản. Vui lòng kiểm tra lại thông tin.");
      } else {
        setError("Không thể đăng ký tài khoản. Vui lòng thử lại.");
      }
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <main className="min-h-screen overflow-hidden bg-background px-5 py-8 text-foreground">
      <div className="mx-auto grid min-h-[calc(100vh-4rem)] max-w-6xl items-center gap-10 lg:grid-cols-[0.95fr_1.05fr]">
        <motion.section
          initial={{ opacity: 0, y: 24 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.55 }}
          className="space-y-7"
        >
          <SectionLabel>Tài khoản USER</SectionLabel>
          <div className="space-y-5">
            <h1 className="max-w-3xl font-display text-5xl leading-[1.08] text-foreground md:text-7xl">
              Tạo tài khoản <span className="gradient-text">Medical Chatbot</span>
            </h1>
            <p className="max-w-2xl text-lg leading-8 text-muted-foreground">
              Tài khoản mới được cấp quyền USER và có thể sử dụng ngay sau khi liên kết với hồ sơ FHIR của chính bạn.
            </p>
          </div>
          <div className="grid gap-4 sm:grid-cols-2">
            <div className="rounded-2xl border border-border bg-white p-5 shadow-card">
              <UserRoundPlus className="mb-4 h-7 w-7 text-accent" />
              <h2 className="font-semibold">Đăng ký nhanh</h2>
              <p className="mt-2 text-sm leading-6 text-muted-foreground">
                Không cần admin duyệt. Bạn chỉ cần email, tên đăng nhập và mật khẩu hợp lệ.
              </p>
            </div>
            <div className="rounded-2xl bg-foreground p-5 text-white shadow-lift dark-dots">
              <ShieldPlus className="mb-4 h-7 w-7 text-white" />
              <h2 className="font-semibold">Phạm vi an toàn</h2>
              <p className="mt-2 text-sm leading-6 text-white/75">
                USER chỉ truy cập được hồ sơ FHIR đã xác minh là của chính mình.
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
            <p className="font-mono text-xs uppercase tracking-[0.15em] text-accent">Đăng ký</p>
            <h2 className="mt-2 font-display text-3xl">Tạo tài khoản mới</h2>
          </div>

          <form className="space-y-5" onSubmit={handleSubmit}>
            <Field label="Họ tên">
              <input
                className={inputClass()}
                value={form.displayName}
                onChange={(event) => updateField("displayName", event.target.value)}
                autoComplete="name"
                required
              />
            </Field>
            <Field label="Tên đăng nhập" hint="Chỉ dùng chữ, số, dấu chấm, gạch dưới hoặc gạch ngang.">
              <input
                className={inputClass()}
                value={form.username}
                onChange={(event) => updateField("username", event.target.value)}
                autoComplete="username"
                spellCheck={false}
                required
              />
            </Field>
            <Field label="Email">
              <input
                className={inputClass()}
                value={form.email}
                onChange={(event) => updateField("email", event.target.value)}
                type="email"
                autoComplete="email"
                spellCheck={false}
                required
              />
            </Field>
            <Field label="Mật khẩu" hint="Ít nhất 8 ký tự, có chữ và số.">
              <div className="relative">
                <input
                  className={inputClass("pr-12")}
                  value={form.password}
                  onChange={(event) => updateField("password", event.target.value)}
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
            <Field label="Xác nhận mật khẩu">
              <input
                className={inputClass()}
                value={form.passwordConfirmation}
                onChange={(event) => updateField("passwordConfirmation", event.target.value)}
                type={showPassword ? "text" : "password"}
                autoComplete="new-password"
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
              Đăng ký
            </Button>
          </form>

          <p className="mt-5 text-center text-sm text-muted-foreground">
            Đã có tài khoản?{" "}
            <Link className="font-semibold text-accent hover:underline" to="/login">
              Đăng nhập
            </Link>
          </p>
        </motion.section>
      </div>
    </main>
  );
}
