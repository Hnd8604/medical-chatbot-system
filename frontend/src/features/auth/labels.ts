import type { AuthUser, UserRole, UserStatus } from "./types";

export const USER_ROLES: UserRole[] = ["USER", "DOCTOR", "ADMIN"];
export const USER_STATUSES: UserStatus[] = ["ACTIVE", "LOCKED", "DISABLED"];

const ROLE_LABELS: Record<UserRole, string> = {
  USER: "Người dùng",
  DOCTOR: "Bác sĩ",
  ADMIN: "Quản trị viên",
};

const STATUS_LABELS: Record<UserStatus, string> = {
  ACTIVE: "Đang hoạt động",
  LOCKED: "Đã khóa",
  DISABLED: "Đã vô hiệu hóa",
};

export function roleLabel(role: UserRole): string {
  return ROLE_LABELS[role] ?? role;
}

export function statusLabel(status: UserStatus): string {
  return STATUS_LABELS[status] ?? status;
}

/** Doctors and admins may look up any patient; USER accounts only see their own record. */
export function isStaff(user: Pick<AuthUser, "role">): boolean {
  return user.role === "DOCTOR" || user.role === "ADMIN";
}

/** Where a signed-in user lands: admins on the dashboard, unlinked users on onboarding. */
export function homePath(user: AuthUser): string {
  if (user.role === "ADMIN") {
    return "/admin";
  }
  return user.role === "USER" && user.onboardingRequired ? "/onboarding" : "/chat";
}
