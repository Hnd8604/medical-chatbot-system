import { X } from "lucide-react";
import { Button } from "./Button";
import { cn } from "../../lib/cn";

interface ModalProps {
  open: boolean;
  title: string;
  description?: string;
  children: React.ReactNode;
  onClose: () => void;
  size?: "sm" | "md" | "lg";
}

const sizes = {
  sm: "max-w-md",
  md: "max-w-xl",
  lg: "max-w-4xl",
};

export function Modal({ open, title, description, children, onClose, size = "md" }: ModalProps) {
  if (!open) {
    return null;
  }

  return (
    <div className="fixed inset-0 z-50 grid place-items-center bg-foreground/50 p-4 backdrop-blur-sm">
      <section className={cn("w-full rounded-2xl bg-white shadow-lift", sizes[size])} role="dialog" aria-modal="true">
        <header className="flex items-start justify-between gap-4 border-b border-border p-5">
          <div>
            <h2 className="font-display text-2xl text-foreground">{title}</h2>
            {description ? <p className="mt-1 text-sm text-muted-foreground">{description}</p> : null}
          </div>
          <Button type="button" variant="ghost" size="icon" onClick={onClose} aria-label="Đóng">
            <X className="h-5 w-5" />
          </Button>
        </header>
        <div className="max-h-[75vh] overflow-auto p-5">{children}</div>
      </section>
    </div>
  );
}
