import { forwardRef } from "react";
import { Eye, EyeOff } from "lucide-react";
import { inputClass } from "./Field";

interface PasswordInputProps extends Omit<React.InputHTMLAttributes<HTMLInputElement>, "type"> {
  visible: boolean;
  /** Renders the show/hide toggle; forms with several password fields put it on the first one only. */
  onToggleVisible?: () => void;
}

export const PasswordInput = forwardRef<HTMLInputElement, PasswordInputProps>(
  ({ visible, onToggleVisible, className, ...props }, ref) => {
    if (!onToggleVisible) {
      return <input ref={ref} className={inputClass(className)} type={visible ? "text" : "password"} {...props} />;
    }

    return (
      <div className="relative">
        <input ref={ref} className={inputClass(`pr-12 ${className ?? ""}`)} type={visible ? "text" : "password"} {...props} />
        <button
          type="button"
          className="focus-ring absolute right-2 top-1/2 grid h-9 w-9 -translate-y-1/2 place-items-center rounded-lg text-muted-foreground hover:bg-muted hover:text-foreground"
          aria-label={visible ? "Ẩn mật khẩu" : "Hiện mật khẩu"}
          onClick={onToggleVisible}
        >
          {visible ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
        </button>
      </div>
    );
  },
);

PasswordInput.displayName = "PasswordInput";
