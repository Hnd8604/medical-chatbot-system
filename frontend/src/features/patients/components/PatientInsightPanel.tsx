import { Search } from "lucide-react";
import { cn } from "@/shared/lib/cn";
import { formatDate } from "@/shared/lib/format";
import { Badge } from "@/shared/ui/Badge";
import { Button } from "@/shared/ui/Button";
import { inputClass } from "@/shared/ui/Field";
import {
  codeText,
  genderLabel,
  patientBirthDate,
  patientIdentifier,
  patientName,
  patientPhone,
  resourceDetail,
} from "../fhir";
import type { PatientPanelState } from "../hooks";
import type { ClinicalResourceKind, FhirResource } from "../types";

const VISIBLE_RESOURCES = 5;

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
          <dd className="break-words font-medium">{patientPhone(patient) || "-"}</dd>
        </div>
        <div>
          <dt className="text-xs text-muted-foreground">Định danh</dt>
          <dd className="break-words font-medium">{patientIdentifier(patient) || "-"}</dd>
        </div>
      </div>
    </dl>
  );
}

function ResourceList({ items, kind }: { items: FhirResource[]; kind: ClinicalResourceKind }) {
  if (items.length === 0) {
    return <EmptyText>Chưa có dữ liệu.</EmptyText>;
  }

  return (
    <div className="grid gap-2">
      {items.slice(0, VISIBLE_RESOURCES).map((item, index) => (
        <article key={`${item.resourceType}-${item.id || index}`} className="rounded-xl border border-border bg-white p-3">
          <strong className="text-sm">{codeText(item)}</strong>
          <p className="mt-2 text-sm text-muted-foreground">{resourceDetail(item, kind)}</p>
        </article>
      ))}
    </div>
  );
}

function PatientSearch({ panel }: { panel: PatientPanelState }) {
  return (
    <DetailSection title="Tìm bệnh nhân">
      <form
        className="flex gap-2"
        onSubmit={(event) => {
          event.preventDefault();
          panel.submitSearch();
        }}
      >
        <div className="relative min-w-0 flex-1">
          <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
          <input
            className={inputClass("pl-9")}
            value={panel.searchTerm}
            onChange={(event) => panel.changeSearchTerm(event.target.value)}
            placeholder="Tên, số điện thoại, ngày sinh"
            spellCheck={false}
          />
        </div>
        <Button type="submit" variant="primary" disabled={panel.searching}>
          Tìm
        </Button>
      </form>
      {panel.searchResults.length > 0 ? (
        <div className="mt-3 grid gap-2">
          {panel.searchResults.map((patient) => (
            <button
              key={patient.id}
              type="button"
              className="focus-ring rounded-xl border border-border p-3 text-left transition hover:border-accent/40 hover:bg-accent/5"
              onClick={() => panel.selectPatient(patient.id)}
            >
              <strong className="line-clamp-1 text-sm">{patientName(patient)}</strong>
              <p className="mt-1 text-xs text-muted-foreground">Patient/{patient.id}</p>
            </button>
          ))}
        </div>
      ) : null}
    </DetailSection>
  );
}

interface PatientInsightPanelProps {
  staff: boolean;
  panel: PatientPanelState;
  /** Shown for USER accounts until their linked record has loaded. */
  fallbackPatient: FhirResource | null;
  mobile?: boolean;
  className?: string;
}

export function PatientInsightPanel({ staff, panel, fallbackPatient, mobile = false, className }: PatientInsightPanelProps) {
  const { profile } = panel;

  return (
    <aside
      className={cn(
        mobile ? "h-full min-h-0 overflow-auto bg-white" : "hidden min-h-0 overflow-auto border-l border-border bg-white xl:block",
        className,
      )}
    >
      <div className="sticky top-0 z-10 border-b border-border bg-white p-4">
        <Badge tone={staff ? "green" : "blue"}>{staff ? "Bệnh nhân" : "SELF"}</Badge>
        <h2 className="mt-3 font-display text-2xl">{staff ? "Thông số bệnh nhân" : "Hồ sơ của tôi"}</h2>
      </div>

      {staff ? <PatientSearch panel={panel} /> : null}

      <DetailSection title={staff ? "Hồ sơ bệnh nhân" : "Hồ sơ của tôi"}>
        {panel.loadingProfile ? (
          <EmptyText>Đang tải hồ sơ...</EmptyText>
        ) : (
          <PatientSummary patient={profile.patient ?? (staff ? null : fallbackPatient)} />
        )}
      </DetailSection>
      <DetailSection title="Lần khám gần đây">
        <ResourceList items={profile.encounters} kind="encounter" />
      </DetailSection>
      <DetailSection title="Chỉ số gần đây">
        <ResourceList items={profile.observations} kind="observation" />
      </DetailSection>
      <DetailSection title="Chẩn đoán">
        <ResourceList items={profile.conditions} kind="condition" />
      </DetailSection>
      <DetailSection title="Thuốc">
        <ResourceList items={profile.medications} kind="medication" />
      </DetailSection>
    </aside>
  );
}
