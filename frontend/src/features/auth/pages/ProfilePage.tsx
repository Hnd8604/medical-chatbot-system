import { FormEvent, useState } from "react";
import { useMutation } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import { KeyRound, UserRound } from "lucide-react";
import { errorMessage } from "@/shared/api/client";
import { Badge } from "@/shared/ui/Badge";
import { Button } from "@/shared/ui/Button";
import { Field, inputClass } from "@/shared/ui/Field";
import { Notice } from "@/shared/ui/Notice";
import { Spinner } from "@/shared/ui/Spinner";
import { authApi } from "../api";
import { useAuth, useSignedInUser } from "../AuthProvider";
import { AuthCard, AuthLayout, BackButton } from "../components/AuthLayout";
import { ChangePasswordForm } from "../components/ChangePasswordForm";
import { roleLabel } from "../labels";

function CardHeader({ icon, title, children }: { icon: React.ReactNode; title: string; children: React.ReactNode }) {
  return (
    <header className="mb-6 flex items-center gap-3">
      <span className="grid h-10 w-10 place-items-center rounded-xl bg-accent/10 text-accent">{icon}</span>
      <div>
        <h2 className="font-display text-xl">{title}</h2>
        <div className="mt-1 text-sm text-muted-foreground">{children}</div>
      </div>
    </header>
  );
}

function ProfileForm() {
  const { setUser } = useAuth();
  const user = useSignedInUser();
  const [displayName, setDisplayName] = useState(user.displayName);
  const [email, setEmail] = useState(user.email ?? "");

  const updateProfile = useMutation({
    mutationFn: () => authApi.updateProfile({ displayName: displayName, email }),
    onSuccess: setUser,
  });

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    updateProfile.mutate();
  }

  return (
    <form className="space-y-5" onSubmit={handleSubmit}>
      {updateProfile.isError ? (
        <Notice>{errorMessage(updateProfile.error, "Không thể cập nhật thông tin. Vui lòng thử lại.")}</Notice>
      ) : null}
      {updateProfile.isSuccess ? <Notice tone="success">Cập nhật thông tin cá nhân thành công.</Notice> : null}
      <Field label="Tên đăng nhập">
        <input className={inputClass()} value={user.username} type="text" disabled />
      </Field>
      <Field label="Tên hiển thị">
        <input
          className={inputClass()}
          value={displayName}
          onChange={(event) => setDisplayName(event.target.value)}
          minLength={2}
          maxLength={100}
          autoComplete="name"
          required
        />
      </Field>
      <Field label="Email">
        <input
          className={inputClass()}
          value={email}
          onChange={(event) => setEmail(event.target.value)}
          type="email"
          autoComplete="email"
          required
        />
      </Field>
      <Button type="submit" variant="primary" size="lg" className="w-full" disabled={updateProfile.isPending}>
        {updateProfile.isPending ? <Spinner /> : null}
        Lưu thay đổi
      </Button>
    </form>
  );
}

export function ProfilePage() {
  const user = useSignedInUser();
  const navigate = useNavigate();

  return (
    <AuthLayout
      wide
      icon={<UserRound className="h-7 w-7" />}
      title="Trang cá nhân"
      subtitle="Xem và cập nhật thông tin tài khoản của bạn."
    >
      <div className="grid gap-6">
        <AuthCard>
          <CardHeader icon={<UserRound className="h-5 w-5" />} title="Thông tin cá nhân">
            <span className="flex items-center gap-2">
              {user.username}
              <Badge tone="blue" className="px-2 py-0.5 text-[10px]">
                {roleLabel(user.role)}
              </Badge>
            </span>
          </CardHeader>
          <ProfileForm />
        </AuthCard>
        <AuthCard>
          <CardHeader icon={<KeyRound className="h-5 w-5" />} title="Đổi mật khẩu">
            Nhập mật khẩu hiện tại và mật khẩu mới cho tài khoản của bạn.
          </CardHeader>
          <ChangePasswordForm />
        </AuthCard>
      </div>
      <BackButton onClick={() => navigate(-1)} />
    </AuthLayout>
  );
}
