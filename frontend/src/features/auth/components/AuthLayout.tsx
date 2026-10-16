import { motion } from "framer-motion";
import { ArrowLeft } from "lucide-react";
import { cn } from "@/shared/lib/cn";

interface AuthLayoutProps {
  icon: React.ReactNode;
  title: string;
  subtitle: string;
  children: React.ReactNode;
  wide?: boolean;
}

/** Centered column shared by the sign-in, registration and account pages. */
export function AuthLayout({ icon, title, subtitle, children, wide = false }: AuthLayoutProps) {
  return (
    <main className="grid min-h-screen place-items-center bg-background px-5 py-10 text-foreground">
      <motion.section
        initial={{ opacity: 0, y: 16 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.4 }}
        className={cn("w-full", wide ? "max-w-2xl" : "max-w-md")}
      >
        <div className="mb-8 flex flex-col items-center text-center">
          <span className="grid h-14 w-14 place-items-center rounded-2xl gradient-surface text-white shadow-lift">{icon}</span>
          <h1 className="mt-5 font-display text-3xl">{title}</h1>
          <p className="mt-2 text-sm leading-6 text-muted-foreground">{subtitle}</p>
        </div>
        {children}
      </motion.section>
    </main>
  );
}

export function AuthCard({ children, className }: { children: React.ReactNode; className?: string }) {
  return <div className={cn("rounded-3xl border border-border bg-white p-6 shadow-lift md:p-8", className)}>{children}</div>;
}

export function BackButton({ onClick }: { onClick: () => void }) {
  return (
    <button
      type="button"
      className="focus-ring mt-6 flex w-full items-center justify-center gap-2 rounded-lg py-1 text-sm font-medium text-accent hover:underline"
      onClick={onClick}
    >
      <ArrowLeft className="h-4 w-4" />
      Hủy
    </button>
  );
}
