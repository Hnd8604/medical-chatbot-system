export type UserRole = "USER" | "DOCTOR" | "ADMIN";
export type UserStatus = "ACTIVE" | "LOCKED" | "DISABLED";

export interface AuthUser {
  id: string;
  username: string;
  email: string;
  displayName: string;
  role: UserRole;
  status: UserStatus;
  /** USER accounts must link a FHIR patient record before they can chat. */
  onboardingRequired: boolean;
}

export interface LoginResponse {
  accessToken: string;
  refreshToken: string;
  tokenType: "Bearer";
  expiresInSeconds: number;
  user: AuthUser;
}

export interface RegisterRequest {
  displayName: string;
  username: string;
  email: string;
  password: string;
  passwordConfirmation: string;
}

export interface UpdateProfileRequest {
  displayName: string;
  email: string;
}

export interface ChangePasswordRequest {
  currentPassword: string;
  newPassword: string;
  passwordConfirmation: string;
}

export interface LinkPatientRequest {
  patientId: string;
  birthDate: string;
  phone: string;
}

export interface ResetPasswordRequest {
  resetTicket: string;
  newPassword: string;
  passwordConfirmation: string;
}

/** Pages that end on the login screen pass a confirmation message through router state. */
export interface LoginLocationState {
  notice?: string;
}
