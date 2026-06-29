import { Search } from "lucide-react";
import type { ChatResponse, FhirResource, UserRole } from "../../lib/types";
import {
  bundleEntries,
  codeText,
  formatDate,
  genderLabel,
  patientBirthDate,
  patientIdentifier,
  patientName,
  patientPhone,
  resourceSecondaryText,
  safeJson,
} from "../../lib/formatters";
import { TEXT } from "../../lib/constants";
import { cn } from "../../lib/cn";
import { Button } from "../ui/Button";
import { Badge } from "../ui/Badge";
import { inputClass } from "../ui/Field";

interface PatientProfileData {
  patient: FhirResource | null;
  encounters: FhirResource[];
  observations: FhirResource[];
  conditions: FhirResource[];
  medications: FhirResource[];
}

interface PatientInsightPanelProps {
  role: UserRole;
  searchTerm: string;
  patientResults: FhirResource[];
  selectedPatient: FhirResource | null;
  profile: PatientProfileData;
  lastResponse: ChatResponse | null;
  loadingProfile: boolean;
  searchingPatients: boolean;
  quota: unknown;
  cost: unknown;
  onSearchTermChange: (value: string) => void;
  onSearchPatients: () => void;
  onSelectPatient: (patient: FhirResource) => void;
  mobile?: boolean;
  className?: string;
}

function DetailSection({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section className="border-b border-border px-4 py-5 last:border-b-0">
      <h3 className="mb-3 text-sm font-bold uppercase tracking-[0.08em] text-muted-foreground">{title}</h3>
      {children}
    </section>
  );
}

function EmptyText({ children }: { children: React.ReactNode }) {
  return <p className="text-sm leading-6 text-muted-foreground">{children}</p>;
}

function PatientSummary({ patient }: { patient: FhirResource | null }) {
  if (!patient) {
    return <EmptyText>Chưa có thông tin bệnh nhân.</EmptyText>;
  }

  const phone = patientPhone(patient);
  const identifier = patientIdentifier(patient);

  return (
    <dl className="grid gap-3 text-sm">
      <div className="rounded-xl bg-muted p-3">
        <dt className="text-xs font-semibold uppercase tracking-[0.08em] text-muted-foreground">Họ tên</dt>
        <dd className="mt-1 font-semibold text-foreground">{patientName(patient)}</dd>
      </div>
      <div className="grid grid-cols-2 gap-3">
        <div>
          <dt className="text-xs text-muted-foreground">Giới tính</dt>
          <dd className="font-medium">{genderLabel(patient.gender)}</dd>
        </div>
        <div>
          <dt className="text-xs text-muted-foreground">Ngày sinh</dt>
          <dd className="font-medium">{formatDate(patientBirthDate(patient))}</dd>
        </div>
      </div>
      <div className="grid grid-cols-2 gap-3">
        <div>
          <dt className="text-xs text-muted-foreground">Số điện thoại</dt>
          <dd className="break-words font-medium">{phone || "-"}</dd>
        </div>
        <div>
          <dt className="text-xs text-muted-foreground">Định danh</dt>
          <dd className="break-words font-medium">{identifier || patient.id || "-"}</dd>
        </div>
      </div>
    </dl>
  );
}

function ResourceList({ items, type }: { items: FhirResource[]; type: "observation" | "encounter" | "condition" | "medication" }) {
  if (items.length === 0) {
    return <EmptyText>Chưa có dữ liệu.</EmptyText>;
  }

  return (
    <div className="grid gap-2">
      {items.slice(0, 5).map((item, index) => (
        <article key={`${item.resourceType}-${item.id || index}`} className="rounded-xl border border-border bg-white p-3">
          <div className="flex items-start justify-between gap-3">
            <strong className="text-sm">{codeText(item)}</strong>
            {item.status ? <Badge tone="slate">{item.status}</Badge> : null}
          </div>
          <p className="mt-2 text-sm text-muted-foreground">
            {resourceSecondaryText(item, type)}
          </p>
        </article>
      ))}
    </div>
  );
}

function EvidenceBlock({ response }: { response: ChatResponse | null }) {
  if (!response?.evidence) {
    return <EmptyText>Chưa có dữ liệu tham chiếu.</EmptyText>;
  }
  return (
    <details className="rounded-xl border border-border bg-muted p-3 text-xs">
      <summary className="cursor-pointer font-semibold text-foreground">Xem dữ liệu tham chiếu</summary>
      <pre className="mt-3 max-h-72 overflow-auto whitespace-pre-wrap break-words text-muted-foreground">
        {safeJson(response.evidence)}
      </pre>
    </details>
  );
}

