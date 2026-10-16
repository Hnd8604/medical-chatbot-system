export type UserRole = "USER" | "DOCTOR" | "ADMIN";
export type UserStatus = "ACTIVE" | "LOCKED" | "DISABLED";

export interface AuthUser {
  id: string;
  username: string;
  email: string;
  display_name: string;
  role: UserRole;
  status: UserStatus;
  /** USER accounts must link a FHIR patient record before they can chat. */
  onboarding_required: boolean;
}

export interface LoginResponse {
  access_token: string;
  refresh_token: string;
  token_type: "Bearer";
  expires_in_seconds: number;
  user: AuthUser;
}

export interface RegisterRequest {
  display_name: string;
  username: string;
  email: string;
  password: string;
  password_confirmation: string;
}

export interface UpdateProfileRequest {
  display_name: string;
  email: string;
}

export interface ChangePasswordRequest {
  current_password: string;
  new_password: string;
  password_confirmation: string;
}

export interface LinkPatientRequest {
  patient_id: string;
  birth_date: string;
  phone: string;
}

export interface ResetPasswordRequest {
  reset_ticket: string;
  new_password: string;
  password_confirmation: string;
}

/** Pages that end on the login screen pass a confirmation message through router state. */
export interface LoginLocationState {
  notice?: string;
}
