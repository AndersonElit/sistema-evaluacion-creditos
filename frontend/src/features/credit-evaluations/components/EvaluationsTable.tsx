import { DataTable, type Column } from '@/shared/ui/DataTable';
import { EvaluationStatusBadge } from './EvaluationStatusBadge';
import { EmptyState } from '@/shared/ui/EmptyState';
import { formatCurrency, formatDateTime } from '@/shared/lib/format';
import type { EvaluacionCredito } from '../types';

const columns: Column<EvaluacionCredito>[] = [
  { key: 'cedula', header: 'Cédula', cell: (e) => <span className="font-mono">{e.cedula}</span> },
  { key: 'monto', header: 'Monto', align: 'right', cell: (e) => formatCurrency(e.montoSolicitado) },
  { key: 'plazo', header: 'Plazo', align: 'right', cell: (e) => `${e.plazoAnios} años` },
  { key: 'score', header: 'Score', align: 'right', cell: (e) => e.scoreRiesgo },
  { key: 'estado', header: 'Estado', cell: (e) => <EvaluationStatusBadge estado={e.estadoFinal} /> },
  { key: 'fecha', header: 'Fecha', cell: (e) => formatDateTime(e.fechaEvaluacion) },
];

export const EvaluationsTable = ({ data }: { data: EvaluacionCredito[] }) => (
  <DataTable
    caption="Listado de evaluaciones de crédito"
    columns={columns}
    data={data}
    rowKey={(e) => e.id}
    emptyState={
      <EmptyState
        title="Aún no hay evaluaciones"
        description="Cuando crees tu primera evaluación aparecerá aquí."
      />
    }
  />
);
