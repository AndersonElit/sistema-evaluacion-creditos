import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/shared/ui/Card';
import { EvaluationStatusBadge } from './EvaluationStatusBadge';
import { formatCurrency, formatDateTime } from '@/shared/lib/format';
import type { EvaluacionCredito } from '../types';

const Stat = ({ label, value }: { label: string; value: string }) => (
  <div>
    <dt className="text-xs text-muted-foreground">{label}</dt>
    <dd className="font-semibold">{value}</dd>
  </div>
);

export const EvaluationResultCard = ({ evaluacion }: { evaluacion: EvaluacionCredito }) => (
  <Card>
    <CardHeader className="flex-row items-start justify-between gap-4">
      <div>
        <CardTitle>Resultado de la evaluación</CardTitle>
        <CardDescription>Cédula {evaluacion.cedula}</CardDescription>
      </div>
      <EvaluationStatusBadge estado={evaluacion.estadoFinal} />
    </CardHeader>
    <CardContent>
      <dl className="grid grid-cols-2 gap-4 sm:grid-cols-4">
        <Stat label="Monto" value={formatCurrency(evaluacion.montoSolicitado)} />
        <Stat label="Plazo" value={`${evaluacion.plazoAnios} años`} />
        <Stat label="Score riesgo" value={String(evaluacion.scoreRiesgo)} />
        <Stat label="Deuda mensual" value={formatCurrency(evaluacion.deudaMensualTotal)} />
        <Stat label="Fecha" value={formatDateTime(evaluacion.fechaEvaluacion)} />
      </dl>
    </CardContent>
  </Card>
);
