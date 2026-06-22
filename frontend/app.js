const authScreen = document.querySelector("#authScreen");
const appShell = document.querySelector("#appShell");
const loginForm = document.querySelector("#loginForm");
const loginApiBaseUrlInput = document.querySelector("#loginApiBaseUrl");
const loginUsernameInput = document.querySelector("#loginUsernameInput");
const loginPasswordInput = document.querySelector("#loginPasswordInput");
const loginButton = document.querySelector("#loginButton");
const loginError = document.querySelector("#loginError");
const currentUserPanel = document.querySelector("#currentUserPanel");
const currentUserDisplayName = document.querySelector("#currentUserDisplayName");
const currentUserMeta = document.querySelector("#currentUserMeta");
const logoutButton = document.querySelector("#logoutButton");
const openAdminPanelButton = document.querySelector("#openAdminPanelButton");
const adminPanel = document.querySelector("#adminPanel");
const closeAdminPanelButton = document.querySelector("#closeAdminPanelButton");
const refreshAdminUsersButton = document.querySelector("#refreshAdminUsersButton");
const adminUsersList = document.querySelector("#adminUsersList");
const userAccessNotice = document.querySelector("#userAccessNotice");

const patientSearchForm = document.querySelector("#patientSearchForm");
const patientSearchInput = document.querySelector("#patientSearchInput");
const patientList = document.querySelector("#patientList");
const apiBaseUrlInput = document.querySelector("#apiBaseUrl");
const statusText = document.querySelector("#statusText");

const selectedPatientName = document.querySelector("#selectedPatientName");
const selectedPatientMeta = document.querySelector("#selectedPatientMeta");
const patientSummary = document.querySelector("#patientSummary");
const encountersList = document.querySelector("#encountersList");
const observationsList = document.querySelector("#observationsList");
const conditionsList = document.querySelector("#conditionsList");
const medicationsList = document.querySelector("#medicationsList");
const doctorOnlySections = document.querySelectorAll("[data-doctor-only]");
const patientQuickPrompts = document.querySelectorAll(".patient-quick-prompt");

const chatForm = document.querySelector("#chatForm");
const messageInput = document.querySelector("#messageInput");
const sendButton = document.querySelector("#sendButton");
const messages = document.querySelector("#messages");
const newChatButton = document.querySelector("#newChatButton");
const refreshSessionsButton = document.querySelector("#refreshSessionsButton");
const sessionSearchInput = document.querySelector("#sessionSearchInput");
const sessionList = document.querySelector("#sessionList");

const exportPdfButton = document.querySelector("#exportPdfButton");
const exportCsvButton = document.querySelector("#exportCsvButton");
const exportHistoryButton = document.querySelector("#exportHistoryButton");
const exportModal = document.querySelector("#exportModal");
const exportForm = document.querySelector("#exportForm");
const exportFromDate = document.querySelector("#exportFromDate");
const exportToDate = document.querySelector("#exportToDate");
const exportFormat = document.querySelector("#exportFormat");
const closeExportModal = document.querySelector("#closeExportModal");

const sessionId = document.querySelector("#sessionId");
const intent = document.querySelector("#intent");
const answerSource = document.querySelector("#answerSource");
const toolName = document.querySelector("#toolName");
const responseScope = document.querySelector("#responseScope");
const evidenceList = document.querySelector("#evidenceList");
const usageBlock = document.querySelector("#usageBlock");
const costSummary = document.querySelector("#costSummary");

const notificationBellButton = document.querySelector("#notificationBellButton");
const notificationBadge = document.querySelector("#notificationBadge");
const notificationPopover = document.querySelector("#notificationPopover");
const notificationList = document.querySelector("#notificationList");
const markAllNotificationsRead = document.querySelector("#markAllNotificationsRead");

const AUTH_TOKEN_STORAGE_KEY = "medical_chatbot_access_token";
const API_BASE_URL_STORAGE_KEY = "medical_chatbot_api_base_url";

const defaultSelectedPatientName = selectedPatientName.textContent;
const defaultSelectedPatientMeta = selectedPatientMeta.textContent;

let currentUser = null;
let selectedPatient = null;
let currentSessionId = null;
let lastPatients = [];
let sessionSearchTimer = null;
let notificationsIntervalId = null;

loginForm.addEventListener("submit", handleLogin);
logoutButton.addEventListener("click", () => handleLogout());

patientSearchForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  if (!hasFhirAccess()) {
    return;
  }
  await loadPatients(patientSearchInput.value.trim());
});

chatForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  const message = messageInput.value.trim();
  if (!message) {
    return;
  }
  await submitChat(message, hasFhirAccess() ? selectedPatient?.id || null : null, message);
});

newChatButton.addEventListener("click", () => {
  currentSessionId = null;
  resetMessages("Cuoc chat moi da san sang.");
  renderDetails(null);
  renderSessionsActiveState();
  messageInput.focus();
});

refreshSessionsButton.addEventListener("click", () => {
  sessionSearchInput.value = "";
  loadSessions();
});

openAdminPanelButton.addEventListener("click", async () => {
  adminPanel.hidden = false;
  await loadAdminUsers();
});

closeAdminPanelButton.addEventListener("click", () => {
  adminPanel.hidden = true;
});

refreshAdminUsersButton.addEventListener("click", () => {
  loadAdminUsers();
});

exportPdfButton.addEventListener("click", () => exportSession("pdf"));
exportCsvButton.addEventListener("click", () => exportSession("csv"));

notificationBellButton.addEventListener("click", (event) => {
  event.stopPropagation();
  const isOpen = notificationPopover.style.display === "flex";
  if (isOpen) {
    notificationPopover.style.display = "none";
    return;
  }
  notificationPopover.style.display = "flex";
  loadNotifications().catch(() => {});
});

markAllNotificationsRead.addEventListener("click", async (event) => {
  event.stopPropagation();
  try {
    await apiPost("/api/notifications/read-all");
    await loadNotifications();
  } catch (error) {
    console.error("Khong the danh dau tat ca da doc:", error);
  }
});

document.addEventListener("click", (event) => {
  if (!event.target.closest(".notification-container")) {
    notificationPopover.style.display = "none";
  }
  if (event.target === adminPanel) {
    adminPanel.hidden = true;
  }
});

