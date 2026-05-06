import { render, screen } from '@testing-library/react';
import { EvaluationStatusBadge } from './EvaluationStatusBadge';

describe('EvaluationStatusBadge', () => {
  test.each([
    ['APROBADO', 'Aprobado'],
    ['RECHAZADO', 'Rechazado'],
    ['PENDIENTE', 'Pendiente'],
  ] as const)('renderiza %s con label %s', (estado, label) => {
    render(<EvaluationStatusBadge estado={estado} />);
    expect(screen.getByText(label)).toBeInTheDocument();
  });
});
