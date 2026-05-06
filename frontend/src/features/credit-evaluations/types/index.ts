export type EstadoEvaluacion = 'APROBADO' | 'RECHAZADO' | 'PENDIENTE';

export interface EvaluacionCredito {
  id: string;
  cedula: string;
  montoSolicitado: number;
  plazoAnios: number;
  salario: number;
  scoreRiesgo: number;
  deudaMensualTotal: number;
  estadoFinal: EstadoEvaluacion;
  fechaEvaluacion: string;
  evaluadoPorId: string;
}
