import { FormEvent, useState } from "react";
import { useMutation } from "@tanstack/react-query";
import { Link, Navigate, useLocation } from "react-router-dom";
import { Stethoscope } from "lucide-react";
import { errorMessage } from "@/shared/api/client";
import { Button } from "@/shared/ui/Button";
import { Field, inputClass } from "@/shared/ui/Field";
import { Notice } from "@/shared/ui/Notice";
import { PasswordInput } from "@/shared/ui/PasswordInput";
import { Spinner } from "@/shared/ui/Spinner";
import { useAuth } from "../AuthProvider";
import { AuthCard, AuthLayout } from "../components/AuthLayout";
import { homePath } from "../labels";
import type { LoginLocationState } from "../types";

const DEMO_ACCOUNTS = [
  { role: "Người dùng", credentials: "user_demo / UserDemo123!" },
  { role: "Bác sĩ", credentials: "doctor_demo / DoctorDemo123!" },
  { role: "Quản trị viên", credentials: "admin_demo / AdminDemo123!" },
];

export function LoginPage() {
  const { user, loading, login } = useAuth();
  const notice = (useLocation().state as LoginLocationState | null)?.notice;
  const [usernameOrEmail, setUsernameOrEmail] = useState("");
  const [password, setPassword] = useState("");
  const [showPassword, setShowPassword] = useState(false);

  // On success the user is set and the redirect below takes over.
  const signIn = useMutation({ mutationFn: () => login(usernameOrEmail.trim(), password) });

  if (!loading && user) {
    return <Navigate to={homePath(user)} replace />;
  }

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    signIn.mutate();
  }

  return (
    <AuthLayout
      icon={<Stethoscope className="h-7 w-7" />}
      title="Medical Chatbot"
      subtitle="Đăng nhập để bắt đầu hỏi đáp dữ liệu FHIR theo đúng quyền truy cập."
    >
      <AuthCard>
        <form className="space-y-5" onSubmit={handleSubmit}>
          {notice ? <Notice tone="success">{notice}</Notice> : null}
          <Field label="Tên đăng nhập hoặc email">
            <input
              className={inputClass()}
              value={usernameOrEmail}
              onChange={(event) => setUsernameOrEmail(event.target.value)}
              autoComplete="username"
              spellCheck={false}
              required
            />
          </Field>
          <Field label="Mật khẩu">
            <PasswordInput
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              visible={showPassword}
              onToggleVisible={() => setShowPassword((value) => !value)}
              autoComplete="current-password"
              required
            />
          </Field>
          {signIn.isError ? (
            <Notice>{errorMessage(signIn.error, "Không thể đăng nhập. Vui lòng kiểm tra lại thông tin.")}</Notice>
          ) : null}
          <Button type="submit" variant="primary" size="lg" className="w-full" disabled={signIn.isPending}>
            {signIn.isPending ? <Spinner /> : null}
            Đăng nhập
          </Button>
        </form>

        <p className="mt-4 text-center text-sm">
          <Link className="font-semibold text-accent hover:underline" to="/forgot-password">
            Quên mật khẩu?
          </Link>
        </p>
        <p className="mt-6 text-center text-sm text-muted-foreground">
          Chưa có tài khoản?{" "}
          <Link className="font-semibold text-accent hover:underline" to="/register">
            Đăng ký
          </Link>
        </p>
      </AuthCard>

      {/* Demo accounts exist only in local databases (production migrations disable them). */}
      {import.meta.env.DEV ? (
        <details className="mt-6 rounded-2xl border border-border bg-muted/50 p-4 text-sm">
          <summary className="cursor-pointer font-semibold text-foreground">Tài khoản demo local</summary>
          <dl className="mt-4 grid gap-3 text-muted-foreground">
            {DEMO_ACCOUNTS.map((account) => (
              <div key={account.role} className="flex items-center justify-between gap-3">
                <dt>{account.role}</dt>
                <dd className="font-mono text-foreground">{account.credentials}</dd>
              </div>
            ))}
          </dl>
        </details>
      ) : null}
    </AuthLayout>
  );
}
