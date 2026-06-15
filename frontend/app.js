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

let selectedPatient = null;
let currentSessionId = null;
let lastPatients = [];
let sessionSearchTimer = null;

patientSearchForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  await loadPatients(patientSearchInput.value.trim());
});

chatForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  const message = messageInput.value.trim();
  if (!message) {
    return;
  }
  await submitChat(message, selectedPatient?.id || null, message);
});

newChatButton.addEventListener("click", () => {
  currentSessionId = null;
  resetMessages("Cuộc chat mới đã sẵn sàng.");
  renderDetails(null);
  renderSessionsActiveState();
  messageInput.focus();
});

refreshSessionsButton.addEventListener("click", () => {
  sessionSearchInput.value = "";
  loadSessions();
});

exportPdfButton.addEventListener("click", () => exportSession("pdf"));
exportCsvButton.addEventListener("click", () => exportSession("csv"));

notificationBellButton.addEventListener("click", (e) => {
  e.stopPropagation();
  const isOpen = notificationPopover.style.display === "flex";
  if (isOpen) {
    notificationPopover.style.display = "none";
  } else {
    notificationPopover.style.display = "flex";
    loadNotifications().catch(() => {});
  }
});

markAllNotificationsRead.addEventListener("click", async (e) => {
  e.stopPropagation();
  try {
    await apiPost("/api/notifications/read-all");
    await loadNotifications();
  } catch (error) {
    console.error("Không thể đánh dấu tất cả đã đọc:", error);
  }
});

