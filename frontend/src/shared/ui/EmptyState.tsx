import type { ReactNode } from 'react';
import { Inbox } from 'lucide-react';

interface Props {
  title: string;
  description?: string;
  icon?: ReactNode;
  action?: ReactNode;
}

export const EmptyState = ({ title, description, icon, action }: Props) => (
  <div className="flex flex-col items-center justify-center gap-3 py-12 text-center">
    <div className="rounded-full bg-muted p-4 text-muted-foreground">
      {icon ?? <Inbox className="h-6 w-6" aria-hidden />}
    </div>
    <div>
      <h3 className="font-semibold">{title}</h3>
      {description && <p className="mt-1 text-sm text-muted-foreground">{description}</p>}
    </div>
    {action}
  </div>
);
