import { FormEvent, useState } from "react";
import { useMutation } from "@tanstack/react-query";
import { Link, Navigate, useNavigate } from "react-router-dom";
import { KeyRound } from "lucide-react";
import { errorMessage } from "@/shared/api/client";
import { Button } from "@/shared/ui/Button";
import { Field, inputClass } from "@/shared/ui/Field";
import { Notice } from "@/shared/ui/Notice";
import { PasswordInput } from "@/shared/ui/PasswordInput";
import { Spinner } from "@/shared/ui/Spinner";
import { authApi } from "../api";
import { useAuth } from "../AuthProvider";
import { AuthCard, AuthLayout } from "../components/AuthLayout";
import { homePath } from "../labels";
import type { LoginLocationState } from "../types";

type Step = "email" | "code" | "password";

const SUBTITLES: Record<Step, string> = {
  email: "Nhập email tài khoản để nhận mã đặt lại mật khẩu.",
  code: "Nhập mã 6 số đã được gửi tới email của bạn.",
  password: "Tạo mật khẩu mới cho tài khoản của bạn.",
};

/** Three steps: email the code, exchange it for a one-time reset ticket, then set the password. */
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
  const [info, setInfo] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const sendCode = useMutation({
    mutationFn: () => authApi.sendResetCode(email.trim()),
    onMutate: () => setError(null),
    onSuccess: (response) => {
      setInfo(response.message);
      setStep("code");
    },
    onError: (failure) => setError(errorMessage(failure, "Không thể gửi mã. Vui lòng thử lại.")),
  });

  const verifyCode = useMutation({
    mutationFn: () => authApi.verifyResetCode(email.trim(), code.trim()),
    onMutate: () => setError(null),
    onSuccess: (response) => {
      setResetTicket(response.reset_ticket);
      setInfo(null);
      setStep("password");
    },
    onError: (failure) => setError(errorMessage(failure, "Mã xác thực không đúng hoặc đã hết hạn.")),
  });

  const resetPassword = useMutation({
    mutationFn: () =>
      authApi.resetPassword({
        reset_ticket: resetTicket,
        new_password: newPassword,
        password_confirmation: passwordConfirmation,
      }),
    onMutate: () => setError(null),
    onSuccess: () => {
      const state: LoginLocationState = {
        notice: "Đặt lại mật khẩu thành công. Vui lòng đăng nhập bằng mật khẩu mới.",
      };
      navigate("/login", { replace: true, state });
    },
    onError: (failure) => setError(errorMessage(failure, "Không thể đặt lại mật khẩu. Vui lòng thử lại.")),
  });

  if (!loading && user) {
    return <Navigate to={homePath(user)} replace />;
  }

  const submitting = sendCode.isPending || verifyCode.isPending || resetPassword.isPending;

  function submit(action: () => void) {
    return (event: FormEvent<HTMLFormElement>) => {
      event.preventDefault();
      action();
    };
  }

  return (
    <AuthLayout icon={<KeyRound className="h-7 w-7" />} title="Quên mật khẩu" subtitle={SUBTITLES[step]}>
      <AuthCard>
        {info ? <Notice tone="success" className="mb-5">{info}</Notice> : null}
        {error ? <Notice className="mb-5">{error}</Notice> : null}

        {step === "email" ? (
          <form className="space-y-5" onSubmit={submit(() => sendCode.mutate())}>
            <Field label="Email">
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
              Gửi mã
            </Button>
          </form>
        ) : null}

        {step === "code" ? (
          <form className="space-y-5" onSubmit={submit(() => verifyCode.mutate())}>
            <Field label="Mã xác thực">
              <input
                className={inputClass("text-center font-mono tracking-[0.5em]")}
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
              Xác nhận mã
            </Button>
            <button
              type="button"
              className="focus-ring w-full rounded-lg py-1 text-sm font-medium text-accent hover:underline disabled:opacity-60"
              onClick={() => sendCode.mutate()}
              disabled={submitting}
            >
              Gửi lại mã
            </button>
          </form>
        ) : null}

        {step === "password" ? (
          <form className="space-y-5" onSubmit={submit(() => resetPassword.mutate())}>
            <Field label="Mật khẩu mới">
              <PasswordInput
                value={newPassword}
                onChange={(event) => setNewPassword(event.target.value)}
                visible={showPassword}
                onToggleVisible={() => setShowPassword((value) => !value)}
                autoComplete="new-password"
                required
              />
            </Field>
            <Field label="Xác nhận mật khẩu mới">
              <PasswordInput
                value={passwordConfirmation}
                onChange={(event) => setPasswordConfirmation(event.target.value)}
                visible={showPassword}
                autoComplete="new-password"
                required
              />
            </Field>
            <Button type="submit" variant="primary" size="lg" className="w-full" disabled={submitting}>
              {submitting ? <Spinner /> : null}
              Đặt lại mật khẩu
            </Button>
          </form>
        ) : null}

        <p className="mt-6 text-center text-sm text-muted-foreground">
          <Link className="font-semibold text-accent hover:underline" to="/login">
            Quay lại đăng nhập
          </Link>
        </p>
      </AuthCard>
    </AuthLayout>
  );
}
