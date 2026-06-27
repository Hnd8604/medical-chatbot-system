import { FormEvent, useState } from "react";
import { Navigate, useNavigate } from "react-router-dom";
import { motion } from "framer-motion";
import { Link2, LogOut, ShieldCheck } from "lucide-react";
import { apiJson, ApiError } from "../lib/api";
import { useAuth } from "../lib/auth";
import type { AuthLinkPatientResponse } from "../lib/types";
import { Button } from "../components/ui/Button";
import { Field, inputClass } from "../components/ui/Field";
import { SectionLabel } from "../components/ui/SectionLabel";
import { Spinner } from "../components/ui/Spinner";

export function OnboardingPage() {
  const { user, logout, restore } = useAuth();
  const navigate = useNavigate();
  const [patientId, setPatientId] = useState("");
  const [birthDate, setBirthDate] = useState("");
  const [phone, setPhone] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  if (!user) {
    return <Navigate to="/login" replace />;
  }
  if (user.role !== "USER" || !user.onboarding_required) {
    return <Navigate to="/chat" replace />;
  }

  function validate() {
    if (!patientId.trim()) {
      return "Vui lòng nhập mã bệnh nhân.";
    }
    if (!/^\d{4}-\d{2}-\d{2}$/.test(birthDate.trim())) {
      return "Ngày sinh phải có định dạng YYYY-MM-DD.";
    }
    if (!phone.trim()) {
      return "Vui lòng nhập số điện thoại.";
    }
    return null;
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const validation = validate();
    if (validation) {
      setError(validation);
      return;
    }

    setSubmitting(true);
    setError(null);
    try {
      await apiJson<AuthLinkPatientResponse>("/api/auth/link-patient", {
        method: "POST",
        body: JSON.stringify({
          patient_id: patientId.trim(),
          birth_date: birthDate.trim(),
          phone: phone.trim(),
        }),
      });
      await restore();
      navigate("/chat", { replace: true });
    } catch (err) {
      if (err instanceof ApiError) {
        setError(err.detail || "Không thể liên kết hồ sơ bệnh nhân. Vui lòng kiểm tra lại thông tin.");
      } else {
        setError("Không thể liên kết hồ sơ bệnh nhân. Vui lòng thử lại.");
      }
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <main className="min-h-screen overflow-hidden bg-background px-5 py-8 text-foreground">
      <div className="mx-auto grid min-h-[calc(100vh-4rem)] max-w-6xl items-center gap-10 lg:grid-cols-[1fr_0.95fr]">
        <motion.section
          initial={{ opacity: 0, y: 24 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.55 }}
          className="space-y-7"
        >
          <SectionLabel>Liên kết hồ sơ FHIR</SectionLabel>
          <div className="space-y-5">
            <h1 className="max-w-3xl font-display text-5xl leading-[1.08] text-foreground md:text-7xl">
              Xác minh <span className="gradient-text">hồ sơ của bạn</span>
            </h1>
            <p className="max-w-2xl text-lg leading-8 text-muted-foreground">
              Trước khi bắt đầu chat, tài khoản USER cần liên kết với một hồ sơ bệnh nhân. Thông tin bạn nhập chỉ dùng để xác minh đúng hồ sơ FHIR.
            </p>
          </div>
          <div className="rounded-2xl border border-border bg-white p-5 shadow-card">
            <ShieldCheck className="mb-4 h-7 w-7 text-success" />
            <h2 className="font-semibold">Quyền truy cập cá nhân</h2>
            <p className="mt-2 text-sm leading-6 text-muted-foreground">
              Sau khi liên kết thành công, chatbot chỉ sử dụng hồ sơ đã xác minh khi bạn hỏi về thông tin cá nhân, chỉ số, chẩn đoán hoặc thuốc của mình.
            </p>
          </div>
        </motion.section>

        <motion.section
          initial={{ opacity: 0, y: 20 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.55, delay: 0.1 }}
          className="rounded-3xl border border-border bg-white p-6 shadow-lift md:p-8"
        >
          <div className="mb-7 flex items-start justify-between gap-4">
            <div>
              <p className="font-mono text-xs uppercase tracking-[0.15em] text-accent">Onboarding</p>
              <h2 className="mt-2 font-display text-3xl">Liên kết hồ sơ bệnh nhân</h2>
              <p className="mt-2 text-sm text-muted-foreground">{user.display_name || user.username}</p>
            </div>
            <Button type="button" variant="ghost" size="icon" aria-label="Đăng xuất" onClick={() => void logout()}>
              <LogOut className="h-5 w-5" />
            </Button>
          </div>

          <form className="space-y-5" onSubmit={handleSubmit}>
            <Field label="Mã bệnh nhân" hint="Ví dụ: demo-patient-001 hoặc Patient/demo-patient-001.">
              <input
                className={inputClass()}
                value={patientId}
                onChange={(event) => setPatientId(event.target.value)}
                autoComplete="off"
                spellCheck={false}
                required
              />
            </Field>
            <Field label="Ngày sinh">
              <input
                className={inputClass()}
                value={birthDate}
                onChange={(event) => setBirthDate(event.target.value)}
                type="date"
                required
              />
            </Field>
            <Field label="Số điện thoại">
              <input
                className={inputClass()}
                value={phone}
                onChange={(event) => setPhone(event.target.value)}
                inputMode="tel"
                autoComplete="tel"
                required
              />
            </Field>

            {error ? (
              <p className="rounded-xl border border-danger/20 bg-danger/10 px-4 py-3 text-sm font-medium text-danger">
                {error}
              </p>
            ) : null}

            <Button type="submit" variant="primary" size="lg" className="w-full" disabled={submitting}>
              {submitting ? <Spinner /> : <Link2 className="h-5 w-5" />}
              Liên kết hồ sơ
            </Button>
          </form>
        </motion.section>
      </div>
    </main>
  );
}
