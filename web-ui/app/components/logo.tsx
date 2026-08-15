import { cn } from "~/lib/utils";
import type { ComponentPropsWithoutRef } from "react";

export default function Logo({
  className,
  ...props
}: ComponentPropsWithoutRef<"div">) {
  return (
    <div
      className={cn(
        "flex h-9 w-9 items-center justify-center rounded-xl bg-foreground/10 border border-foreground/15 p-1.5 shadow-sm select-none",
        className
      )}
      {...props}
    >
      <svg viewBox="0 0 120 120" className="h-full w-full text-foreground fill-current">
        <rect x="10" y="10" width="100" height="100" fill="none" stroke="currentColor" strokeWidth="8" rx="20" />
        <rect x="30" y="35" width="15" height="50" fill="currentColor" rx="4" />
        <rect x="55" y="35" width="32" height="22" fill="currentColor" rx="4" />
        <rect x="55" y="63" width="32" height="22" fill="currentColor" rx="4" />
      </svg>
    </div>
  );
}