exportHistoryButton.addEventListener("click", () => {
  exportModal.style.display = "flex";
  const today = new Date().toISOString().split("T")[0];
  const lastWeek = new Date(Date.now() - 7 * 24 * 60 * 60 * 1000).toISOString().split("T")[0];
  exportFromDate.value = lastWeek;
  exportToDate.value = today;
});

closeExportModal.addEventListener("click", () => {
  exportModal.style.display = "none";
});

exportForm.addEventListener("submit", (event) => {
  event.preventDefault();
  exportHistory(exportFromDate.value, exportToDate.value, exportFormat.value);
  exportModal.style.display = "none";
});

sessionSearchInput.addEventListener("input", () => {
  window.clearTimeout(sessionSearchTimer);
  sessionSearchTimer = window.setTimeout(() => {
    loadSessions();
  }, 300);
});

document.querySelectorAll("[data-prompt]").forEach((button) => {
  button.addEventListener("click", () => {
    messageInput.value = button.dataset.prompt || "";
    messageInput.focus();
  });
});

apiBaseUrlInput.addEventListener("change", async () => {
  syncApiBaseUrl(apiBaseUrlInput.value);
  if (!currentUser) {
    return;
  }
  clearPatientSearchResults("Nhap thong tin roi bam Tim khi can chon benh nhan.");
  await loadSessions();
  await loadCostSummary();
  await loadNotifications().catch(() => {});
});

loginApiBaseUrlInput.addEventListener("change", () => {
  syncApiBaseUrl(loginApiBaseUrlInput.value);
});

boot();

async function boot() {
  syncApiBaseUrl(localStorage.getItem(API_BASE_URL_STORAGE_KEY) || apiBaseUrlInput.value);
  showAuthScreen();
  if (!accessToken()) {
    loginUsernameInput.focus();
    return;
  }

  try {
    currentUser = await apiGet("/api/auth/me", { suppressUnauthorizedRedirect: true });
    showApp();
    await initializeDashboard();
  } catch (error) {
    clearAuthState();
    showAuthScreen("Phien dang nhap da het han. Vui long dang nhap lai.");
  }
}

async function initializeDashboard() {
  resetMessages(
    hasFhirAccess()
      ? "Hoi cau tong quat hoac chon benh nhan o ben phai de bat dau."
      : "Hoi ve ho so FHIR cua ban, vi du thuoc, chi so, chan doan hoac lich su kham."
  );
  renderEmptyPatientSections();
  renderDetails(null);
  currentSessionId = null;
  sessionSearchInput.value = "";
  lastPatients = [];
  clearSelectedPatient();
  applyRoleUi();
  await loadSessions();
  await loadCostSummary();
  await loadNotifications().catch(() => {});
  ensureNotificationPolling();
}

function ensureNotificationPolling() {
  if (notificationsIntervalId) {
    return;
  }
  notificationsIntervalId = window.setInterval(() => {
    if (currentUser) {
      loadNotifications().catch(() => {});
    }
  }, 30000);
}

function syncApiBaseUrl(value) {
  const normalized = String(value || "http://localhost:8081").trim().replace(/\/$/, "") || "http://localhost:8081";
  apiBaseUrlInput.value = normalized;
  loginApiBaseUrlInput.value = normalized;
  localStorage.setItem(API_BASE_URL_STORAGE_KEY, normalized);
}

function apiBaseUrl() {
  return apiBaseUrlInput.value.trim().replace(/\/$/, "");
}

function accessToken() {
  return sessionStorage.getItem(AUTH_TOKEN_STORAGE_KEY);
}

function storeAccessToken(token) {
  sessionStorage.setItem(AUTH_TOKEN_STORAGE_KEY, token);
}

function clearAuthState() {
  sessionStorage.removeItem(AUTH_TOKEN_STORAGE_KEY);
  currentUser = null;
  selectedPatient = null;
  currentSessionId = null;
  adminPanel.hidden = true;
}

function hasFhirAccess() {
  return currentUser?.role === "DOCTOR" || currentUser?.role === "ADMIN";
}

function isAdmin() {
  return currentUser?.role === "ADMIN";
}

function showAuthScreen(message = "") {
  authScreen.hidden = false;
  appShell.hidden = true;
  currentUserPanel.hidden = true;
  loginError.hidden = !message;
  loginError.textContent = message;
}

function showApp() {
  authScreen.hidden = true;
  appShell.hidden = false;
  currentUserPanel.hidden = false;
  currentUserDisplayName.textContent = currentUser?.display_name || currentUser?.username || "-";
  currentUserMeta.textContent = [
    currentUser?.role || "-",
    currentUser?.status || "-",
    currentUser?.email || null,
  ].filter(Boolean).join(" | ");
  applyRoleUi();
}

function applyRoleUi() {
  const canUseFhir = hasFhirAccess();
  doctorOnlySections.forEach((section) => {
    section.hidden = !canUseFhir;
  });
  updateQuickPrompts(canUseFhir);
  userAccessNotice.hidden = canUseFhir;
  openAdminPanelButton.hidden = !isAdmin();

  if (canUseFhir) {
    if (!selectedPatient) {
      selectedPatientName.textContent = defaultSelectedPatientName;
      selectedPatientMeta.textContent = defaultSelectedPatientMeta;
      clearPatientSearchResults("Nhap thong tin roi bam Tim khi can chon benh nhan.");
      renderEmptyPatientSections();
    }
    return;
  }

  clearSelectedPatient();
  patientSearchInput.value = "";
  clearPatientSearchResults("Tai khoan USER khong duoc tim kiem danh sach benh nhan.");
  selectedPatientName.textContent = "Ho so cua toi";
  selectedPatientMeta.textContent = "Chatbot tu dung ho so FHIR da lien ket voi tai khoan cua ban.";
}

function updateQuickPrompts(canUseFhir) {
  messageInput.placeholder = canUseFhir
    ? "Vi du: Benh nhan nay dang dung thuoc gi?"
    : "Vi du: Toi dang dung thuoc gi?";

  const prompts = canUseFhir
    ? [
        ["Thuoc", "Benh nhan nay dang dung thuoc gi?"],
        ["Chi so", "Cho toi xem chi so gan day cua benh nhan nay"],
        ["Lich kham", "Lich su kham gan day cua benh nhan nay"],
      ]
    : [
        ["Thuoc cua toi", "Toi dang dung thuoc gi?"],
        ["Chi so cua toi", "Chi so gan day cua toi"],
        ["Lich kham cua toi", "Lich su kham gan day cua toi"],
      ];

  patientQuickPrompts.forEach((button, index) => {
    const [label, prompt] = prompts[index] || prompts[0];
    button.hidden = false;
    button.textContent = label;
    button.dataset.prompt = prompt;
  });
}

