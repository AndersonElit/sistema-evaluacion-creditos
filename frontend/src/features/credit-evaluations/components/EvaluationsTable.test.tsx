import { render, screen } from '@testing-library/react';
import { EvaluationsTable } from './EvaluationsTable';
import type { EvaluacionCredito } from '../types';

const sample: EvaluacionCredito[] = [
  {
    id: '1',
    cedula: '1713175071',
    montoSolicitado: 5000,
    plazoAnios: 3,
    salario: 2000,
    scoreRiesgo: 85,
    deudaMensualTotal: 200,
    estadoFinal: 'APROBADO',
    fechaEvaluacion: '2026-05-05T14:30:00Z',
    evaluadoPorId: 'u1',
  },
];

describe('EvaluationsTable', () => {
  it('muestra empty state cuando no hay datos', () => {
    render(<EvaluationsTable data={[]} />);
    expect(screen.getByText(/aún no hay evaluaciones/i)).toBeInTheDocument();
  });

  it('renderiza filas con datos formateados y badge', () => {
    render(<EvaluationsTable data={sample} />);
    expect(screen.getByText('1713175071')).toBeInTheDocument();
    expect(screen.getByText(/\$\s*5[.,]?000[.,]00/)).toBeInTheDocument();
    expect(screen.getByText('Aprobado')).toBeInTheDocument();
  });

  it('expone tabla accesible con caption', () => {
    render(<EvaluationsTable data={sample} />);
    expect(
      screen.getByRole('table', { name: /listado de evaluaciones/i }),
    ).toBeInTheDocument();
  });
});
