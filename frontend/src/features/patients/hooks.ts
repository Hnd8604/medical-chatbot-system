import { useState } from "react";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { EMPTY_PROFILE, patientApi, patientKeys } from "./api";
import { normalizePatientId } from "./fhir";

/**
 * State of the patient side panel. Staff search for and pick any patient;
 * USER accounts always see the record linked to their own account.
 */
export function usePatientPanel(staff: boolean) {
  const [searchTerm, setSearchTerm] = useState("");
  const [submittedTerm, setSubmittedTerm] = useState("");
  const [patientId, setPatientId] = useState<string | null>(null);

  const search = useQuery({
    queryKey: patientKeys.search(submittedTerm),
    queryFn: () => patientApi.search(submittedTerm),
    enabled: staff && submittedTerm !== "",
  });
  const staffProfile = useQuery({
    queryKey: patientKeys.profile(patientId ?? ""),
    queryFn: () => patientApi.profile(patientId ?? ""),
    enabled: staff && patientId !== null,
    // Keep the current record on screen while the next one loads.
    placeholderData: keepPreviousData,
  });
  const selfProfile = useQuery({
    queryKey: patientKeys.self(),
    queryFn: patientApi.selfProfile,
    enabled: !staff,
  });
  const profile = staff ? staffProfile : selfProfile;

  function changeSearchTerm(value: string) {
    setSearchTerm(value);
    if (!value.trim()) {
      setSubmittedTerm("");
    }
  }

  function selectPatient(id: string | null | undefined) {
    const normalized = normalizePatientId(id);
    if (staff && normalized) {
      setPatientId(normalized);
      setSubmittedTerm("");
    }
  }

  return {
    searchTerm,
    changeSearchTerm,
    submitSearch: () => setSubmittedTerm(searchTerm.trim()),
    searchResults: submittedTerm && search.data ? search.data : [],
    searching: search.isFetching,
    selectPatient,
    profile: profile.data ?? EMPTY_PROFILE,
    loadingProfile: profile.isFetching,
    /** The patient whose record is on screen; chat questions are scoped to it. */
    activePatientId: staff ? (staffProfile.data?.patient?.id ?? null) : null,
    error: search.error ?? profile.error,
  };
}

export type PatientPanelState = ReturnType<typeof usePatientPanel>;
