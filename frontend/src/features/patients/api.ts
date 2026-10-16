import { api, ApiError } from "@/shared/api/client";
import type { QueryParams } from "@/shared/api/types";
import { isDateLike, isPatient, isPhoneLike, normalizePatientId, resourceList } from "./fhir";
import type { FhirResource, PatientProfile } from "./types";

const SEARCH_LIMIT = 20;

export const EMPTY_PROFILE: PatientProfile = {
  patient: null,
  encounters: [],
  observations: [],
  conditions: [],
  medications: [],
};

export const patientKeys = {
  all: ["patients"] as const,
  search: (term: string) => [...patientKeys.all, "search", term] as const,
  profile: (patientId: string) => [...patientKeys.all, "profile", patientId] as const,
  self: () => [...patientKeys.all, "self"] as const,
};

/** The free-text box accepts a patient id, a phone number, a birth date or a name. */
function looksLikePatientId(term: string): boolean {
  return /^(Patient\/)?[A-Za-z0-9.-]{6,}$/i.test(term) && !term.includes(" ");
}

function searchCriteria(term: string): QueryParams {
  if (isPhoneLike(term)) {
    return { phone: term, limit: SEARCH_LIMIT };
  }
  if (isDateLike(term)) {
    return { birthDate: term, limit: SEARCH_LIMIT };
  }
  return { name: term, limit: SEARCH_LIMIT };
}

function toProfile(
  patient: FhirResource | null,
  resources: { encounters: unknown; observations: unknown; conditions: unknown; medications: unknown },
): PatientProfile {
  return {
    patient,
    encounters: resourceList(resources.encounters, "encounters"),
    observations: resourceList(resources.observations, "observations"),
    conditions: resourceList(resources.conditions, "conditions"),
    medications: resourceList(resources.medications, "medications"),
  };
}

export const patientApi = {
  async search(term: string): Promise<FhirResource[]> {
    if (looksLikePatientId(term)) {
      const id = normalizePatientId(term) ?? term;
      const patient = await api.get<FhirResource>(`/patients/${encodeURIComponent(id)}`);
      return isPatient(patient) ? [patient] : [];
    }
    const result = await api.get<unknown>("/patients", searchCriteria(term));
    return resourceList(result, "patients").filter(isPatient);
  },

  /** Doctors and admins only. */
  async profile(patientId: string): Promise<PatientProfile> {
    const path = `/patients/${encodeURIComponent(patientId)}`;
    const [patient, encounters, observations, conditions, medications] = await Promise.all([
      api.get<FhirResource>(path),
      api.get<unknown>(`${path}/encounters`, { limit: 5 }),
      api.get<unknown>(`${path}/observations`, { limit: 5 }),
      api.get<unknown>(`${path}/conditions`, { limit: 20 }),
      api.get<unknown>(`${path}/medications`, { limit: 20 }),
    ]);
    return toProfile(patient, { encounters, observations, conditions, medications });
  },

  /** The record linked to the signed-in account; empty when none is linked yet. */
  async selfProfile(): Promise<PatientProfile> {
    try {
      const profile = await api.get<{
        patient: FhirResource | null;
        encounters: unknown;
        observations: unknown;
        conditions: unknown;
        medications: unknown;
      }>("/users/my-patient-profile");
      return toProfile(profile.patient ?? null, profile);
    } catch (error) {
      if (error instanceof ApiError && error.status === 404) {
        return EMPTY_PROFILE;
      }
      throw error;
    }
  },
};
