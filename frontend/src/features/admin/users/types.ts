import type { UserRole, UserStatus } from "@/features/auth/types";

export interface PatientLink {
  fhirPatientId: string;
  relationship: string;
  isPrimary: boolean;
}

export interface AdminUser {
  id: string;
  username: string;
  email: string;
  displayName: string;
  role: UserRole;
  status: UserStatus;
  createdAt: string;
  updatedAt: string;
  patientLinks: PatientLink[];
}
