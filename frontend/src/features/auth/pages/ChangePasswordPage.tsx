import { useNavigate } from "react-router-dom";
import { KeyRound } from "lucide-react";
import { AuthCard, AuthLayout, BackButton } from "../components/AuthLayout";
import { ChangePasswordForm } from "../components/ChangePasswordForm";

export function ChangePasswordPage() {
  const navigate = useNavigate();

  return (
    <AuthLayout
      icon={<KeyRound className="h-7 w-7" />}
      title="Đổi mật khẩu"
      subtitle="Nhập mật khẩu hiện tại và mật khẩu mới cho tài khoản của bạn."
    >
      <AuthCard>
        <ChangePasswordForm />
        <BackButton onClick={() => navigate(-1)} />
      </AuthCard>
    </AuthLayout>
  );
}
