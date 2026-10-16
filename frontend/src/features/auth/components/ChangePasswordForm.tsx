import { FormEvent, useState } from "react";
import { useMutation } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import { errorMessage } from "@/shared/api/client";
import { Button } from "@/shared/ui/Button";
import { Field } from "@/shared/ui/Field";
import { Notice } from "@/shared/ui/Notice";
import { PasswordInput } from "@/shared/ui/PasswordInput";
import { Spinner } from "@/shared/ui/Spinner";
import { authApi } from "../api";
import { useAuth } from "../AuthProvider";
import type { LoginLocationState } from "../types";

export function ChangePasswordForm() {
  const { logout } = useAuth();
  const navigate = useNavigate();
  const [currentPassword, setCurrentPassword] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [passwordConfirmation, setPasswordConfirmation] = useState("");
  const [showPassword, setShowPassword] = useState(false);

  const changePassword = useMutation({
    mutationFn: () =>
      authApi.changePassword({
        currentPassword: currentPassword,
        newPassword: newPassword,
        passwordConfirmation: passwordConfirmation,
      }),
    // The backend revoked every session, so the user signs in again with the new password.
    onSuccess: async () => {
      await logout();
      const state: LoginLocationState = { notice: "Đổi mật khẩu thành công. Vui lòng đăng nhập lại bằng mật khẩu mới." };
      navigate("/login", { replace: true, state });
    },
  });

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    changePassword.mutate();
  }

  return (
    <form className="space-y-5" onSubmit={handleSubmit}>
      {changePassword.isError ? (
        <Notice>{errorMessage(changePassword.error, "Không thể đổi mật khẩu. Vui lòng thử lại.")}</Notice>
      ) : null}
      <Field label="Mật khẩu hiện tại">
        <PasswordInput
          value={currentPassword}
          onChange={(event) => setCurrentPassword(event.target.value)}
          visible={showPassword}
          onToggleVisible={() => setShowPassword((value) => !value)}
          autoComplete="current-password"
          required
        />
      </Field>
      <Field label="Mật khẩu mới">
        <PasswordInput
          value={newPassword}
          onChange={(event) => setNewPassword(event.target.value)}
          visible={showPassword}
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
      <Button type="submit" variant="primary" size="lg" className="w-full" disabled={changePassword.isPending}>
        {changePassword.isPending ? <Spinner /> : null}
        Đổi mật khẩu
      </Button>
    </form>
  );
}
