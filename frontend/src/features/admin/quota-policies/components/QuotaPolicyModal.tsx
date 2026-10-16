import { FormEvent, useEffect, useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { errorMessage } from "@/shared/api/client";
import { Button } from "@/shared/ui/Button";
import { Field, inputClass } from "@/shared/ui/Field";
import { Modal } from "@/shared/ui/Modal";
import { Notice } from "@/shared/ui/Notice";
import { Spinner } from "@/shared/ui/Spinner";
import { quotaPolicyApi, quotaPolicyKeys } from "../api";
import type { QuotaPolicy, QuotaPolicyRequest } from "../types";

function numberOrNull(value: string): number | null {
  if (!value.trim()) {
    return null;
  }
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : null;
}

interface FormValues {
  name: string;
  requestLimit: string;
  tokenLimit: string;
  costLimit: string;
  rateLimit: string;
}

function initialValues(policy: QuotaPolicy | null): FormValues {
  return {
    name: policy?.name ?? "",
    requestLimit: policy ? String(policy.dailyRequestLimit) : "",
    tokenLimit: policy ? String(policy.dailyTokenLimit) : "",
    costLimit: policy ? String(policy.dailyCostLimitUsd) : "",
    rateLimit: policy?.rateLimitPerMinute != null ? String(policy.rateLimitPerMinute) : "",
  };
}

/** Validates the form; returns the request body or an error message. */
function toRequest(values: FormValues): QuotaPolicyRequest | string {
  const requests = numberOrNull(values.requestLimit);
  const tokens = numberOrNull(values.tokenLimit);
  const cost = numberOrNull(values.costLimit);
  if (!values.name.trim()) {
    return "Vui lòng nhập tên chính sách.";
  }
  if (requests == null || requests <= 0 || tokens == null || tokens <= 0 || cost == null || cost <= 0) {
    return "Giới hạn request, token và chi phí phải là số dương.";
  }
  return {
    name: values.name.trim(),
    dailyRequestLimit: requests,
    dailyTokenLimit: tokens,
    dailyCostLimitUsd: cost,
    rateLimitPerMinute: numberOrNull(values.rateLimit),
  };
}

interface QuotaPolicyModalProps {
  open: boolean;
  /** Null when creating a new policy. */
  policy: QuotaPolicy | null;
  onClose: () => void;
}

export function QuotaPolicyModal({ open, policy, onClose }: QuotaPolicyModalProps) {
  const queryClient = useQueryClient();
  const [values, setValues] = useState(() => initialValues(policy));
  const [validationError, setValidationError] = useState<string | null>(null);

  const save = useMutation({
    mutationFn: (body: QuotaPolicyRequest) => (policy ? quotaPolicyApi.update(policy.id, body) : quotaPolicyApi.create(body)),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: quotaPolicyKeys.list() });
      onClose();
    },
  });
  const { reset } = save;

  useEffect(() => {
    if (open) {
      setValues(initialValues(policy));
      setValidationError(null);
      reset();
    }
  }, [open, policy, reset]);

  function update(key: keyof FormValues, value: string) {
    setValues((current) => ({ ...current, [key]: value }));
  }

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const request = toRequest(values);
    if (typeof request === "string") {
      setValidationError(request);
      return;
    }
    setValidationError(null);
    save.mutate(request);
  }

  const error = validationError ?? (save.isError ? errorMessage(save.error, "Không thể lưu chính sách quota.") : null);

  return (
    <Modal
      open={open}
      title={policy ? "Sửa chính sách quota" : "Thêm chính sách quota"}
      description="Giới hạn request, token và chi phí theo ngày áp dụng cho người dùng."
      onClose={onClose}
    >
      <form className="grid gap-4" onSubmit={handleSubmit}>
        {error ? <Notice>{error}</Notice> : null}
        <Field label="Tên chính sách">
          <input className={inputClass()} value={values.name} maxLength={100} onChange={(event) => update("name", event.target.value)} />
        </Field>
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="Giới hạn request/ngày">
            <input
              className={inputClass()}
              type="number"
              min={1}
              value={values.requestLimit}
              onChange={(event) => update("requestLimit", event.target.value)}
            />
          </Field>
          <Field label="Giới hạn token/ngày">
            <input
              className={inputClass()}
              type="number"
              min={1}
              value={values.tokenLimit}
              onChange={(event) => update("tokenLimit", event.target.value)}
            />
          </Field>
          <Field label="Giới hạn chi phí/ngày (USD)">
            <input
              className={inputClass()}
              type="number"
              min={0}
              step="0.0001"
              value={values.costLimit}
              onChange={(event) => update("costLimit", event.target.value)}
            />
          </Field>
          <Field label="Rate limit/phút" hint="Để trống nếu không giới hạn.">
            <input
              className={inputClass()}
              type="number"
              min={1}
              value={values.rateLimit}
              onChange={(event) => update("rateLimit", event.target.value)}
            />
          </Field>
        </div>
        <div className="flex justify-end gap-3">
          <Button type="button" variant="ghost" onClick={onClose} disabled={save.isPending}>
            Hủy
          </Button>
          <Button type="submit" variant="primary" disabled={save.isPending}>
            {save.isPending ? <Spinner /> : null}
            {policy ? "Lưu thay đổi" : "Tạo mới"}
          </Button>
        </div>
      </form>
    </Modal>
  );
}
