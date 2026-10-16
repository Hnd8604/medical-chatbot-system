import { formatDateTime } from "@/shared/lib/format";
import type { ClinicalResourceKind, FhirResource, PatientCandidate } from "./types";

function asRecord(value: unknown): Record<string, unknown> | null {
  return value && typeof value === "object" ? (value as Record<string, unknown>) : null;
}

function asString(value: unknown): string | null {
  return typeof value === "string" && value.trim() ? value : null;
}

function codeableText(value: unknown): string | null {
  if (typeof value === "string" && value.trim()) {
    return value;
  }
  const record = asRecord(value);
  if (!record) {
    return null;
  }
  const direct = asString(record.text) || asString(record.display) || asString(record.code);
  if (direct) {
    return direct;
  }
  const coding = Array.isArray(record.coding) ? record.coding : [];
  for (const item of coding) {
    const codingRecord = asRecord(item);
    const display = codingRecord ? asString(codingRecord.display) || asString(codingRecord.code) : null;
    if (display) {
      return display;
    }
  }
  return null;
}

function firstCodeableText(value: unknown): string | null {
  if (!Array.isArray(value)) {
    return codeableText(value);
  }
  for (const item of value) {
    const text = codeableText(item);
    if (text) {
      return text;
    }
  }
  return null;
}

/** Strips the optional "Patient/" prefix; null for blank input. */
export function normalizePatientId(value?: string | null): string | null {
  const trimmed = value?.trim();
  return trimmed ? trimmed.replace(/^Patient\//i, "") : null;
}

export function isPatient(resource: FhirResource | null | undefined): boolean {
  return resource?.resourceType === "Patient" || resource?.resource_type === "Patient";
}

function bundleEntries(value: unknown): FhirResource[] {
  const entries = asRecord(value)?.entry;
  if (!Array.isArray(entries)) {
    return [];
  }
  return entries.map((entry) => asRecord(entry)?.resource).filter(Boolean) as FhirResource[];
}

/** Resources from a list, a FHIR Bundle, or an object holding the list under `key` (or under `data`). */
export function resourceList(value: unknown, key: string): FhirResource[] {
  if (Array.isArray(value)) {
    return value.filter((item) => Boolean(asRecord(item))) as FhirResource[];
  }
  const bundleItems = bundleEntries(value);
  if (bundleItems.length > 0) {
    return bundleItems;
  }
  const record = asRecord(value);
  if (!record) {
    return [];
  }
  const keyed = record[key];
  if (Array.isArray(keyed)) {
    return keyed.filter((item) => Boolean(asRecord(item))) as FhirResource[];
  }
  return resourceList(record.data, key);
}

/** The Patient resource carried in a chat answer's evidence bundle, if any. */
export function evidencePatient(evidence: unknown): FhirResource | null {
  return bundleEntries(evidence).find(isPatient) ?? null;
}

export function patientName(patient?: FhirResource | null): string {
  if (!patient) {
    return "Chưa có thông tin";
  }
  if (typeof patient.name === "string" && patient.name.trim()) {
    return patient.name;
  }
  const firstName = (Array.isArray(patient.name) ? patient.name : patient.names)?.[0];
  if (!firstName) {
    return patient.id ? `Patient/${patient.id}` : "Chưa có thông tin";
  }
  if (firstName.text) {
    return firstName.text;
  }
  return [...(firstName.given || []), firstName.family].filter(Boolean).join(" ") || "Chưa có thông tin";
}

export function patientBirthDate(patient: FhirResource): string | null {
  return patient.birthDate || patient.birth_date || null;
}

export function patientPhone(patient: FhirResource): string | null {
  return patient.telecom?.find((item) => item.system === "phone")?.value || patient.phone || null;
}

export function patientIdentifier(patient: FhirResource): string | null {
  if (typeof patient.identifier === "string") {
    return patient.identifier;
  }
  return patient.identifier?.find((item) => item.value)?.value || patient.id || null;
}

export function genderLabel(gender?: string | null): string {
  switch (gender) {
    case "male":
      return "Nam";
    case "female":
      return "Nữ";
    case "other":
      return "Khác";
    default:
      return gender || "-";
  }
}

export function codeText(resource: FhirResource): string {
  return (
    codeableText(resource.code) ||
    asString(resource.medication) ||
    firstCodeableText(resource.type) ||
    codeableText(resource.service_type) ||
    firstCodeableText(resource.reason_code) ||
    codeableText(resource.medicationCodeableConcept) ||
    firstCodeableText(resource.reasonCode) ||
    resource.clinical_status ||
    resource.resourceType ||
    resource.resource_type ||
    "Bản ghi"
  );
}

function resourceDate(resource: FhirResource): string {
  const effectiveTime = typeof resource.effective_time === "string" ? resource.effective_time : resource.effective_time?.start;
  return (
    resource.effectiveDateTime ||
    effectiveTime ||
    resource.issued ||
    resource.onsetDateTime ||
    resource.onset ||
    resource.recordedDate ||
    resource.recorded_date ||
    resource.authored_on ||
    resource.period?.start ||
    ""
  );
}

function quantityText(quantity?: { value?: number | string; unit?: string }): string | null {
  if (quantity?.value === undefined) {
    return null;
  }
  return `${quantity.value}${quantity.unit ? ` ${quantity.unit}` : ""}`;
}

function observationValue(resource: FhirResource): string {
  const value =
    quantityText(resource.valueQuantity) || quantityText(resource.value) || resource.valueString || resource.value_string;
  if (value) {
    return value;
  }
  if (resource.components?.length) {
    return resource.components
      .map((item) => [item.code, quantityText(item.value) || item.value_string].filter(Boolean).join(": "))
      .filter(Boolean)
      .join(", ");
  }
  return resource.status || "-";
}

/** Second line of a resource card: the measured value, the dosage, or the date. */
export function resourceDetail(resource: FhirResource, kind: ClinicalResourceKind): string {
  if (kind === "observation") {
    return observationValue(resource);
  }
  if (kind === "medication" && resource.dosage?.length) {
    return resource.dosage.join(", ");
  }
  return formatDateTime(resourceDate(resource));
}

/** Candidates from a chat answer, skipping malformed entries. */
export function parsePatientCandidates(value: unknown): PatientCandidate[] {
  if (!Array.isArray(value)) {
    return [];
  }
  const text = (field: unknown) => (typeof field === "string" ? field : null);
  return value.flatMap((item) => {
    const candidate = asRecord(item);
    const id = typeof candidate?.id === "string" ? candidate.id.trim() : "";
    if (!candidate || !id) {
      return [];
    }
    return [
      {
        id,
        name: text(candidate.name),
        gender: text(candidate.gender),
        birth_date: text(candidate.birth_date),
        phone: text(candidate.phone),
        identifier: text(candidate.identifier),
      },
    ];
  });
}

export function isPhoneLike(value: string): boolean {
  return /(?:\+?84|0)[\d\s.-]{8,14}\d/.test(value);
}

export function isDateLike(value: string): boolean {
  return /\b([0-3]?\d)[/-]([0-1]?\d)[/-]((?:19|20)\d{2})\b/.test(value) || /\b(19|20)\d{2}-\d{2}-\d{2}\b/.test(value);
}