async function handleLogin(event) {
  event.preventDefault();
  syncApiBaseUrl(loginApiBaseUrlInput.value);
  loginError.hidden = true;
  loginError.textContent = "";
  loginButton.disabled = true;

  try {
    const data = await apiPost(
      "/api/auth/login",
      {
        username_or_email: loginUsernameInput.value.trim(),
        password: loginPasswordInput.value,
      },
      { auth: false, suppressUnauthorizedRedirect: true }
    );
    storeAccessToken(data.access_token);
    currentUser = data.user;
    loginPasswordInput.value = "";
    showApp();
    await initializeDashboard();
  } catch (error) {
    loginError.hidden = false;
    loginError.textContent = error.message || "Dang nhap that bai.";
  } finally {
    loginButton.disabled = false;
  }
}

async function handleLogout() {
  try {
    if (accessToken()) {
      await apiPost("/api/auth/logout", undefined, { suppressUnauthorizedRedirect: true });
    }
  } catch (error) {
    console.debug("Logout request failed", error);
  } finally {
    clearAuthState();
    showAuthScreen("Da dang xuat.");
  }
}

async function handleUnauthorized(detail) {
  clearAuthState();
  showAuthScreen(detail || "Ban can dang nhap de tiep tuc.");
}

async function apiGet(path, options = {}) {
  return apiRequest(path, { ...options, method: "GET" });
}

async function apiPost(path, payload, options = {}) {
  return apiRequest(path, { ...options, method: "POST", payload });
}

async function apiPatch(path, payload, options = {}) {
  return apiRequest(path, { ...options, method: "PATCH", payload });
}

async function apiRequest(path, options = {}) {
  const {
    method = "GET",
    payload,
    auth = true,
    responseType = "json",
    suppressUnauthorizedRedirect = false,
  } = options;

  const headers = new Headers();
  if (responseType === "json") {
    headers.set("Accept", "application/json");
  }
  if (payload !== undefined) {
    headers.set("Content-Type", "application/json");
  }
  if (auth && accessToken()) {
    headers.set("Authorization", `Bearer ${accessToken()}`);
  }

  const response = await fetch(`${apiBaseUrl()}${path}`, {
    method,
    headers,
    body: payload !== undefined ? JSON.stringify(payload) : undefined,
  });

  if (responseType === "blob") {
    return readBlobResponse(response, { suppressUnauthorizedRedirect });
  }
  return readJsonResponse(response, { suppressUnauthorizedRedirect });
}

async function readJsonResponse(response, options = {}) {
  const { suppressUnauthorizedRedirect = false } = options;
  if (response.status === 204) {
    return null;
  }
  const data = await response.json().catch(() => ({}));
  if (!response.ok) {
    const detail = data.detail || data.message || `Yeu cau that bai voi HTTP ${response.status}`;
    if (response.status === 401 && !suppressUnauthorizedRedirect) {
      await handleUnauthorized(detail);
    }
    throw new Error(detail);
  }
  return data;
}

async function readBlobResponse(response, options = {}) {
  const { suppressUnauthorizedRedirect = false } = options;
  if (!response.ok) {
    let detail = `Yeu cau that bai voi HTTP ${response.status}`;
    try {
      const data = await response.json();
      detail = data.detail || data.message || detail;
    } catch (error) {
      // Keep the generic HTTP message for non-JSON failures.
    }
    if (response.status === 401 && !suppressUnauthorizedRedirect) {
      await handleUnauthorized(detail);
    }
    throw new Error(detail);
  }
  return response.blob();
}

async function loadPatients(term) {
  if (!hasFhirAccess()) {
    return;
  }
  if (!term) {
    clearPatientSearchResults("Nhap ten, SDT, ngay sinh hoac ma dinh danh de tim.");
    return;
  }

  setStatus("Dang tim benh nhan", "loading");
  showPatientSearchResults(createPlaceholder("Dang tim benh nhan..."));

  try {
    let patients;
    if (/^demo-patient-[a-z0-9.-]+$/i.test(term)) {
      patients = [await apiGet(`/api/patients/${encodeURIComponent(term)}`)];
    } else {
      const params = buildPatientSearchParams(term);
      const data = await apiGet(`/api/patients?${params.toString()}`);
      patients = Array.isArray(data.patients) ? data.patients : [];
    }

    lastPatients = patients;
    renderPatientList(patients);
    setStatus("San sang", "ready");
  } catch (error) {
    showPatientSearchResults(createPlaceholder(error.message, "error-text"));
    setStatus("Loi", "error");
  }
}

function buildPatientSearchParams(term) {
  const params = new URLSearchParams({ limit: "20" });
  if (/^\d{4}-\d{2}-\d{2}$/.test(term)) {
    params.set("birth_date", term);
  } else if (/^\d{8,}$/.test(term.replace(/\s+/g, ""))) {
    params.set("phone", term.replace(/\s+/g, ""));
  } else if (/^[A-Z]+-\d+$/i.test(term)) {
    params.set("identifier", term);
  } else {
    params.set("name", term);
  }
  return params;
}

function renderPatientList(patients) {
  if (patients.length === 0) {
    showPatientSearchResults(createPlaceholder("Khong tim thay benh nhan phu hop."));
    return;
  }

  patientList.replaceChildren();
  patientList.classList.add("visible");
  for (const patient of patients) {
    const button = document.createElement("button");
    button.type = "button";
    button.className = "patient-row";
    button.dataset.patientId = patient.id;
    if (selectedPatient?.id === patient.id) {
      button.classList.add("active");
    }

    button.append(
      createEl("strong", "", patient.name || "Khong ro ten"),
      createEl(
        "span",
        "",
        [
          patient.id,
          patient.birth_date ? `Sinh: ${patient.birth_date}` : null,
          patient.gender ? `Gioi tinh: ${formatGender(patient.gender)}` : null,
          patient.phone ? `SDT: ${patient.phone}` : null,
        ].filter(Boolean).join(" | ")
      )
    );
    button.addEventListener("click", () => {
      selectPatient(patient, { resetSession: true, clearMessages: true });
    });
    patientList.append(button);
  }
}

