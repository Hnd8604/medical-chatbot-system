import { useEffect, useState } from "react";
import { Gauge, Pencil, Plus, RefreshCw, Trash2 } from "lucide-react";
import { apiDelete, apiGet, apiPost, apiPut } from "../services/api";
import type { QuotaPolicyItem, QuotaPolicyUpsert } from "../lib/types";
import { formatDateTime, formatNumber, formatUsd } from "../lib/formatters";
import { DashboardLayout } from "../components/dashboard/DashboardLayout";
import { EmptyState } from "../components/dashboard/EmptyState";
import { Button } from "../components/ui/Button";
import { Field, inputClass } from "../components/ui/Field";
import { Modal } from "../components/ui/Modal";
import { Spinner } from "../components/ui/Spinner";

const QUOTA_PATH = "/api/admin/quota-policies";

function numberOrNull(value: string): number | null {
  const trimmed = value.trim();
  if (!trimmed) {
    return null;
  }
  const parsed = Number(trimmed);
  return Number.isFinite(parsed) ? parsed : null;
}

interface QuotaPolicyModalProps {
  open: boolean;
  editing: QuotaPolicyItem | null;
  onClose: () => void;
  onSaved: () => void;
}

function QuotaPolicyModal({ open, editing, onClose, onSaved }: QuotaPolicyModalProps) {
  const [name, setName] = useState("");
  const [requestLimit, setRequestLimit] = useState("");
  const [tokenLimit, setTokenLimit] = useState("");
  const [costLimit, setCostLimit] = useState("");
  const [rateLimit, setRateLimit] = useState("");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!open) {
      return;
    }
    setError(null);
    setName(editing?.name ?? "");
    setRequestLimit(editing ? String(editing.daily_request_limit) : "");
    setTokenLimit(editing ? String(editing.daily_token_limit) : "");
    setCostLimit(editing ? String(editing.daily_cost_limit_usd) : "");
    setRateLimit(editing?.rate_limit_per_minute != null ? String(editing.rate_limit_per_minute) : "");
  }, [open, editing]);

  async function handleSubmit(event: React.FormEvent) {
    event.preventDefault();
    const requests = numberOrNull(requestLimit);
    const tokens = numberOrNull(tokenLimit);
    const cost = numberOrNull(costLimit);

    if (!name.trim()) {
      setError("Vui lòng nhập tên chính sách.");
      return;
    }
    if (requests == null || requests <= 0 || tokens == null || tokens <= 0 || cost == null || cost <= 0) {
      setError("Giới hạn request, token và chi phí phải là số dương.");
      return;
    }

    const payload: QuotaPolicyUpsert = {
      name: name.trim(),
      daily_request_limit: requests,
      daily_token_limit: tokens,
      daily_cost_limit_usd: cost,
      rate_limit_per_minute: numberOrNull(rateLimit),
    };

    setSaving(true);
    setError(null);
    try {
      if (editing) {
        await apiPut(`${QUOTA_PATH}/${encodeURIComponent(editing.id)}`, payload);
      } else {
        await apiPost(QUOTA_PATH, payload);
      }
      onSaved();
      onClose();
    } catch (saveError) {
      setError(saveError instanceof Error ? saveError.message : "Không thể lưu chính sách quota.");
    } finally {
      setSaving(false);
    }
  }

  return (
    <Modal
      open={open}
      title={editing ? "Sửa chính sách quota" : "Thêm chính sách quota"}
      description="Giới hạn request, token và chi phí theo ngày áp dụng cho người dùng."
      onClose={onClose}
    >
      <form className="grid gap-4" onSubmit={handleSubmit}>
        {error ? (
          <div className="rounded-lg border border-danger/25 bg-danger/5 p-3 text-sm text-danger">{error}</div>
        ) : null}
        <Field label="Tên chính sách">
          <input className={inputClass()} value={name} maxLength={100} onChange={(event) => setName(event.target.value)} />
        </Field>
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="Giới hạn request/ngày">
            <input className={inputClass()} type="number" min={1} value={requestLimit} onChange={(event) => setRequestLimit(event.target.value)} />
          </Field>
          <Field label="Giới hạn token/ngày">
            <input className={inputClass()} type="number" min={1} value={tokenLimit} onChange={(event) => setTokenLimit(event.target.value)} />
          </Field>
          <Field label="Giới hạn chi phí/ngày (USD)">
            <input className={inputClass()} type="number" min={0} step="0.0001" value={costLimit} onChange={(event) => setCostLimit(event.target.value)} />
          </Field>
          <Field label="Rate limit/phút" hint="Để trống nếu không giới hạn.">
            <input className={inputClass()} type="number" min={1} value={rateLimit} onChange={(event) => setRateLimit(event.target.value)} />
          </Field>
        </div>
        <div className="flex justify-end gap-3">
          <Button type="button" variant="ghost" onClick={onClose} disabled={saving}>
            Hủy
          </Button>
          <Button type="submit" variant="primary" disabled={saving}>
            {saving ? <Spinner /> : null}
            {editing ? "Lưu thay đổi" : "Tạo mới"}
          </Button>
        </div>
      </form>
    </Modal>
  );
}

