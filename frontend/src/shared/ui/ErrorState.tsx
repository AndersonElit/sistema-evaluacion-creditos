import { AlertTriangle } from 'lucide-react';
import { Button } from './Button';

interface Props {
  title?: string;
  message: string;
  onRetry?: () => void;
}

export const ErrorState = ({ title = 'Algo salió mal', message, onRetry }: Props) => (
  <div
    role="alert"
    className="flex flex-col items-center gap-3 rounded-lg border border-danger/30 bg-danger/5 p-6 text-center"
  >
    <AlertTriangle className="h-8 w-8 text-danger" aria-hidden />
    <div>
      <h3 className="font-semibold text-danger">{title}</h3>
      <p className="mt-1 text-sm text-muted-foreground">{message}</p>
    </div>
    {onRetry && (
      <Button variant="secondary" size="sm" onClick={onRetry}>
        Reintentar
      </Button>
    )}
  </div>
);