function showPatientSearchResults(node) {
  patientList.replaceChildren(node);
  patientList.classList.add("visible");
}

function clearPatientSearchResults(text) {
  patientList.replaceChildren(createPlaceholder(text));
  patientList.classList.remove("visible");
}

function markSelectedPatientInResults() {
  document.querySelectorAll(".patient-row").forEach((node) => {
    node.classList.toggle("active", node.dataset.patientId === selectedPatient?.id);
  });
}

async function selectPatient(patient, options = {}) {
  if (!hasFhirAccess()) {
    return;
  }
  const { resetSession = true, clearMessages = true } = options;
  selectedPatient = patient;
  if (resetSession) {
    currentSessionId = null;
  }

  renderSelectedPatient(patient);
  markSelectedPatientInResults();
  if (clearMessages) {
    resetMessages(`Da chon ${patient.name || patient.id}.`);
    renderDetails(null);
  }
  await loadPatientProfile(patient.id);
  clearPatientSearchResults("Da chon benh nhan. Tim lai khi can doi benh nhan.");
  renderSessionsActiveState();
}

async function selectPatientById(patientId, options = {}) {
  if (!hasFhirAccess()) {
    return;
  }
  const patient = await apiGet(`/api/patients/${encodeURIComponent(normalizePatientId(patientId))}`);
  await selectPatient(patient, options);
}

function renderSelectedPatient(patient) {
  selectedPatientName.textContent = patient.name || patient.id;
  selectedPatientMeta.textContent = [
    patient.id,
    patient.birth_date ? `Sinh: ${patient.birth_date}` : null,
    patient.gender ? `Gioi tinh: ${formatGender(patient.gender)}` : null,
    patient.phone ? `SDT: ${patient.phone}` : null,
  ].filter(Boolean).join(" | ");
}

async function loadPatientProfile(patientId) {
  if (!hasFhirAccess()) {
    return;
  }
  renderPatientLoading();
  try {
    const [patient, encounters, observations, conditions, medications] = await Promise.all([
      apiGet(`/api/patients/${encodeURIComponent(patientId)}`),
      apiGet(`/api/patients/${encodeURIComponent(patientId)}/encounters?limit=5`),
      apiGet(`/api/patients/${encodeURIComponent(patientId)}/observations?limit=5`),
      apiGet(`/api/patients/${encodeURIComponent(patientId)}/conditions?limit=20`),
      apiGet(`/api/patients/${encodeURIComponent(patientId)}/medications?limit=20`),
    ]);

    selectedPatient = patient;
    renderSelectedPatient(patient);
    renderPatientSummary(patient);
    renderEncounters(encounters.encounters || []);
    renderObservations(observations.observations || []);
    renderConditions(conditions.conditions || []);
    renderMedications(medications.medications || []);
    setStatus("San sang", "ready");
  } catch (error) {
    patientSummary.replaceChildren(createPlaceholder(error.message, "error-text"));
    setStatus("Loi", "error");
  }
}

function renderPatientLoading() {
  patientSummary.replaceChildren(createPlaceholder("Dang tai ho so benh nhan..."));
  encountersList.replaceChildren();
  observationsList.replaceChildren();
  conditionsList.replaceChildren();
  medicationsList.replaceChildren();
}

function renderEmptyPatientSections() {
  patientSummary.replaceChildren(createPlaceholder("Chua chon benh nhan."));
  encountersList.replaceChildren(createPlaceholder("Chua co du lieu."));
  observationsList.replaceChildren(createPlaceholder("Chua co du lieu."));
  conditionsList.replaceChildren(createPlaceholder("Chua co du lieu."));
  medicationsList.replaceChildren(createPlaceholder("Chua co du lieu."));
}

function renderPatientSummary(patient) {
  patientSummary.replaceChildren();
  const rows = [
    ["Ma FHIR", patient.id],
    ["Ho ten", patient.name],
    ["Gioi tinh", formatGender(patient.gender)],
    ["Ngay sinh", patient.birth_date],
    ["SDT", patient.phone],
    ["Email", patient.email],
    ["Dinh danh", formatIdentifiers(patient.identifier)],
  ];

  for (const [label, value] of rows) {
    const item = document.createElement("div");
    item.className = "summary-item";
    item.append(createEl("span", "", label), createEl("strong", "", value || "-"));
    patientSummary.append(item);
  }
}

function renderEncounters(encounters) {
  renderList(encountersList, encounters, (encounter) => {
    const type = firstText(encounter.type) || "Lan kham";
    const period = encounter.period || {};
    return {
      title: type,
      lines: [
        `Trang thai: ${encounter.status || "-"}`,
        period.start ? `Bat dau: ${formatDateTime(period.start)}` : null,
        period.end ? `Ket thuc: ${formatDateTime(period.end)}` : null,
      ],
    };
  });
}

function renderObservations(observations) {
  renderList(observationsList, observations, (observation) => ({
    title: observation.code || "Chi so",
    lines: [
      observationValueText(observation),
      observation.effective_time ? `Thoi diem: ${formatDateTime(observation.effective_time)}` : null,
      observation.status ? `Trang thai: ${observation.status}` : null,
    ],
  }));
}

function renderConditions(conditions) {
  renderList(conditionsList, conditions, (condition) => ({
    title: condition.code || "Chan doan",
    lines: [
      condition.clinical_status ? `Lam sang: ${condition.clinical_status}` : null,
      condition.verification_status ? `Xac nhan: ${condition.verification_status}` : null,
      condition.recorded_date ? `Ghi nhan: ${formatDateTime(condition.recorded_date)}` : null,
    ],
  }));
}

function renderMedications(medications) {
  renderList(medicationsList, medications, (medication) => ({
    title: medication.medication || "Thuoc",
    lines: [
      medication.status ? `Trang thai: ${medication.status}` : null,
      medication.authored_on ? `Ngay ke: ${formatDateTime(medication.authored_on)}` : null,
      medication.dosage?.length ? `Lieu dung: ${medication.dosage.join("; ")}` : null,
    ],
  }));
}

