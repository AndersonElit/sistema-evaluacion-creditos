import { vi } from 'vitest';
import { renderWithProviders, screen, waitFor } from '@/test/test-utils';
import { EvaluationsListPage } from './EvaluationsListPage';

vi.mock('@/shared/lib/keycloak', () => import('@/test/__mocks__/keycloak'));

describe('EvaluationsListPage', () => {
  it('renderiza skeleton y luego datos', async () => {
    renderWithProviders(<EvaluationsListPage />);
    await waitFor(() => expect(screen.getByText('1713175071')).toBeInTheDocument());
  });
});
