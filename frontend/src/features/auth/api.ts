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
    api.post<LoginResponse>("/auth/login", { usernameOrEmail, password }),
  logout: () => api.post("/auth/logout"),
  me: () => api.get<AuthUser>("/users/my-info"),
  updateProfile: (body: UpdateProfileRequest) => api.put<AuthUser>("/users/my-info", body),
  register: (body: RegisterRequest) => api.post<AuthUser>("/auth/register", body),
  /** Revokes every session of the user, so the client has to sign in again. */
  changePassword: (body: ChangePasswordRequest) => api.put("/auth/password/change", body),
  linkPatient: (body: LinkPatientRequest) => api.post<AuthUser>("/users/my-patient-link", body),
  sendResetCode: (email: string) => api.post<{ message: string }>("/auth/password/reset/init", { email }),
  verifyResetCode: (email: string, code: string) =>
    api.post<{ resetTicket: string }>("/auth/password/reset/verify-otp", { email, code }),
  resetPassword: (body: ResetPasswordRequest) => api.post("/auth/password/reset/confirm", body),
};
