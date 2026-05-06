import { http, HttpResponse } from 'msw';
import { env } from '@/shared/config/env';
import type { EvaluacionCredito } from '@/features/credit-evaluations/types';

const evaluacion: EvaluacionCredito = {
  id: '550e8400-e29b-41d4-a716-446655440001',
  cedula: '1713175071',
  montoSolicitado: 5000,
  plazoAnios: 3,
  salario: 2000,
  scoreRiesgo: 85,
  deudaMensualTotal: 200,
  estadoFinal: 'APROBADO',
  fechaEvaluacion: '2026-05-05T14:30:00Z',
  evaluadoPorId: '550e8400-e29b-41d4-a716-446655440000',
};

const url = (p: string) => `${env.VITE_API_BASE_URL}${p}`;

export const handlers = [
  http.get(url('/v1/credit-evaluations'), () => HttpResponse.json([evaluacion])),
  http.post(url('/v1/credit-evaluations'), () => HttpResponse.json(evaluacion, { status: 201 })),
];

export const errorHandlers = [
  http.post(url('/v1/credit-evaluations'), () =>
    HttpResponse.json({ status: 422, error: 'cédula inválida' }, { status: 422 }),
  ),
];
