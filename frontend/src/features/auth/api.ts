import { api } from "@/shared/api/client";
import type {
  AuthUser,
  ChangePasswordRequest,
  LinkPatientRequest,
  LoginResponse,
  RegisterRequest,
  ResetPasswordRequest,
  UpdateProfileRequest,
} from "./types";

export const authApi = {
  login: (usernameOrEmail: string, password: string) =>
    api.post<LoginResponse>("/api/auth/login", { username_or_email: usernameOrEmail, password }),
  logout: () => api.post("/api/auth/logout"),
  me: () => api.get<AuthUser>("/api/auth/me"),
  updateProfile: (body: UpdateProfileRequest) => api.put<AuthUser>("/api/auth/me", body),
  register: (body: RegisterRequest) => api.post<AuthUser>("/api/auth/register", body),
  /** Revokes every session of the user, so the client has to sign in again. */
  changePassword: (body: ChangePasswordRequest) => api.post("/api/auth/change-password", body),
  linkPatient: (body: LinkPatientRequest) => api.post<AuthUser>("/api/auth/link-patient", body),
  sendResetCode: (email: string) => api.post<{ message: string }>("/api/auth/forgot-password", { email }),
  verifyResetCode: (email: string, code: string) =>
    api.post<{ reset_ticket: string }>("/api/auth/verify-reset-code", { email, code }),
  resetPassword: (body: ResetPasswordRequest) => api.post("/api/auth/reset-password", body),
};
