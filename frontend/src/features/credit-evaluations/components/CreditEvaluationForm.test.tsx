import { vi } from 'vitest';
import { http, HttpResponse, delay } from 'msw';
import { renderWithProviders, screen, waitFor, userEvent } from '@/test/test-utils';
import { server } from '@/test/mocks/server';
import { errorHandlers } from '@/test/mocks/handlers';
import { env } from '@/shared/config/env';
import { CreditEvaluationForm } from './CreditEvaluationForm';

vi.mock('@/shared/lib/keycloak', () => import('@/test/__mocks__/keycloak'));

const fillValid = async (user: ReturnType<typeof userEvent.setup>) => {
  await user.type(screen.getByLabelText(/cédula/i), '1713175071');
  await user.type(screen.getByLabelText(/monto/i), '5000');
  await user.clear(screen.getByLabelText(/plazo/i));
  await user.type(screen.getByLabelText(/plazo/i), '3');
  await user.type(screen.getByLabelText(/salario/i), '2000');
};

describe('CreditEvaluationForm', () => {
  it('valida en cliente con Zod (cédula inválida)', async () => {
    renderWithProviders(<CreditEvaluationForm />);
    const user = userEvent.setup();
    await user.type(screen.getByLabelText(/cédula/i), '123');
    await user.tab();
    expect(await screen.findByText(/exactamente 10 dígitos/i)).toBeInTheDocument();
  });

  it('envía y notifica éxito', async () => {
    const onSuccess = vi.fn();
    renderWithProviders(<CreditEvaluationForm onSuccess={onSuccess} />);
    const user = userEvent.setup();
    await fillValid(user);
    await user.click(screen.getByRole('button', { name: /evaluar crédito/i }));
    await waitFor(() =>
      expect(onSuccess).toHaveBeenCalledWith(
        expect.objectContaining({ estadoFinal: 'APROBADO' }),
      ),
    );
  });

  it('muestra error del backend (422) vía toast', async () => {
    server.use(...errorHandlers);
    const onSuccess = vi.fn();
    renderWithProviders(<CreditEvaluationForm onSuccess={onSuccess} />);
    const user = userEvent.setup();
    await fillValid(user);
    await user.click(screen.getByRole('button', { name: /evaluar crédito/i }));
    await waitFor(() => expect(onSuccess).not.toHaveBeenCalled());
  });

  it('deshabilita el botón mientras envía', async () => {
    server.use(
      http.post(`${env.VITE_API_BASE_URL}/v1/credit-evaluations`, async () => {
        await delay(100);
        return HttpResponse.json({}, { status: 201 });
      }),
    );
    renderWithProviders(<CreditEvaluationForm />);
    const user = userEvent.setup();
    await fillValid(user);
    const btn = screen.getByRole('button', { name: /evaluar crédito/i });
    await user.click(btn);
    await waitFor(() => expect(btn).toBeDisabled());
  });
});
