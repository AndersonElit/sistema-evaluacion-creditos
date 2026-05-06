import type { ReactNode } from 'react';
import * as RadixLabel from '@radix-ui/react-label';
import { cn } from '@/shared/lib/cn';

interface Props {
  label: string;
  htmlFor: string;
  error?: string | undefined;
  hint?: string | undefined;
  required?: boolean | undefined;
  children: ReactNode;
  className?: string | undefined;
}

export const FormField = ({
  label,
  htmlFor,
  error,
  hint,
  required,
  children,
  className,
}: Props) => {
  return (
    <div className={cn('flex flex-col gap-1.5', className)}>
      <RadixLabel.Root htmlFor={htmlFor} className="text-sm font-medium">
        {label}
        {required && (
          <span className="ml-0.5 text-danger" aria-hidden>
            *
          </span>
        )}
      </RadixLabel.Root>
      {children}
      {hint && !error && <p className="text-xs text-muted-foreground">{hint}</p>}
      {error && (
        <p role="alert" className="text-xs text-danger">
          {error}
        </p>
      )}
    </div>
  );
};