export function AdminConfigPage() {
  const [policies, setPolicies] = useState<QuotaPolicyItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const [policyModalOpen, setPolicyModalOpen] = useState(false);
  const [editingPolicy, setEditingPolicy] = useState<QuotaPolicyItem | null>(null);
  const [deletingId, setDeletingId] = useState<string | null>(null);

  async function loadAll() {
    setLoading(true);
    setError(null);
    try {
      const policyData = await apiGet<QuotaPolicyItem[]>(QUOTA_PATH);
      setPolicies(policyData || []);
    } catch (loadError) {
      setError(loadError instanceof Error ? loadError.message : "Không thể tải cấu hình hệ thống.");
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void loadAll();
  }, []);

  async function deletePolicy(item: QuotaPolicyItem) {
    if (!window.confirm(`Xóa chính sách quota "${item.name}"?`)) {
      return;
    }
    setDeletingId(item.id);
    setError(null);
    try {
      await apiDelete(`${QUOTA_PATH}/${encodeURIComponent(item.id)}`);
      await loadAll();
    } catch (deleteError) {
      setError(deleteError instanceof Error ? deleteError.message : "Không thể xóa chính sách quota.");
    } finally {
      setDeletingId(null);
    }
  }

  return (
    <DashboardLayout
      eyebrow="Admin Config"
      title="Cấu hình hệ thống"
      actions={
        <Button type="button" variant="secondary" onClick={() => void loadAll()} disabled={loading}>
          {loading ? <Spinner /> : <RefreshCw className="h-4 w-4" />}
          Làm mới
        </Button>
      }
    >
      {error ? (
        <div className="mb-6 rounded-lg border border-danger/25 bg-danger/5 p-4 text-sm text-danger">{error}</div>
      ) : null}

      {loading && policies.length === 0 ? (
        <div className="grid min-h-[18rem] place-items-center rounded-lg border border-border bg-white">
          <div className="flex items-center gap-3 text-muted-foreground">
            <Spinner className="text-accent" />
            Đang tải cấu hình...
          </div>
        </div>
      ) : (
        <div className="grid gap-8">
          <section className="rounded-lg border border-border bg-white">
            <div className="flex flex-wrap items-center justify-between gap-3 border-b border-border px-5 py-4">
              <div className="flex items-center gap-3">
                <Gauge className="h-5 w-5 text-accent" />
                <div>
                  <h2 className="text-lg font-semibold text-foreground">Chính sách quota</h2>
                  <p className="text-sm text-muted-foreground">Giới hạn request, token và chi phí theo ngày.</p>
                </div>
              </div>
              <Button
                type="button"
                variant="primary"
                onClick={() => {
                  setEditingPolicy(null);
                  setPolicyModalOpen(true);
                }}
              >
                <Plus className="h-4 w-4" />
                Thêm chính sách
              </Button>
            </div>

            {policies.length === 0 ? (
              <div className="p-5">
                <EmptyState title="Chưa có chính sách quota nào." description="Tạo chính sách đầu tiên để giới hạn sử dụng." />
              </div>
            ) : (
              <div className="divide-y divide-border">
                {policies.map((item) => (
                  <article key={item.id} className="flex flex-wrap items-center justify-between gap-4 px-5 py-4">
                    <div className="min-w-0">
                      <p className="font-semibold text-foreground">{item.name}</p>
                      <p className="mt-1 text-sm text-muted-foreground">
                        {formatNumber(item.daily_request_limit)} request · {formatNumber(item.daily_token_limit)} token ·{" "}
                        {formatUsd(item.daily_cost_limit_usd)} / ngày
                        {item.rate_limit_per_minute != null ? ` · ${formatNumber(item.rate_limit_per_minute)} req/phút` : ""}
                      </p>
                      <p className="mt-1 text-xs text-muted-foreground">Tạo lúc {formatDateTime(item.created_at)}</p>
                    </div>
                    <div className="flex items-center gap-2">
                      <Button
                        type="button"
                        variant="secondary"
                        size="sm"
                        onClick={() => {
                          setEditingPolicy(item);
                          setPolicyModalOpen(true);
                        }}
                      >
                        <Pencil className="h-3.5 w-3.5" />
                        Sửa
                      </Button>
                      <Button type="button" variant="danger" size="sm" disabled={deletingId === item.id} onClick={() => void deletePolicy(item)}>
                        {deletingId === item.id ? <Spinner /> : <Trash2 className="h-3.5 w-3.5" />}
                        Xóa
                      </Button>
                    </div>
                  </article>
                ))}
              </div>
            )}
          </section>
        </div>
      )}

      <QuotaPolicyModal
        open={policyModalOpen}
        editing={editingPolicy}
        onClose={() => setPolicyModalOpen(false)}
        onSaved={() => void loadAll()}
      />
    </DashboardLayout>
  );
}