document.addEventListener("click", (e) => {
  if (!e.target.closest(".notification-container")) {
    notificationPopover.style.display = "none";
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

exportForm.addEventListener("submit", (e) => {
  e.preventDefault();
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

apiBaseUrlInput.addEventListener("change", () => {
  clearPatientSearchResults("Nhập thông tin rồi bấm Tìm khi cần chọn bệnh nhân.");
  loadSessions();
  loadCostSummary();
  loadNotifications().catch(() => {});
});

init();

async function init() {
  resetMessages("Hỏi câu tổng quát hoặc chọn bệnh nhân ở bên phải để bắt đầu.");
  renderEmptyPatientSections();
  clearPatientSearchResults("Nhập thông tin rồi bấm Tìm khi cần chọn bệnh nhân.");
  await loadSessions();
  await loadCostSummary();
  await loadNotifications().catch(() => {});
  setInterval(() => {
    loadNotifications().catch(() => {});
  }, 30000);
}

function apiBaseUrl() {
  return apiBaseUrlInput.value.trim().replace(/\/$/, "");
}

async function apiGet(path) {
  const response = await fetch(`${apiBaseUrl()}${path}`, {
    headers: { Accept: "application/json" },
  });
  return readJsonResponse(response);
}

async function apiPost(path, payload) {
  const response = await fetch(`${apiBaseUrl()}${path}`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Accept: "application/json",
    },
    body: JSON.stringify(payload),
  });
  return readJsonResponse(response);
}

async function readJsonResponse(response) {
  const data = await response.json().catch(() => ({}));
  if (!response.ok) {
    throw new Error(data.detail || `Yêu cầu thất bại với HTTP ${response.status}`);
  }
  return data;
}

async function loadPatients(term) {
  if (!term) {
    clearPatientSearchResults("Nhập tên, SĐT, ngày sinh hoặc mã định danh để tìm.");
    return;
  }

  setStatus("Đang tìm bệnh nhân", "loading");
  showPatientSearchResults(createPlaceholder("Đang tìm bệnh nhân..."));

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
    setStatus("Sẵn sàng", "ready");
  } catch (error) {
    showPatientSearchResults(createPlaceholder(error.message, "error-text"));
    setStatus("Lỗi", "error");
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
    showPatientSearchResults(createPlaceholder("Không tìm thấy bệnh nhân phù hợp."));
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
      createEl("strong", "", patient.name || "Không rõ tên"),
      createEl(
        "span",
        "",
        [
          patient.id,
          patient.birth_date ? `Sinh: ${patient.birth_date}` : null,
          patient.gender ? `Giới tính: ${formatGender(patient.gender)}` : null,
          patient.phone ? `SĐT: ${patient.phone}` : null,
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
  const { resetSession = true, clearMessages = true } = options;
  selectedPatient = patient;
  if (resetSession) {
    currentSessionId = null;
  }

  renderSelectedPatient(patient);
  markSelectedPatientInResults();
  if (clearMessages) {
    resetMessages(`Đã chọn ${patient.name || patient.id}.`);
    renderDetails(null);
  }
  await loadPatientProfile(patient.id);
  clearPatientSearchResults("Đã chọn bệnh nhân. Tìm lại khi cần đổi bệnh nhân.");
  renderSessionsActiveState();
}

async function selectPatientById(patientId, options = {}) {
  const patient = await apiGet(`/api/patients/${encodeURIComponent(normalizePatientId(patientId))}`);
  await selectPatient(patient, options);
}

function renderSelectedPatient(patient) {
  selectedPatientName.textContent = patient.name || patient.id;
  selectedPatientMeta.textContent = [
    patient.id,
    patient.birth_date ? `Sinh: ${patient.birth_date}` : null,
    patient.gender ? `Giới tính: ${formatGender(patient.gender)}` : null,
    patient.phone ? `SĐT: ${patient.phone}` : null,
  ].filter(Boolean).join(" | ");
}

async function loadPatientProfile(patientId) {
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
    setStatus("Sẵn sàng", "ready");
  } catch (error) {
    patientSummary.replaceChildren(createPlaceholder(error.message, "error-text"));
    setStatus("Lỗi", "error");
  }
}

function renderPatientLoading() {
  patientSummary.replaceChildren(createPlaceholder("Đang tải hồ sơ bệnh nhân..."));
  encountersList.replaceChildren();
  observationsList.replaceChildren();
  conditionsList.replaceChildren();
  medicationsList.replaceChildren();
}

function renderEmptyPatientSections() {
  patientSummary.replaceChildren(createPlaceholder("Chưa chọn bệnh nhân."));
  encountersList.replaceChildren(createPlaceholder("Chưa có dữ liệu."));
  observationsList.replaceChildren(createPlaceholder("Chưa có dữ liệu."));
  conditionsList.replaceChildren(createPlaceholder("Chưa có dữ liệu."));
  medicationsList.replaceChildren(createPlaceholder("Chưa có dữ liệu."));
}

function renderPatientSummary(patient) {
  patientSummary.replaceChildren();
  const rows = [
    ["Mã FHIR", patient.id],
    ["Họ tên", patient.name],
    ["Giới tính", formatGender(patient.gender)],
    ["Ngày sinh", patient.birth_date],
    ["SĐT", patient.phone],
    ["Email", patient.email],
    ["Định danh", formatIdentifiers(patient.identifier)],
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
    const type = firstText(encounter.type) || "Lần khám";
    const period = encounter.period || {};
    return {
      title: type,
      lines: [
        `Trạng thái: ${encounter.status || "-"}`,
        period.start ? `Bắt đầu: ${formatDateTime(period.start)}` : null,
        period.end ? `Kết thúc: ${formatDateTime(period.end)}` : null,
      ],
    };
  });
}

function renderObservations(observations) {
  renderList(observationsList, observations, (observation) => ({
    title: observation.code || "Chỉ số",
    lines: [
      observationValueText(observation),
      observation.effective_time ? `Thời điểm: ${formatDateTime(observation.effective_time)}` : null,
      observation.status ? `Trạng thái: ${observation.status}` : null,
    ],
  }));
}

function renderConditions(conditions) {
  renderList(conditionsList, conditions, (condition) => ({
    title: condition.code || "Chẩn đoán",
    lines: [
      condition.clinical_status ? `Lâm sàng: ${condition.clinical_status}` : null,
      condition.verification_status ? `Xác nhận: ${condition.verification_status}` : null,
      condition.recorded_date ? `Ghi nhận: ${formatDateTime(condition.recorded_date)}` : null,
    ],
  }));
}

function renderMedications(medications) {
  renderList(medicationsList, medications, (medication) => ({
    title: medication.medication || "Thuốc",
    lines: [
      medication.status ? `Trạng thái: ${medication.status}` : null,
      medication.authored_on ? `Ngày kê: ${formatDateTime(medication.authored_on)}` : null,
      medication.dosage?.length ? `Liều dùng: ${medication.dosage.join("; ")}` : null,
    ],
  }));
}

function renderList(container, items, mapItem) {
  container.replaceChildren();
  if (!items.length) {
    container.append(createPlaceholder("Chưa có dữ liệu."));
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
  setStatus("Đang gửi", "loading");

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
    appendMessage("assistant", data.answer || "Không có câu trả lời.", data);
    renderDetails(data);
    await loadSessions();
    await loadCostSummary();
    await loadNotifications().catch(() => {});
    setStatus("Sẵn sàng", "ready");
  } catch (error) {
    appendMessage("error", error.message || "Yêu cầu thất bại.");
    setStatus("Lỗi", "error");
    await loadNotifications().catch(() => {});
  } finally {
    setFormDisabled(false);
    messageInput.focus();
  }
}

async function loadSessions() {
  sessionList.replaceChildren(createPlaceholder("Đang tải lịch sử..."));
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
    sessionList.append(createPlaceholder("Không tìm thấy hội thoại phù hợp."));
    return;
  }
  if (!sessions.length) {
    sessionList.append(createPlaceholder("Chưa có phiên chat."));
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
      createEl("strong", "", session.title || "Cuộc trò chuyện"),
      createEl("span", "", session.last_message_preview || "Chưa có tin nhắn"),
      createEl("small", "", `${session.message_count || 0} tin nhắn | ${formatDateTime(session.updated_at)}`)
    );
    button.addEventListener("click", () => loadSessionMessages(session.id, session.active_patient_id));
    sessionList.append(button);
  }
}

async function loadSessionMessages(id, activePatientId) {
  setStatus("Đang tải lịch sử", "loading");
  try {
    const data = await apiGet(`/api/chat/sessions/${encodeURIComponent(id)}/messages`);
    currentSessionId = data.session_id;
    renderMessagesFromHistory(data.messages || []);
    renderSessionsActiveState();
    renderDetails(null);
    sessionId.textContent = currentSessionId || "-";
    if (activePatientId) {
      await selectPatientById(activePatientId, { resetSession: false, clearMessages: false });
    } else {
      clearSelectedPatient();
    }
    setStatus("Sẵn sàng", "ready");
  } catch (error) {
    appendMessage("error", error.message || "Không tải được lịch sử.");
    setStatus("Lỗi", "error");
  }
}

function clearSelectedPatient() {
  selectedPatient = null;
  selectedPatientName.textContent = "Chưa chọn bệnh nhân";
  selectedPatientMeta.textContent = "Phiên này chưa gắn bệnh nhân cụ thể.";
  renderEmptyPatientSections();
}

function renderMessagesFromHistory(items) {
  messages.replaceChildren();
  if (!items.length) {
    messages.append(createHistoryNotice("Phiên này chưa có tin nhắn."));
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

  if (currentSessionId) {
    exportPdfButton.style.display = "inline-block";
    exportCsvButton.style.display = "inline-block";
  } else {
    exportPdfButton.style.display = "none";
    exportCsvButton.style.display = "none";
  }
}

function resetMessages(text) {
  messages.replaceChildren(createHistoryNotice(text));
}

function createHistoryNotice(text) {
  const article = document.createElement("article");
  article.className = "message assistant";
  article.append(createEl("div", "message-label", "Trợ lý"), createEl("p", "", text));
  return article;
}

function appendMessage(role, text, data) {
  const article = document.createElement("article");
  article.className = `message ${role}`;

  const label = createEl(
    "div",
    "message-label",
    role === "user" ? "Bạn" : role === "error" ? "Lỗi" : "Trợ lý"
  );

  const body = role === "assistant"
    ? renderAssistantAnswer(text)
    : createEl("p", "", text || "");

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
  triggerBtn.textContent = "★ Đánh giá";

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
    btn.textContent = "★";
    btn.addEventListener("mouseover", () => {
      starsEl.querySelectorAll(".feedback-btn").forEach((b, i) => b.classList.toggle("hover", i < val));
    });
    btn.addEventListener("mouseout", () => {
      starsEl.querySelectorAll(".feedback-btn").forEach((b, i) => {
        b.classList.remove("hover");
        b.classList.toggle("active", i < pendingRating);
      });
    });
    btn.addEventListener("click", () => {
      pendingRating = val;
      starsEl.querySelectorAll(".feedback-btn").forEach((b, i) => b.classList.toggle("active", i < val));
    });
    starsEl.append(btn);
  });

  const textarea = document.createElement("textarea");
  textarea.placeholder = "Nhận xét (không bắt buộc)...";
  textarea.rows = 2;

  const submitBtn = document.createElement("button");
  submitBtn.type = "button";
  submitBtn.className = "feedback-submit";
  submitBtn.textContent = "Gửi đánh giá";
  submitBtn.addEventListener("click", async () => {
    if (!pendingRating) return;
    await handleFeedback(messageId, pendingRating, textarea.value.trim(), wrapper, starsEl);
  });

  panel.append(starsEl, textarea, submitBtn);

  triggerBtn.addEventListener("click", () => {
    const opening = panel.hidden;
    panel.hidden = !panel.hidden;
    triggerBtn.classList.toggle("open", opening);
    if (opening) textarea.focus();
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
    starsEl.querySelectorAll(".feedback-btn").forEach((btn, i) => {
      btn.classList.toggle("active", i < rating);
      btn.disabled = true;
    });
    wrapper.replaceWith(createEl("span", "feedback-thanks", "★".repeat(rating) + " Cảm ơn bạn đã đánh giá!"));
  } catch (e) {
    console.error("Feedback failed", e);
  }
}

function renderPatientCandidates(data) {
  if (!data?.needs_patient_selection || !Array.isArray(data.patient_candidates)) {
    return null;
  }

  const candidates = data.patient_candidates.filter((item) => item?.id);
  if (!candidates.length) {
    return null;
  }

  const panel = document.createElement("div");
  panel.className = "candidate-panel";
  panel.append(createEl("div", "candidate-title", "Chọn đúng bệnh nhân để tiếp tục"));

  for (const candidate of candidates) {
    const row = document.createElement("div");
    row.className = "candidate-row";

    const info = document.createElement("div");
    info.className = "candidate-info";
    info.append(
      createEl("strong", "", `${candidate.name || "Không rõ tên"} (${candidate.id})`),
      createEl(
        "span",
        "",
        [
          candidate.birth_date ? `Sinh: ${candidate.birth_date}` : null,
          candidate.phone ? `SĐT: ${candidate.phone}` : null,
          candidate.gender ? `Giới tính: ${formatGender(candidate.gender)}` : null,
        ].filter(Boolean).join(" | ")
      )
    );

    const button = createEl("button", "candidate-button", "Chọn");
    button.type = "button";
    button.addEventListener("click", async () => {
      const patientId = normalizePatientId(candidate.id);
      await selectPatientById(patientId, { resetSession: false, clearMessages: false });
      const question = data.pending_question || messageInput.value.trim();
      const label = `Chọn Patient/${patientId} - ${candidate.name || "không rõ tên"}`;
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
    body.append(createEl("p", "", "Không có câu trả lời."));
  }
  return body;
}

function renderDetails(data) {
  sessionId.textContent = data?.session_id || currentSessionId || "-";
  intent.textContent = data?.intent || "-";
  answerSource.textContent = data?.answer_source || "-";
  toolName.textContent = data?.tool_name || "-";
  responseScope.textContent = data?.all_patients
    ? "Tất cả bệnh nhân"
    : data?.patient_id
      ? "Một bệnh nhân"
      : data
        ? "Không gắn bệnh nhân"
        : "-";
  usageBlock.textContent = data?.usage ? JSON.stringify(data.usage, null, 2) : "-";

  evidenceList.replaceChildren();
  const evidence = Array.isArray(data?.evidence) ? data.evidence : [];
  if (!evidence.length) {
    evidenceList.append(createPlaceholder("Chưa có dữ liệu tham chiếu."));
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
      details.append(createEl("summary", "", "Dữ liệu chi tiết"));
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

  costSummary.replaceChildren(createPlaceholder("Đang tải chi phí AI..."));
  const today = todayIsoDate();
  try {
    const [quota, cost] = await Promise.all([
      apiGet("/api/quota/status"),
      apiGet(`/api/usage/cost-summary?from=${today}&to=${today}`),
    ]);
    renderCostSummary(cost, quota);
  } catch (error) {
    costSummary.replaceChildren(createPlaceholder(error.message || "Không tải được chi phí AI.", "error-text"));
  }
}

function renderCostSummary(cost, quota) {
  costSummary.replaceChildren();

  const grid = document.createElement("div");
  grid.className = "cost-grid";
  grid.append(
    costMetric("Token hôm nay", formatInteger(cost?.total_tokens)),
    costMetric("Lượt gọi hôm nay", `${formatInteger(quota?.used_requests)} lượt`),
    costMetric("Chi phí hôm nay", formatUsd(cost?.estimated_cost_usd)),
    costMetric("Chi phí còn lại", formatUsd(quota?.remaining_cost_usd)),
    costMetric("Hạn mức chi phí/ngày", formatUsd(quota?.daily_cost_limit_usd)),
    costMetric("Hạn mức lượt gọi/ngày", `${formatInteger(quota?.daily_request_limit)} lượt`)
  );
  costSummary.append(grid);

  const topModel = Array.isArray(cost?.models) && cost.models.length ? cost.models[0] : null;
  if (topModel) {
    const modelLine = [
      topModel.llm_provider,
      topModel.llm_model,
      `${formatInteger(topModel.request_count)} lượt`,
      formatUsd(topModel.estimated_cost_usd),
    ].filter(Boolean).join(" | ");
    costSummary.append(createEl("p", "cost-model", `Model chính: ${modelLine}`));
  } else {
    costSummary.append(createEl("p", "muted", "Chưa có lượt gọi AI thành công hôm nay."));
  }

  const missingPricing = Array.isArray(cost?.missing_pricing_models) ? cost.missing_pricing_models : [];
  if (missingPricing.length) {
    const models = missingPricing
      .map((item) => `${item.llm_provider || "-"} / ${item.llm_model || "-"}`)
      .join(", ");
    costSummary.append(createEl("p", "cost-warning", `Chưa có bảng giá cho model này: ${models}.`));
  }
}

function costMetric(label, value) {
  const item = document.createElement("div");
  item.className = "cost-metric";
  item.append(createEl("span", "", label), createEl("strong", "", value || "-"));
  return item;
}

function observationValueText(observation) {
  if (Array.isArray(observation.components) && observation.components.length) {
    const components = observation.components
      .map((component) => {
        const label = component.code || component.code_text || "Thành phần";
        return `${label}: ${quantityText(component.value)}`;
      })
      .join("; ");
    return components || "Giá trị: -";
  }
  if (observation.value) {
    return `Giá trị: ${quantityText(observation.value)}`;
  }
  if (observation.value_string) {
    return `Giá trị: ${observation.value_string}`;
  }
  if (observation.value_integer !== null && observation.value_integer !== undefined) {
    return `Giá trị: ${observation.value_integer}`;
  }
  return "Giá trị: -";
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
    return "nữ";
  }
  if (gender === "other") {
    return "khác";
  }
  return "không rõ";
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

function exportSession(format) {
  if (!currentSessionId) return;
  const url = `${apiBaseUrl()}/api/chat/sessions/${currentSessionId}/export?format=${format}`;
  const link = document.createElement("a");
  link.href = url;
  // If authorization is needed, usually the cookie handles it or we'd need to fetch and trigger download
  // For standard browser download with cookies, link.click() works if the API allows GET.
  link.target = "_blank";
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);
}

function exportHistory(from, to, format) {
  if (!from || !to) return;
  const url = `${apiBaseUrl()}/api/chat/export?from=${from}&to=${to}&format=${format}`;
  const link = document.createElement("a");
  link.href = url;
  link.target = "_blank";
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);
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
  try {
    const data = await apiGet("/api/notifications");
    const unreadCount = data.unread_count || 0;

    if (unreadCount > 0) {
      const oldVal = parseInt(notificationBadge.textContent || "0");
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
    console.error("Lỗi khi tải thông báo:", error);
  }
}

function renderNotificationList(items) {
  notificationList.replaceChildren();

  if (!items || items.length === 0) {
    const empty = document.createElement("div");
    empty.className = "popover-empty";
    empty.innerHTML = `
      <span class="popover-empty-icon">🔔</span>
      <span>Không có thông báo nào</span>
    `;
    notificationList.appendChild(empty);
    return;
  }

  items.forEach(item => {
    const div = document.createElement("div");
    const typeClass = `type-${item.type.toLowerCase().replace(/_/g, '-')}`;
    div.className = `notification-item ${item.isRead ? '' : 'unread'} ${typeClass}`;

    const headerDiv = document.createElement("div");
    headerDiv.className = "notification-item-header";

    const titleSpan = document.createElement("span");
    titleSpan.className = "notification-item-title";
    titleSpan.textContent = item.title;

    const timeSpan = document.createElement("span");
    timeSpan.className = "notification-item-time";
    timeSpan.textContent = formatTimeAgo(item.createdAt);

    headerDiv.appendChild(titleSpan);
    headerDiv.appendChild(timeSpan);

    const contentDiv = document.createElement("div");
    contentDiv.className = "notification-item-content";
    contentDiv.textContent = item.content;

    div.appendChild(headerDiv);
    div.appendChild(contentDiv);

    div.addEventListener("click", async (e) => {
      e.stopPropagation();
      if (!item.isRead) {
        try {
          await apiPost(`/api/notifications/${item.id}/read`);
          await loadNotifications();
        } catch (error) {
          console.error("Không thể đánh dấu thông báo đã đọc:", error);
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

    if (isNaN(seconds)) return "";
    if (seconds < 0) return "Vừa xong";

    if (seconds < 60) return "Vừa xong";
    const minutes = Math.floor(seconds / 60);
    if (minutes < 60) return `${minutes} phút trước`;
    const hours = Math.floor(minutes / 60);
    if (hours < 24) return `${hours} giờ trước`;
    const days = Math.floor(hours / 24);
    if (days < 30) return `${days} ngày trước`;

    return date.toLocaleDateString('vi-VN', {
      hour: '2-digit',
      minute: '2-digit',
      day: '2-digit',
      month: '2-digit'
    });
  } catch (e) {
    return "";
  }
}