function renderList(container, items, mapItem) {
  container.replaceChildren();
  if (!items.length) {
    container.append(createPlaceholder("Chua co du lieu."));
    return;
  }

  for (const item of items) {
    const view = mapItem(item);
    const node = document.createElement("article");
    node.className = "compact-item";
    node.append(createEl("strong", "", view.title));

    const lines = (view.lines || []).filter(Boolean);
    if (lines.length) {
      const detail = document.createElement("div");
      detail.className = "compact-lines";
      for (const line of lines) {
        detail.append(createEl("span", "", line));
      }
      node.append(detail);
    }
    container.append(node);
  }
}

async function submitChat(message, patientId, displayText) {
  appendMessage("user", displayText || message);
  messageInput.value = "";
  setFormDisabled(true);
  setStatus("Dang gui", "loading");

  try {
    const payload = {
      message,
      patient_id: patientId || null,
    };
    if (currentSessionId) {
      payload.session_id = currentSessionId;
    }

    const data = await apiPost("/api/chat", payload);
    currentSessionId = data.session_id || currentSessionId;
    appendMessage("assistant", data.answer || "Khong co cau tra loi.", data);
    renderDetails(data);
    await loadSessions();
    await loadCostSummary();
    await loadNotifications().catch(() => {});
    setStatus("San sang", "ready");
  } catch (error) {
    appendMessage("error", error.message || "Yeu cau that bai.");
    setStatus("Loi", "error");
    await loadNotifications().catch(() => {});
  } finally {
    setFormDisabled(false);
    messageInput.focus();
  }
}

async function loadSessions() {
  sessionList.replaceChildren(createPlaceholder("Dang tai lich su..."));
  try {
    const params = new URLSearchParams({ limit: "30" });
    const query = sessionSearchInput.value.trim();
    if (query) {
      params.set("query", query);
    }
    const data = await apiGet(`/api/chat/sessions?${params.toString()}`);
    renderSessionList(data.sessions || [], Boolean(query));
  } catch (error) {
    sessionList.replaceChildren(createPlaceholder(error.message, "error-text"));
  }
}

function renderSessionList(sessions, isSearch = false) {
  sessionList.replaceChildren();
  if (!sessions.length && isSearch) {
    sessionList.append(createPlaceholder("Khong tim thay hoi thoai phu hop."));
    return;
  }
  if (!sessions.length) {
    sessionList.append(createPlaceholder("Chua co phien chat."));
    return;
  }

  for (const session of sessions) {
    const button = document.createElement("button");
    button.type = "button";
    button.className = "session-row";
    button.dataset.sessionId = session.id;
    button.dataset.activePatientId = session.active_patient_id || "";
    if (session.id === currentSessionId) {
      button.classList.add("active");
    }

    button.append(
      createEl("strong", "", session.title || "Cuoc tro chuyen"),
      createEl("span", "", session.last_message_preview || "Chua co tin nhan"),
      createEl("small", "", `${session.message_count || 0} tin nhan | ${formatDateTime(session.updated_at)}`)
    );
    button.addEventListener("click", () => loadSessionMessages(session.id, session.active_patient_id));
    sessionList.append(button);
  }
}

async function loadSessionMessages(id, activePatientId) {
  setStatus("Dang tai lich su", "loading");
  try {
    const data = await apiGet(`/api/chat/sessions/${encodeURIComponent(id)}/messages`);
    currentSessionId = data.session_id;
    renderMessagesFromHistory(data.messages || []);
    renderSessionsActiveState();
    renderDetails(null);
    sessionId.textContent = currentSessionId || "-";
    if (activePatientId && hasFhirAccess()) {
      await selectPatientById(activePatientId, { resetSession: false, clearMessages: false });
    } else {
      clearSelectedPatient();
      applyRoleUi();
    }
    setStatus("San sang", "ready");
  } catch (error) {
    appendMessage("error", error.message || "Khong tai duoc lich su.");
    setStatus("Loi", "error");
  }
}

function clearSelectedPatient() {
  selectedPatient = null;
  selectedPatientName.textContent = "Chua chon benh nhan";
  selectedPatientMeta.textContent = "Phien nay chua gan benh nhan cu the.";
  renderEmptyPatientSections();
}

function renderMessagesFromHistory(items) {
  messages.replaceChildren();
  if (!items.length) {
    messages.append(createHistoryNotice("Phien nay chua co tin nhan."));
    return;
  }

  for (const item of items) {
    const role = item.role === "assistant" ? "assistant" : "user";
    appendMessage(role, item.content, role === "assistant" ? { message_id: item.id } : undefined);
  }
}

function renderSessionsActiveState() {
  document.querySelectorAll(".session-row").forEach((node) => {
    node.classList.toggle("active", node.dataset.sessionId === currentSessionId);
  });

  exportPdfButton.style.display = currentSessionId ? "inline-block" : "none";
  exportCsvButton.style.display = currentSessionId ? "inline-block" : "none";
}

function resetMessages(text) {
  messages.replaceChildren(createHistoryNotice(text));
}

function createHistoryNotice(text) {
  const article = document.createElement("article");
  article.className = "message assistant";
  article.append(createEl("div", "message-label", "Tro ly"), createEl("p", "", text));
  return article;
}

function appendMessage(role, text, data) {
  const article = document.createElement("article");
  article.className = `message ${role}`;

  const label = createEl(
    "div",
    "message-label",
    role === "user" ? "Ban" : role === "error" ? "Loi" : "Tro ly"
  );
  const body = role === "assistant" ? renderAssistantAnswer(text) : createEl("p", "", text || "");

  article.append(label, body);
  if (role === "assistant") {
    const candidates = renderPatientCandidates(data);
    if (candidates) {
      article.append(candidates);
    }
    if (data?.message_id) {
      article.dataset.messageId = data.message_id;
      article.append(renderFeedbackRow(data.message_id));
    }
  }

  messages.append(article);
  messages.scrollTop = messages.scrollHeight;
}