function Metadata({ response, quota, cost }: { response: ChatResponse | null; quota: unknown; cost: unknown }) {
  return (
    <dl className="grid gap-3 text-sm">
      <div className="grid grid-cols-2 gap-3">
        <div>
          <dt className="text-xs text-muted-foreground">Intent</dt>
          <dd className="font-medium">{response?.intent || "-"}</dd>
        </div>
        <div>
          <dt className="text-xs text-muted-foreground">Tool</dt>
          <dd className="break-words font-medium">{response?.tool_name || "-"}</dd>
        </div>
      </div>
      <div className="grid grid-cols-2 gap-3">
        <div>
          <dt className="text-xs text-muted-foreground">Nguồn trả lời</dt>
          <dd className="font-medium">{response?.answer_source || "-"}</dd>
        </div>
        <div>
          <dt className="text-xs text-muted-foreground">Bệnh nhân</dt>
          <dd className="break-words font-medium">{response?.patient_id || "-"}</dd>
        </div>
      </div>
      <details className="rounded-xl border border-border bg-muted p-3 text-xs">
        <summary className="cursor-pointer font-semibold">Usage và chi phí</summary>
        <pre className="mt-3 max-h-64 overflow-auto whitespace-pre-wrap break-words">
          {safeJson({ usage: response?.usage, answer_usage: response?.answer_usage, saved_usage: response?.saved_usage, quota, cost })}
        </pre>
      </details>
    </dl>
  );
}

export function PatientInsightPanel({
  role,
  searchTerm,
  patientResults,
  selectedPatient,
  profile,
  lastResponse,
  loadingProfile,
  searchingPatients,
  quota,
  cost,
  onSearchTermChange,
  onSearchPatients,
  onSelectPatient,
  mobile = false,
  className,
}: PatientInsightPanelProps) {
  const isStaff = role === "DOCTOR" || role === "ADMIN";
  const selfPatient = lastResponse?.evidence ? bundleEntries(lastResponse.evidence).find((item) => item.resourceType === "Patient") || null : null;

  return (
    <aside
      className={cn(
        mobile
          ? "h-full min-h-0 overflow-auto bg-white"
          : "hidden min-h-0 overflow-auto border-l border-border bg-white xl:block",
        className,
      )}
    >
      <div className="sticky top-0 z-10 border-b border-border bg-white p-4">
        <Badge tone={isStaff ? "green" : "blue"}>{isStaff ? "Bệnh nhân" : "SELF"}</Badge>
        <h2 className="mt-3 font-display text-2xl">{isStaff ? "Thông số bệnh nhân" : TEXT.myProfile}</h2>
      </div>

      {isStaff ? (
        <DetailSection title={TEXT.patientSearch}>
          <form
            className="flex gap-2"
            onSubmit={(event) => {
              event.preventDefault();
              onSearchPatients();
            }}
          >
            <div className="relative min-w-0 flex-1">
              <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
              <input
                className={inputClass("pl-9")}
                value={searchTerm}
                onChange={(event) => onSearchTermChange(event.target.value)}
                placeholder="Tên, số điện thoại, ngày sinh"
                spellCheck={false}
              />
            </div>
            <Button type="submit" variant="primary" disabled={searchingPatients}>
              Tìm
            </Button>
          </form>
          {patientResults.length > 0 ? (
            <div className="mt-3 grid gap-2">
              {patientResults.map((patient) => (
                <button
                  key={patient.id}
                  type="button"
                  className="focus-ring rounded-xl border border-border p-3 text-left transition hover:border-accent/40 hover:bg-accent/5"
                  onClick={() => onSelectPatient(patient)}
                >
                  <strong className="line-clamp-1 text-sm">{patientName(patient)}</strong>
                  <p className="mt-1 text-xs text-muted-foreground">Patient/{patient.id}</p>
                </button>
              ))}
            </div>
          ) : null}
        </DetailSection>
      ) : null}

      <DetailSection title={isStaff ? "Hồ sơ bệnh nhân" : "Hồ sơ của tôi"}>
        {loadingProfile ? <EmptyText>Đang tải hồ sơ...</EmptyText> : <PatientSummary patient={isStaff ? selectedPatient || profile.patient : selfPatient} />}
      </DetailSection>

      {isStaff ? (
        <>
          <DetailSection title="Lần khám gần đây">
            <ResourceList items={profile.encounters} type="encounter" />
          </DetailSection>
          <DetailSection title="Chỉ số gần đây">
            <ResourceList items={profile.observations} type="observation" />
          </DetailSection>
          <DetailSection title="Chẩn đoán">
            <ResourceList items={profile.conditions} type="condition" />
          </DetailSection>
          <DetailSection title="Thuốc">
            <ResourceList items={profile.medications} type="medication" />
          </DetailSection>
        </>
      ) : (
        <DetailSection title="Gợi ý truy vấn">
          <EmptyText>Panel sẽ cập nhật sau khi bạn hỏi chatbot về hồ sơ FHIR đã liên kết.</EmptyText>
        </DetailSection>
      )}

      <DetailSection title="Phản hồi gần nhất">
        <Metadata response={lastResponse} quota={quota} cost={cost} />
      </DetailSection>

      <DetailSection title="Dữ liệu tham chiếu">
        <EvidenceBlock response={lastResponse} />
      </DetailSection>
    </aside>
  );
}
