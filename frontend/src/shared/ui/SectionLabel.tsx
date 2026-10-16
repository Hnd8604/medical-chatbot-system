export function SectionLabel({ children }: { children: React.ReactNode }) {
  return (
    <div className="inline-flex items-center gap-3 rounded-full border border-accent/30 bg-accent/5 px-4 py-2">
      <span className="h-2 w-2 animate-pulse rounded-full bg-accent" />
      <span className="font-mono text-xs uppercase tracking-[0.15em] text-accent">{children}</span>
    </div>
  );
}