function renderFeedbackRow(messageId) {
  const wrapper = document.createElement("div");
  wrapper.className = "feedback-wrapper";

  const triggerBtn = document.createElement("button");
  triggerBtn.type = "button";
  triggerBtn.className = "feedback-trigger";
  triggerBtn.textContent = "Danh gia";

  const panel = document.createElement("div");
  panel.className = "feedback-panel";
  panel.hidden = true;

  let pendingRating = 0;
  const starsEl = document.createElement("div");
  starsEl.className = "feedback-stars";

  [1, 2, 3, 4, 5].forEach((val) => {
    const btn = document.createElement("button");
    btn.type = "button";
    btn.className = "feedback-btn";
    btn.title = `${val} sao`;
    btn.textContent = "*";
    btn.addEventListener("mouseover", () => {
      starsEl.querySelectorAll(".feedback-btn").forEach((node, i) => node.classList.toggle("hover", i < val));
    });
    btn.addEventListener("mouseout", () => {
      starsEl.querySelectorAll(".feedback-btn").forEach((node, i) => {
        node.classList.remove("hover");
        node.classList.toggle("active", i < pendingRating);
      });
    });
    btn.addEventListener("click", () => {
      pendingRating = val;
      starsEl.querySelectorAll(".feedback-btn").forEach((node, i) => node.classList.toggle("active", i < val));
    });
    starsEl.append(btn);
  });

  const textarea = document.createElement("textarea");
  textarea.placeholder = "Nhan xet (khong bat buoc)...";
  textarea.rows = 2;

  const submitBtn = document.createElement("button");
  submitBtn.type = "button";
  submitBtn.className = "feedback-submit";
  submitBtn.textContent = "Gui danh gia";
  submitBtn.addEventListener("click", async () => {
    if (!pendingRating) {
      return;
    }
    await handleFeedback(messageId, pendingRating, textarea.value.trim(), wrapper, starsEl);
  });

  panel.append(starsEl, textarea, submitBtn);
  triggerBtn.addEventListener("click", () => {
    const opening = panel.hidden;
    panel.hidden = !panel.hidden;
    triggerBtn.classList.toggle("open", opening);
    if (opening) {
      textarea.focus();
    }
  });

  wrapper.append(triggerBtn, panel);
  return wrapper;
}

async function handleFeedback(messageId, rating, comment, wrapper, starsEl) {
  try {
    await apiPost(`/api/chat/messages/${encodeURIComponent(messageId)}/feedback`, {
      rating,
      comment: comment || null,
    });
    starsEl.querySelectorAll(".feedback-btn").forEach((btn, index) => {
      btn.classList.toggle("active", index < rating);
      btn.disabled = true;
    });
    wrapper.replaceWith(createEl("span", "feedback-thanks", `${rating}/5 - Cam on ban da danh gia!`));
  } catch (error) {
    console.error("Feedback failed", error);
  }
}

function renderPatientCandidates(data) {
  if (!hasFhirAccess() || !data?.needs_patient_selection || !Array.isArray(data.patient_candidates)) {
    return null;
  }

  const candidates = data.patient_candidates.filter((item) => item?.id);
  if (!candidates.length) {
    return null;
  }

  const panel = document.createElement("div");
  panel.className = "candidate-panel";
  panel.append(createEl("div", "candidate-title", "Chon dung benh nhan de tiep tuc"));

  for (const candidate of candidates) {
    const row = document.createElement("div");
    row.className = "candidate-row";

    const info = document.createElement("div");
    info.className = "candidate-info";
    info.append(
      createEl("strong", "", `${candidate.name || "Khong ro ten"} (${candidate.id})`),
      createEl(
        "span",
        "",
        [
          candidate.birth_date ? `Sinh: ${candidate.birth_date}` : null,
          candidate.phone ? `SDT: ${candidate.phone}` : null,
          candidate.gender ? `Gioi tinh: ${formatGender(candidate.gender)}` : null,
        ].filter(Boolean).join(" | ")
      )
    );

    const button = createEl("button", "candidate-button", "Chon");
    button.type = "button";
    button.addEventListener("click", async () => {
      const patientId = normalizePatientId(candidate.id);
      await selectPatientById(patientId, { resetSession: false, clearMessages: false });
      const question = data.pending_question || messageInput.value.trim();
      const label = `Chon Patient/${patientId} - ${candidate.name || "khong ro ten"}`;
      await submitChat(question, patientId, label);
    });

    row.append(info, button);
    panel.append(row);
  }
  return panel;
}

function renderAssistantAnswer(text) {
  const answer = String(text || "").trim();
  const body = document.createElement("div");
  body.className = "message-body";

  for (const line of answer.split(/\n+/).map((item) => item.trim()).filter(Boolean)) {
    body.append(createEl("p", "", line));
  }

  if (!body.childElementCount) {
    body.append(createEl("p", "", "Khong co cau tra loi."));
  }
  return body;
}

function renderDetails(data) {
  sessionId.textContent = data?.session_id || currentSessionId || "-";
  intent.textContent = data?.intent || "-";
  answerSource.textContent = data?.answer_source || "-";
  toolName.textContent = data?.tool_name || "-";
  responseScope.textContent = data?.all_patients
    ? "Tat ca benh nhan"
    : data?.patient_id
      ? "Mot benh nhan"
      : data
        ? "Khong gan benh nhan"
        : "-";
  usageBlock.textContent = data?.usage ? JSON.stringify(data.usage, null, 2) : "-";

  evidenceList.replaceChildren();
  const evidence = Array.isArray(data?.evidence) ? data.evidence : [];
  if (!evidence.length) {
    evidenceList.append(createPlaceholder("Chua co du lieu tham chieu."));
    return;
  }

  for (const item of evidence) {
    const node = document.createElement("article");
    node.className = "evidence-item";
    node.append(
      createEl("strong", "", `${item.resource_type || "Resource"} ${item.id || ""}`.trim()),
      createEl("p", "", item.summary || "-")
    );

    if (item.data) {
      const details = document.createElement("details");
      details.className = "evidence-json";
      details.append(createEl("summary", "", "Du lieu chi tiet"));
      details.append(createEl("pre", "", JSON.stringify(item.data, null, 2)));
      node.append(details);
    }
    evidenceList.append(node);
  }
}

async function loadCostSummary() {
  if (!costSummary) {
    return;
  }

  costSummary.replaceChildren(createPlaceholder("Dang tai chi phi AI..."));
  const today = todayIsoDate();
  try {
    const [quota, cost] = await Promise.all([
      apiGet("/api/quota/status"),
      apiGet(`/api/usage/cost-summary?from=${today}&to=${today}`),
    ]);
    renderCostSummary(cost, quota);
  } catch (error) {
    costSummary.replaceChildren(createPlaceholder(error.message || "Khong tai duoc chi phi AI.", "error-text"));
  }
}

