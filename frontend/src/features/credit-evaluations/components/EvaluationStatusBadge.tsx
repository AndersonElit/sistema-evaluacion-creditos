import { Badge } from '@/shared/ui/Badge';
import type { EstadoEvaluacion } from '../types';

const map: Record<EstadoEvaluacion, { tone: 'success' | 'danger' | 'warning'; label: string }> = {
  APROBADO: { tone: 'success', label: 'Aprobado' },
  RECHAZADO: { tone: 'danger', label: 'Rechazado' },
  PENDIENTE: { tone: 'warning', label: 'Pendiente' },
};

export const EvaluationStatusBadge = ({ estado }: { estado: EstadoEvaluacion }) => {
  const { tone, label } = map[estado];
  return <Badge tone={tone}>{label}</Badge>;
};
