import { Badge } from "./Badge";

interface PageHeaderProps {
  eyebrow: string;
  title: string;
  description?: string;
  actions?: React.ReactNode;
}

export function PageHeader({ eyebrow, title, description, actions }: PageHeaderProps) {
  return (
    <section className="py-8 sm:py-10">
      <div className="flex flex-wrap items-end justify-between gap-5">
        <div className="max-w-3xl">
          <Badge tone="blue" pulse>
            {eyebrow}
          </Badge>
          <h1 className="mt-4 font-display text-4xl leading-tight text-foreground sm:text-5xl">{title}</h1>
          {description ? <p className="mt-3 max-w-2xl text-base leading-7 text-muted-foreground">{description}</p> : null}
        </div>
        {actions ? <div className="flex flex-wrap gap-3">{actions}</div> : null}
      </div>
    </section>
  );
}