function renderCostSummary(cost, quota) {
  costSummary.replaceChildren();

  const grid = document.createElement("div");
  grid.className = "cost-grid";
  grid.append(
    costMetric("Token hom nay", formatInteger(cost?.total_tokens)),
    costMetric("Luot goi hom nay", `${formatInteger(quota?.used_requests)} luot`),
    costMetric("Chi phi hom nay", formatUsd(cost?.estimated_cost_usd)),
    costMetric("Chi phi con lai", formatUsd(quota?.remaining_cost_usd)),
    costMetric("Han muc chi phi/ngay", formatUsd(quota?.daily_cost_limit_usd)),
    costMetric("Han muc luot goi/ngay", `${formatInteger(quota?.daily_request_limit)} luot`)
  );
  costSummary.append(grid);

  const topModel = Array.isArray(cost?.models) && cost.models.length ? cost.models[0] : null;
  if (topModel) {
    const modelLine = [
      topModel.llm_provider,
      topModel.llm_model,
      `${formatInteger(topModel.request_count)} luot`,
      formatUsd(topModel.estimated_cost_usd),
    ].filter(Boolean).join(" | ");
    costSummary.append(createEl("p", "cost-model", `Model chinh: ${modelLine}`));
  } else {
    costSummary.append(createEl("p", "muted", "Chua co luot goi AI thanh cong hom nay."));
  }

  const missingPricing = Array.isArray(cost?.missing_pricing_models) ? cost.missing_pricing_models : [];
  if (missingPricing.length) {
    const models = missingPricing
      .map((item) => `${item.llm_provider || "-"} / ${item.llm_model || "-"}`)
      .join(", ");
    costSummary.append(createEl("p", "cost-warning", `Chua co bang gia cho model nay: ${models}.`));
  }
}

function costMetric(label, value) {
  const item = document.createElement("div");
  item.className = "cost-metric";
  item.append(createEl("span", "", label), createEl("strong", "", value || "-"));
  return item;
}

async function loadAdminUsers() {
  if (!isAdmin()) {
    return;
  }

  adminUsersList.replaceChildren(createPlaceholder("Dang tai danh sach user..."));
  try {
    const data = await apiGet("/api/admin/users?page=0&size=50");
    renderAdminUsers(data.users || []);
  } catch (error) {
    adminUsersList.replaceChildren(createPlaceholder(error.message, "error-text"));
  }
}

function renderAdminUsers(users) {
  adminUsersList.replaceChildren();
  if (!users.length) {
    adminUsersList.append(createPlaceholder("Chua co user."));
    return;
  }

  for (const user of users) {
    const row = document.createElement("article");
    row.className = "admin-user-row";

    const main = document.createElement("div");
    main.className = "admin-user-main";
    main.append(
      createEl("strong", "", user.display_name || user.username),
      createEl("span", "admin-user-meta", `${user.username} | ${user.email || "-"} | ${user.id}`)
    );

    const badges = document.createElement("div");
    badges.className = "user-badges";
    badges.append(
      createEl("span", `user-badge role-${String(user.role || "").toLowerCase()}`, user.role || "-"),
      createEl("span", `user-badge status-${String(user.status || "").toLowerCase()}`, user.status || "-")
    );

    const actions = document.createElement("div");
    actions.className = "admin-user-actions";
    actions.append(renderRoleControl(user), renderStatusControl(user));

    row.append(main, badges, actions);
    adminUsersList.append(row);
  }
}

function renderRoleControl(user) {
  const wrapper = document.createElement("div");
  wrapper.className = "admin-inline-form";
  const select = document.createElement("select");
  for (const role of ["USER", "DOCTOR", "ADMIN"]) {
    const option = document.createElement("option");
    option.value = role;
    option.textContent = role;
    option.selected = user.role === role;
    select.append(option);
  }
  const button = createEl("button", "", "Doi role");
  button.type = "button";
  button.disabled = user.id === currentUser?.id && select.value !== "ADMIN";
  select.addEventListener("change", () => {
    button.disabled = user.id === currentUser?.id && select.value !== "ADMIN";
  });
  button.addEventListener("click", async () => {
    await updateAdminUserRole(user.id, select.value);
  });
  wrapper.append(select, button);
  return wrapper;
}

function renderStatusControl(user) {
  const wrapper = document.createElement("div");
  wrapper.className = "admin-inline-form";
  const select = document.createElement("select");
  for (const status of ["ACTIVE", "LOCKED", "DISABLED"]) {
    const option = document.createElement("option");
    option.value = status;
    option.textContent = status;
    option.selected = user.status === status;
    select.append(option);
  }
  const button = createEl("button", "", "Doi trang thai");
  button.type = "button";
  button.disabled = user.id === currentUser?.id && select.value !== "ACTIVE";
  select.addEventListener("change", () => {
    button.disabled = user.id === currentUser?.id && select.value !== "ACTIVE";
  });
  button.addEventListener("click", async () => {
    await updateAdminUserStatus(user.id, select.value);
  });
  wrapper.append(select, button);
  return wrapper;
}

async function updateAdminUserRole(userId, role) {
  try {
    await apiPatch(`/api/admin/users/${encodeURIComponent(userId)}/role`, { role });
    await loadAdminUsers();
  } catch (error) {
    adminUsersList.prepend(createPlaceholder(error.message, "error-text"));
  }
}

async function updateAdminUserStatus(userId, status) {
  try {
    await apiPatch(`/api/admin/users/${encodeURIComponent(userId)}/status`, { status });
    await loadAdminUsers();
  } catch (error) {
    adminUsersList.prepend(createPlaceholder(error.message, "error-text"));
  }
}

function observationValueText(observation) {
  if (Array.isArray(observation.components) && observation.components.length) {
    const components = observation.components
      .map((component) => {
        const label = component.code || component.code_text || "Thanh phan";
        return `${label}: ${quantityText(component.value)}`;
      })
      .join("; ");
    return components || "Gia tri: -";
  }
  if (observation.value) {
    return `Gia tri: ${quantityText(observation.value)}`;
  }
  if (observation.value_string) {
    return `Gia tri: ${observation.value_string}`;
  }
  if (observation.value_integer !== null && observation.value_integer !== undefined) {
    return `Gia tri: ${observation.value_integer}`;
  }
  return "Gia tri: -";
}

