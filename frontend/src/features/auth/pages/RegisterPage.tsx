import { FormEvent, useState } from "react";
import { useMutation } from "@tanstack/react-query";
import { Link, Navigate, useNavigate } from "react-router-dom";
import { UserRoundPlus } from "lucide-react";
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
import type { LoginLocationState, RegisterRequest } from "../types";

const emptyForm: RegisterRequest = {
  displayName: "",
  username: "",
  email: "",
  password: "",
  passwordConfirmation: "",
};

/** Mirrors the backend rules so most mistakes are caught before the request. */
function validate(form: RegisterRequest): string | null {
  if (!/^[A-Za-z0-9._-]{3,30}$/.test(form.username.trim())) {
    return "Tên đăng nhập phải có 3-30 ký tự và chỉ gồm chữ, số, dấu chấm, gạch dưới hoặc gạch ngang.";
  }
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(form.email.trim())) {
    return "Email không hợp lệ.";
  }
  const passwordBytes = new TextEncoder().encode(form.password).length;
  if (passwordBytes < 8 || passwordBytes > 72) {
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
  const [form, setForm] = useState<RegisterRequest>(emptyForm);
  const [showPassword, setShowPassword] = useState(false);
  const [validationError, setValidationError] = useState<string | null>(null);

  const register = useMutation({
    mutationFn: () =>
      authApi.register({
        ...form,
        displayName: form.displayName.trim(),
        username: form.username.trim(),
        email: form.email.trim(),
      }),
    onSuccess: () => {
      const state: LoginLocationState = { notice: "Đăng ký tài khoản thành công." };
      navigate("/login", { replace: true, state });
    },
  });

  if (!loading && user) {
    return <Navigate to={homePath(user)} replace />;
  }

  function updateField(key: keyof RegisterRequest, value: string) {
    setForm((current) => ({ ...current, [key]: value }));
  }

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const problem = validate(form);
    setValidationError(problem);
    if (!problem) {
      register.mutate();
    }
  }

  const error =
    validationError ??
    (register.isError ? errorMessage(register.error, "Không thể đăng ký tài khoản. Vui lòng kiểm tra lại thông tin.") : null);

  return (
    <AuthLayout
      icon={<UserRoundPlus className="h-7 w-7" />}
      title="Tạo tài khoản"
      subtitle="Tài khoản mới được cấp quyền USER và dùng được ngay sau khi liên kết với hồ sơ FHIR của bạn."
    >
      <AuthCard>
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
            <PasswordInput
              value={form.password}
              onChange={(event) => updateField("password", event.target.value)}
              visible={showPassword}
              onToggleVisible={() => setShowPassword((value) => !value)}
              autoComplete="new-password"
              required
            />
          </Field>
          <Field label="Xác nhận mật khẩu">
            <PasswordInput
              value={form.passwordConfirmation}
              onChange={(event) => updateField("passwordConfirmation", event.target.value)}
              visible={showPassword}
              autoComplete="new-password"
              required
            />
          </Field>

          {error ? <Notice>{error}</Notice> : null}

          <Button type="submit" variant="primary" size="lg" className="w-full" disabled={register.isPending}>
            {register.isPending ? <Spinner /> : null}
            Đăng ký
          </Button>
        </form>

        <p className="mt-6 text-center text-sm text-muted-foreground">
          Đã có tài khoản?{" "}
          <Link className="font-semibold text-accent hover:underline" to="/login">
            Đăng nhập
          </Link>
        </p>
      </AuthCard>
    </AuthLayout>
  );
}
