import { forwardRef } from "react";
import { cva, type VariantProps } from "class-variance-authority";
import { cn } from "../../lib/cn";

const buttonVariants = cva(
  "focus-ring inline-flex min-h-11 items-center justify-center gap-2 rounded-xl px-4 py-2 text-sm font-semibold transition-all duration-200 disabled:opacity-50",
  {
    variants: {
      variant: {
        primary:
          "gradient-surface text-white shadow-accent hover:-translate-y-0.5 hover:brightness-110 active:translate-y-0",
        secondary:
          "border border-border bg-white text-foreground shadow-sm hover:-translate-y-0.5 hover:border-accent/40 hover:shadow-card",
        ghost: "bg-transparent text-muted-foreground hover:bg-muted hover:text-foreground",
        danger: "bg-danger text-white shadow-sm hover:-translate-y-0.5 hover:brightness-110",
      },
      size: {
        sm: "min-h-9 rounded-lg px-3 text-xs",
        md: "min-h-11 px-4 text-sm",
        lg: "min-h-12 px-5 text-base",
        icon: "h-11 w-11 px-0",
      },
    },
    defaultVariants: {
      variant: "secondary",
      size: "md",
    },
  },
);

export interface ButtonProps
  extends React.ButtonHTMLAttributes<HTMLButtonElement>,
    VariantProps<typeof buttonVariants> {}

export const Button = forwardRef<HTMLButtonElement, ButtonProps>(
  ({ className, variant, size, ...props }, ref) => (
    <button ref={ref} className={cn(buttonVariants({ variant, size }), className)} {...props} />
  ),
);

Button.displayName = "Button";