function quantityText(value) {
  if (!value || typeof value !== "object") {
    return "-";
  }
  return [value.value, value.unit || value.code]
    .filter((item) => item !== null && item !== undefined && item !== "")
    .join(" ") || "-";
}

function firstText(items) {
  if (!Array.isArray(items) || !items.length) {
    return "";
  }
  return items[0].text || items[0].display || "";
}

function formatIdentifiers(identifiers) {
  if (!Array.isArray(identifiers) || !identifiers.length) {
    return "";
  }
  return identifiers.map((item) => item.value).filter(Boolean).join(", ");
}

function normalizePatientId(patientId) {
  return String(patientId || "").replace(/^Patient\//i, "");
}

function formatGender(gender) {
  if (gender === "male") {
    return "nam";
  }
  if (gender === "female") {
    return "nu";
  }
  if (gender === "other") {
    return "khac";
  }
  return "khong ro";
}

function formatDateTime(value) {
  if (!value) {
    return "-";
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }
  return date.toLocaleString("vi-VN", {
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  });
}

function todayIsoDate() {
  const now = new Date();
  const local = new Date(now.getTime() - now.getTimezoneOffset() * 60000);
  return local.toISOString().slice(0, 10);
}

function formatInteger(value) {
  return new Intl.NumberFormat("vi-VN").format(Number(value || 0));
}

function formatUsd(value) {
  return new Intl.NumberFormat("vi-VN", {
    style: "currency",
    currency: "USD",
    minimumFractionDigits: 6,
    maximumFractionDigits: 6,
  }).format(Number(value || 0));
}

async function exportSession(format) {
  if (!currentSessionId) {
    return;
  }
  await downloadFromApi(
    `/api/chat/sessions/${encodeURIComponent(currentSessionId)}/export?format=${encodeURIComponent(format)}`,
    `chat-session-${currentSessionId}.${format}`
  );
}

async function exportHistory(from, to, format) {
  if (!from || !to) {
    return;
  }
  await downloadFromApi(
    `/api/chat/export?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}&format=${encodeURIComponent(format)}`,
    `chat-history-${from}-${to}.${format}`
  );
}

async function downloadFromApi(path, filename) {
  try {
    const blob = await apiGet(path, { responseType: "blob" });
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = filename;
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    URL.revokeObjectURL(url);
  } catch (error) {
    appendMessage("error", error.message || "Khong tai duoc file export.");
  }
}

function createPlaceholder(text, className = "muted") {
  return createEl("p", className, text);
}

function createEl(tagName, className, text) {
  const node = document.createElement(tagName);
  if (className) {
    node.className = className;
  }
  if (text !== undefined && text !== null) {
    node.textContent = text;
  }
  return node;
}

function setStatus(text, state) {
  statusText.textContent = text;
  statusText.className = `status ${state || ""}`.trim();
}

function setFormDisabled(disabled) {
  sendButton.disabled = disabled;
  messageInput.disabled = disabled;
  document.querySelectorAll("[data-prompt]").forEach((button) => {
    button.disabled = disabled;
  });
}

async function loadNotifications() {
  if (!currentUser) {
    return;
  }
  try {
    const data = await apiGet("/api/notifications");
    const unreadCount = data.unread_count || 0;

    if (unreadCount > 0) {
      const oldVal = parseInt(notificationBadge.textContent || "0", 10);
      notificationBadge.textContent = unreadCount;
      notificationBadge.style.display = "flex";

      if (unreadCount > oldVal) {
        notificationBadge.classList.add("animate-bounce");
        setTimeout(() => {
          notificationBadge.classList.remove("animate-bounce");
        }, 400);
      }
    } else {
      notificationBadge.style.display = "none";
      notificationBadge.textContent = "0";
    }

    renderNotificationList(data.notifications || []);
  } catch (error) {
    console.error("Loi khi tai thong bao:", error);
  }
}

function renderNotificationList(items) {
  notificationList.replaceChildren();

  if (!items || items.length === 0) {
    const empty = document.createElement("div");
    empty.className = "popover-empty";
    empty.append(createEl("span", "popover-empty-icon", "!"), createEl("span", "", "Khong co thong bao nao"));
    notificationList.appendChild(empty);
    return;
  }

  items.forEach((item) => {
    const div = document.createElement("div");
    const typeClass = `type-${String(item.type || "system").toLowerCase().replace(/_/g, "-")}`;
    div.className = `notification-item ${item.is_read ? "" : "unread"} ${typeClass}`;

    const headerDiv = document.createElement("div");
    headerDiv.className = "notification-item-header";

    const titleSpan = document.createElement("span");
    titleSpan.className = "notification-item-title";
    titleSpan.textContent = item.title || "Thong bao";

    const timeSpan = document.createElement("span");
    timeSpan.className = "notification-item-time";
    timeSpan.textContent = formatTimeAgo(item.created_at);

    headerDiv.append(titleSpan, timeSpan);

    const contentDiv = document.createElement("div");
    contentDiv.className = "notification-item-content";
    contentDiv.textContent = item.content || "";

    div.append(headerDiv, contentDiv);
    div.addEventListener("click", async (event) => {
      event.stopPropagation();
      if (!item.is_read) {
        try {
          await apiPost(`/api/notifications/${item.id}/read`);
          await loadNotifications();
        } catch (error) {
          console.error("Khong the danh dau thong bao da doc:", error);
        }
      }
    });

    notificationList.appendChild(div);
  });
}

function formatTimeAgo(isoString) {
  try {
    const date = new Date(isoString);
    const now = new Date();
    const seconds = Math.floor((now - date) / 1000);

    if (Number.isNaN(seconds)) {
      return "";
    }
    if (seconds < 60) {
      return "Vua xong";
    }
    const minutes = Math.floor(seconds / 60);
    if (minutes < 60) {
      return `${minutes} phut truoc`;
    }
    const hours = Math.floor(minutes / 60);
    if (hours < 24) {
      return `${hours} gio truoc`;
    }
    const days = Math.floor(hours / 24);
    if (days < 30) {
      return `${days} ngay truoc`;
    }

    return date.toLocaleDateString("vi-VN", {
      hour: "2-digit",
      minute: "2-digit",
      day: "2-digit",
      month: "2-digit",
    });
  } catch (error) {
    return "";
  }
}
