import type { UserRole, UserStatus } from "@/features/auth/types";

export interface PatientLink {
  fhir_patient_id: string;
  relationship: string;
  is_primary: boolean;
}

export interface AdminUser {
  id: string;
  username: string;
  email: string;
  display_name: string;
  role: UserRole;
  status: UserStatus;
  created_at: string;
  updated_at: string;
  patient_links: PatientLink[];
}
