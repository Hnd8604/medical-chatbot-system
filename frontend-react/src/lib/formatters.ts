import type { FhirBundle, FhirResource, UserRole } from "./types";

function asRecord(value: unknown): Record<string, unknown> | null {
  return value && typeof value === "object" ? (value as Record<string, unknown>) : null;
}

function asString(value: unknown): string | null {
  return typeof value === "string" && value.trim() ? value : null;
}

export function numericValue(value: number | string | null | undefined): number {
  if (typeof value === "number") {
    return Number.isFinite(value) ? value : 0;
  }
  if (typeof value === "string" && value.trim()) {
    const parsed = Number(value);
    return Number.isFinite(parsed) ? parsed : 0;
  }
  return 0;
}

export function formatNumber(value: number | string | null | undefined): string {
  return new Intl.NumberFormat("vi-VN").format(numericValue(value));
}

export function formatUsd(value: number | string | null | undefined): string {
  return new Intl.NumberFormat("vi-VN", {
    style: "currency",
    currency: "USD",
    minimumFractionDigits: 2,
    maximumFractionDigits: 4,
  }).format(numericValue(value));
}

export function formatPercent(value: number | string | null | undefined): string {
  return `${numericValue(value).toFixed(1)}%`;
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
  if (Array.isArray(value)) {
    for (const item of value) {
      const text = codeableText(item);
      if (text) {
        return text;
      }
    }
    return null;
  }
  return codeableText(value);
}

export function formatDateTime(value?: string | null): string {
  if (!value) {
    return "-";
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }
  return new Intl.DateTimeFormat("vi-VN", {
    dateStyle: "short",
    timeStyle: "short",
  }).format(date);
}

export function formatDate(value?: string | null): string {
  if (!value) {
    return "-";
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }
  return new Intl.DateTimeFormat("vi-VN", { dateStyle: "medium" }).format(date);
}

export function roleLabel(role: UserRole): string {
  if (role === "ADMIN") {
    return "Quản trị viên";
  }
  if (role === "DOCTOR") {
    return "Bác sĩ";
  }
  return "Người dùng";
}

export function statusLabel(status: string): string {
  if (status === "ACTIVE") {
    return "Đang hoạt động";
  }
  if (status === "LOCKED") {
    return "Đã khóa";
  }
  if (status === "DISABLED") {
    return "Đã vô hiệu hóa";
  }
  return status || "-";
}

export function patientName(patient?: FhirResource | null): string {
  if (!patient) {
    return "Chưa có thông tin";
  }
  if (typeof patient.name === "string" && patient.name.trim()) {
    return patient.name;
  }
  const rawNames = Array.isArray(patient.name) ? patient.name : patient.names;
  const firstName = rawNames?.[0];
  if (!firstName) {
    return patient.id ? `Patient/${patient.id}` : "Chưa có thông tin";
  }
  if (firstName.text) {
    return firstName.text;
  }
  return [...(firstName.given || []), firstName.family].filter(Boolean).join(" ") || "Chưa có thông tin";
}

export function patientBirthDate(patient?: FhirResource | null): string | null {
  return patient?.birthDate || patient?.birth_date || null;
}

export function patientPhone(patient?: FhirResource | null): string | null {
  if (!patient) {
    return null;
  }
  const phone = patient.telecom?.find((item) => item.system === "phone")?.value;
  return phone || patient.phone || null;
}

export function patientIdentifier(patient?: FhirResource | null): string | null {
  if (!patient) {
    return null;
  }
  if (typeof patient.identifier === "string") {
    return patient.identifier;
  }
  return patient.identifier?.find((item) => item.value)?.value || patient.id || null;
}

export function genderLabel(gender?: string): string {
  if (gender === "male") {
    return "Nam";
  }
  if (gender === "female") {
    return "Nữ";
  }
  if (gender === "other") {
    return "Khác";
  }
  return gender || "-";
}

export function bundleEntries(bundle: unknown): FhirResource[] {
  const value = bundle as FhirBundle;
  if (!value || !Array.isArray(value.entry)) {
    return [];
  }
  return value.entry.map((entry) => entry.resource).filter(Boolean) as FhirResource[];
}

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

export function codeText(resource: FhirResource): string {
  const code = codeableText(resource.code);
  if (code) {
    return code;
  }
  const medication = asString(resource.medication);
  if (medication) {
    return medication;
  }
  const type = firstCodeableText(resource.type) || codeableText(resource.service_type);
  if (type) {
    return type;
  }
  const reason = firstCodeableText(resource.reason_code);
  if (reason) {
    return reason;
  }
  return (
    resource.medicationCodeableConcept?.text ||
    resource.medicationCodeableConcept?.coding?.find((item) => item.display)?.display ||
    resource.reasonCode?.find((item) => item.text)?.text ||
    resource.reasonCode?.flatMap((item) => item.coding || []).find((item) => item.display)?.display ||
    resource.clinical_status ||
    resource.resourceType ||
    resource.resource_type ||
    "Bản ghi"
  );
}

export function resourceDate(resource: FhirResource): string {
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

export function observationValue(resource: FhirResource): string {
  if (resource.valueQuantity?.value !== undefined) {
    return `${resource.valueQuantity.value}${resource.valueQuantity.unit ? ` ${resource.valueQuantity.unit}` : ""}`;
  }
  if (resource.value?.value !== undefined) {
    return `${resource.value.value}${resource.value.unit ? ` ${resource.value.unit}` : ""}`;
  }
  if (resource.valueString) {
    return resource.valueString;
  }
  if (resource.value_string) {
    return resource.value_string;
  }
  if (resource.components?.length) {
    return resource.components
      .map((item) => {
        const value = item.value?.value !== undefined ? `${item.value.value}${item.value.unit ? ` ${item.value.unit}` : ""}` : item.value_string;
        return [item.code, value].filter(Boolean).join(": ");
      })
      .filter(Boolean)
      .join(", ");
  }
  return resource.status || "-";
}

export function resourceSecondaryText(resource: FhirResource, type: "observation" | "encounter" | "condition" | "medication"): string {
  if (type === "observation") {
    return observationValue(resource);
  }
  if (type === "medication" && resource.dosage?.length) {
    return resource.dosage.join(", ");
  }
  return formatDateTime(resourceDate(resource));
}

export function isPhoneLike(value: string): boolean {
  return /(?:\+?84|0)[\d\s.-]{8,14}\d/.test(value);
}

export function isDateLike(value: string): boolean {
  return /\b([0-3]?\d)[/-]([0-1]?\d)[/-]((?:19|20)\d{2})\b/.test(value) || /\b(19|20)\d{2}-\d{2}-\d{2}\b/.test(value);
}

export function normalizePatientId(value?: string | null): string | null {
  if (!value) {
    return null;
  }
  const trimmed = value.trim();
  if (!trimmed) {
    return null;
  }
  return trimmed.replace(/^Patient\//i, "");
}

export function safeJson(value: unknown): string {
  if (value === undefined || value === null) {
    return "-";
  }
  return JSON.stringify(value, null, 2);
}
