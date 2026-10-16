import { useMutation } from "@tanstack/react-query";
import { errorMessage } from "@/shared/api/client";
import { Button } from "@/shared/ui/Button";
import { Modal } from "@/shared/ui/Modal";
import { Notice } from "@/shared/ui/Notice";
import { UNTITLED_SESSION } from "../constants";
import type { ChatSession } from "../types";

interface DeleteSessionModalProps {
  /** Null while closed. */
  session: ChatSession | null;
  onConfirm: (session: ChatSession) => Promise<void>;
  onClose: () => void;
}

export function DeleteSessionModal({ session, onConfirm, onClose }: DeleteSessionModalProps) {
  const remove = useMutation({ mutationFn: onConfirm, onSuccess: onClose });

  function close() {
    if (!remove.isPending) {
      remove.reset();
      onClose();
    }
  }

  return (
    <Modal open={session !== null} title="Xóa hội thoại" description="Hành động này không thể hoàn tác." size="sm" onClose={close}>
      {remove.isError ? <Notice className="mb-4">{errorMessage(remove.error, "Không thể xóa hội thoại.")}</Notice> : null}
      <p className="text-sm text-foreground">
        Bạn có chắc muốn xóa hội thoại <strong>“{session?.title || UNTITLED_SESSION}”</strong>? Toàn bộ tin nhắn trong hội thoại sẽ bị
        xóa vĩnh viễn.
      </p>
      <div className="mt-6 flex justify-end gap-3">
        <Button type="button" variant="secondary" onClick={close} disabled={remove.isPending}>
          Hủy
        </Button>
        <Button
          type="button"
          variant="danger"
          onClick={() => session && remove.mutate(session)}
          disabled={remove.isPending}
        >
          {remove.isPending ? "Đang xóa..." : "Xóa"}
        </Button>
      </div>
    </Modal>
  );
}
