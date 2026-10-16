type CodeableConcept = { text?: string; display?: string; coding?: Array<{ display?: string; code?: string }> };
type HumanName = { family?: string; given?: string[]; text?: string };
type Quantity = { value?: number | string; unit?: string };

/**
 * A FHIR resource as returned by chatbot-service: either raw FHIR (camelCase)
 * or its normalized form (snake_case), so both spellings are accepted.
 */
export interface FhirResource {
  resourceType?: string;
  resource_type?: string;
  id?: string;
  name?: string | HumanName[];
  names?: HumanName[];
  gender?: string;
  birthDate?: string;
  birth_date?: string;
  telecom?: Array<{ system?: string; value?: string }>;
  phone?: string;
  identifier?: string | Array<{ system?: string; value?: string }>;
  code?: string | CodeableConcept;
  medication?: string;
  status?: string;
  effectiveDateTime?: string;
  effective_time?: string | { start?: string; end?: string };
  issued?: string;
  onsetDateTime?: string;
  onset?: string;
  recordedDate?: string;
  recorded_date?: string;
  authored_on?: string;
  period?: { start?: string; end?: string };
  valueQuantity?: Quantity;
  value?: Quantity;
  valueString?: string;
  value_string?: string;
  medicationCodeableConcept?: CodeableConcept;
  reasonCode?: CodeableConcept[];
  reason_code?: CodeableConcept[];
  type?: CodeableConcept[];
  service_type?: CodeableConcept;
  clinical_status?: string;
  dosage?: string[];
  components?: Array<{ code?: string; value?: Quantity; value_string?: string }>;
  [key: string]: unknown;
}

export interface PatientProfile {
  patient: FhirResource | null;
  encounters: FhirResource[];
  observations: FhirResource[];
  conditions: FhirResource[];
  medications: FhirResource[];
}

export type ClinicalResourceKind = "observation" | "encounter" | "condition" | "medication";

/** A possible match the chatbot asks the doctor to choose from. */
export interface PatientCandidate {
  id: string;
  name: string | null;
  gender: string | null;
  birth_date: string | null;
  phone: string | null;
  identifier: string | null;
}
