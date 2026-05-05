# Paso 10 — Frontend React: Keycloak JS, Formulario y Lista de Evaluaciones

## Objetivo
Crear la aplicación React con TypeScript que integra Keycloak OIDC/PKCE,
el formulario para crear evaluaciones y la lista de resultados.
El Frontend consume `ms-credit-evaluation` con el JWT de Keycloak.

## Prerrequisitos
- Paso 02 completado (Keycloak con realm banco y client `credit-evaluation-spa`)
- Paso 07 completado (ms-credit-evaluation corriendo con CORS configurado)
- Node.js 20+, npm

## 1. Crear la aplicación

```bash
cd frontend
npm create vite@latest . -- --template react-ts
npm install
npm install keycloak-js axios
```

## 2. Configuración de Keycloak — `src/keycloak.ts`

```typescript
import Keycloak from 'keycloak-js';

const keycloak = new Keycloak({
  url: 'http://localhost:9000',
  realm: 'banco',
  clientId: 'credit-evaluation-spa',
});

export default keycloak;
```

## 3. Bootstrap con login requerido — `src/main.tsx`

```typescript
import React from 'react';
import ReactDOM from 'react-dom/client';
import App from './App';
import keycloak from './keycloak';

keycloak.init({
  onLoad: 'login-required',
  pkceMethod: 'S256',
  checkLoginIframe: false,
}).then((authenticated) => {
  if (!authenticated) {
    keycloak.login();
    return;
  }
  ReactDOM.createRoot(document.getElementById('root')!).render(
    <React.StrictMode>
      <App keycloak={keycloak} />
    </React.StrictMode>
  );
});

// Renovación automática del token (si quedan <30s para expirar)
keycloak.onTokenExpired = () => {
  keycloak.updateToken(30).catch(() => keycloak.logout());
};
```

## 4. Axios con interceptor de JWT — `src/api/axiosInstance.ts`

```typescript
import axios from 'axios';
import keycloak from '../keycloak';

const api = axios.create({
  baseURL: 'http://localhost:8080',
  headers: { 'Content-Type': 'application/json' },
});

api.interceptors.request.use(async (config) => {
  await keycloak.updateToken(30);
  if (keycloak.token) {
    config.headers['Authorization'] = `Bearer ${keycloak.token}`;
  }
  return config;
});

export default api;
```

## 5. Tipos TypeScript — `src/types/index.ts`

```typescript
export interface SolicitudCredito {
  cedula: string;
  montoSolicitado: number;
  plazoAnios: number;
  salario: number;
  destinatarioEmail?: string;
}

export interface EvaluacionCredito {
  id: string;
  cedula: string;
  montoSolicitado: number;
  plazoAnios: number;
  salario: number;
  scoreRiesgo: number;
  deudaMensualTotal: number;
  estadoFinal: 'APROBADO' | 'RECHAZADO' | 'PENDIENTE';
  fechaEvaluacion: string;
  evaluadoPorId: string;
}
```

## 6. Servicio API — `src/api/creditApi.ts`

```typescript
import api from './axiosInstance';
import { SolicitudCredito, EvaluacionCredito } from '../types';

export const crearEvaluacion = async (solicitud: SolicitudCredito): Promise<EvaluacionCredito> => {
  const { data } = await api.post<EvaluacionCredito>('/v1/credit-evaluations', solicitud);
  return data;
};

export const listarEvaluaciones = async (page = 0, size = 20): Promise<EvaluacionCredito[]> => {
  const { data } = await api.get<EvaluacionCredito[]>(
    `/v1/credit-evaluations?page=${page}&size=${size}`
  );
  return data;
};

export const obtenerEvaluacion = async (id: string): Promise<EvaluacionCredito> => {
  const { data } = await api.get<EvaluacionCredito>(`/v1/credit-evaluations/${id}`);
  return data;
};
```

## 7. Formulario de Evaluación — `src/components/CreditForm.tsx`

```typescript
import { useState } from 'react';
import { crearEvaluacion } from '../api/creditApi';
import { EvaluacionCredito, SolicitudCredito } from '../types';

interface Props {
  onEvaluacionCreada: (ev: EvaluacionCredito) => void;
}

export default function CreditForm({ onEvaluacionCreada }: Props) {
  const [form, setForm] = useState<SolicitudCredito>({
    cedula: '', montoSolicitado: 0, plazoAnios: 1, salario: 0,
  });
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setLoading(true);
    setError(null);
    try {
      const resultado = await crearEvaluacion(form);
      onEvaluacionCreada(resultado);
    } catch (err: any) {
      setError(err.response?.data?.error ?? 'Error al procesar la solicitud');
    } finally {
      setLoading(false);
    }
  };

  return (
    <form onSubmit={handleSubmit} style={{ maxWidth: 400 }}>
      <h2>Nueva Evaluación de Crédito</h2>

      <label>Cédula (10 dígitos)</label>
      <input type="text" maxLength={10} pattern="\d{10}"
        value={form.cedula}
        onChange={e => setForm({ ...form, cedula: e.target.value })}
        required />

      <label>Monto Solicitado (USD)</label>
      <input type="number" min={1} step={0.01}
        value={form.montoSolicitado || ''}
        onChange={e => setForm({ ...form, montoSolicitado: Number(e.target.value) })}
        required />

      <label>Plazo (años, 1–30)</label>
      <input type="number" min={1} max={30}
        value={form.plazoAnios}
        onChange={e => setForm({ ...form, plazoAnios: Number(e.target.value) })}
        required />

      <label>Salario Mensual (USD)</label>
      <input type="number" min={1} step={0.01}
        value={form.salario || ''}
        onChange={e => setForm({ ...form, salario: Number(e.target.value) })}
        required />

      <label>Email del Solicitante</label>
      <input type="email"
        value={form.destinatarioEmail ?? ''}
        onChange={e => setForm({ ...form, destinatarioEmail: e.target.value })} />

      {error && <p style={{ color: 'red' }}>{error}</p>}

      <button type="submit" disabled={loading}>
        {loading ? 'Evaluando...' : 'Evaluar Crédito'}
      </button>
    </form>
  );
}
```

## 8. Lista de Evaluaciones — `src/components/EvaluacionesList.tsx`

```typescript
import { EvaluacionCredito } from '../types';

interface Props {
  evaluaciones: EvaluacionCredito[];
}

const estadoColor = (estado: string) =>
  estado === 'APROBADO' ? '#27ae60' : estado === 'RECHAZADO' ? '#e74c3c' : '#f39c12';

export default function EvaluacionesList({ evaluaciones }: Props) {
  if (evaluaciones.length === 0) return <p>No hay evaluaciones registradas.</p>;

  return (
    <table style={{ width: '100%', borderCollapse: 'collapse' }}>
      <thead>
        <tr>
          <th>Cédula</th><th>Monto</th><th>Plazo</th>
          <th>Score</th><th>Estado</th><th>Fecha</th>
        </tr>
      </thead>
      <tbody>
        {evaluaciones.map(ev => (
          <tr key={ev.id}>
            <td>{ev.cedula}</td>
            <td>${ev.montoSolicitado.toFixed(2)}</td>
            <td>{ev.plazoAnios} años</td>
            <td>{ev.scoreRiesgo}</td>
            <td style={{ color: estadoColor(ev.estadoFinal), fontWeight: 'bold' }}>
              {ev.estadoFinal}
            </td>
            <td>{new Date(ev.fechaEvaluacion).toLocaleString()}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
```

## 9. App principal — `src/App.tsx`

```typescript
import { useState, useEffect } from 'react';
import Keycloak from 'keycloak-js';
import CreditForm from './components/CreditForm';
import EvaluacionesList from './components/EvaluacionesList';
import { listarEvaluaciones } from './api/creditApi';
import { EvaluacionCredito } from './types';

interface Props {
  keycloak: Keycloak;
}

export default function App({ keycloak }: Props) {
  const [evaluaciones, setEvaluaciones] = useState<EvaluacionCredito[]>([]);
  const [vistaActiva, setVistaActiva] = useState<'formulario' | 'lista'>('formulario');
  const roles: string[] = (keycloak.tokenParsed as any)?.groups ?? [];
  const puedeEvaluar = roles.includes('ANALYST') || roles.includes('ADMIN');

  useEffect(() => {
    listarEvaluaciones().then(setEvaluaciones).catch(console.error);
  }, []);

  const handleNuevaEvaluacion = (ev: EvaluacionCredito) => {
    setEvaluaciones(prev => [ev, ...prev]);
    setVistaActiva('lista');
  };

  return (
    <div style={{ padding: 20, fontFamily: 'Arial, sans-serif' }}>
      <header style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 20 }}>
        <h1>Sistema de Evaluación de Créditos</h1>
        <div>
          <span style={{ marginRight: 12 }}>
            {keycloak.tokenParsed?.['email']} ({roles.join(', ')})
          </span>
          <button onClick={() => keycloak.logout()}>Cerrar Sesión</button>
        </div>
      </header>

      <nav style={{ marginBottom: 20 }}>
        {puedeEvaluar && (
          <button onClick={() => setVistaActiva('formulario')}
            style={{ marginRight: 8 }}>Nueva Evaluación</button>
        )}
        <button onClick={() => setVistaActiva('lista')}>Ver Evaluaciones</button>
      </nav>

      {vistaActiva === 'formulario' && puedeEvaluar && (
        <CreditForm onEvaluacionCreada={handleNuevaEvaluacion} />
      )}

      {vistaActiva === 'lista' && (
        <EvaluacionesList evaluaciones={evaluaciones} />
      )}
    </div>
  );
}
```

## 10. Levantar el Frontend

```bash
cd frontend
npm run dev
# Disponible en http://localhost:3000
```

> Vite usa el puerto 5173 por defecto. Cambiar en `vite.config.ts`:
> ```ts
> server: { port: 3000 }
> ```
> Y agregar `http://localhost:3000/*` en Keycloak Valid Redirect URIs (paso 02).

## Verificación

```bash
# 1. Abrir http://localhost:3000
# → Redirige automáticamente al login de Keycloak

# 2. Iniciar sesión como analyst@banco.com / Analyst123!
# → Redirige de vuelta con JWT

# 3. Llenar formulario de evaluación
#    Cédula: 1713175071 | Monto: 5000 | Plazo: 3 | Salario: 2000
# → Resultado: APROBADO o RECHAZADO en ~2s

# 4. Ver la evaluación en la lista

# 5. Cerrar sesión y volver a entrar como viewer@banco.com / Viewer123!
# → Solo visible la lista, sin botón "Nueva Evaluación"
```

---

## Pruebas del Frontend

### Dependencias de test

```bash
npm install --save-dev \
  vitest \
  @vitest/ui \
  jsdom \
  @testing-library/react \
  @testing-library/user-event \
  @testing-library/jest-dom \
  msw
```

### `vite.config.ts` — configurar Vitest

```typescript
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  server: { port: 3000 },
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
  },
});
```

### `src/test/setup.ts`

```typescript
import '@testing-library/jest-dom';
```

### Mock de Keycloak — `src/test/__mocks__/keycloak.ts`

```typescript
const keycloak = {
  token: 'fake-jwt-token',
  tokenParsed: {
    email: 'analyst@banco.com',
    groups: ['ANALYST'],
    sub: '550e8400-e29b-41d4-a716-446655440000',
  },
  authenticated: true,
  init: vi.fn().mockResolvedValue(true),
  login: vi.fn(),
  logout: vi.fn(),
  updateToken: vi.fn().mockResolvedValue(true),
  onTokenExpired: undefined,
};

export default keycloak;
```

### Mock del API — `src/test/mocks/handlers.ts` (MSW)

```typescript
import { http, HttpResponse } from 'msw';
import { EvaluacionCredito } from '../../types';

const evaluacionBase: EvaluacionCredito = {
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

export const handlers = [
  http.get('http://localhost:8080/v1/credit-evaluations', () => {
    return HttpResponse.json([evaluacionBase]);
  }),

  http.post('http://localhost:8080/v1/credit-evaluations', () => {
    return HttpResponse.json(evaluacionBase, { status: 201 });
  }),
];

export const handlersError = [
  http.post('http://localhost:8080/v1/credit-evaluations', () => {
    return HttpResponse.json(
      { status: 422, error: 'cédula inválida' },
      { status: 422 }
    );
  }),
];
```

### `src/test/mocks/server.ts`

```typescript
import { setupServer } from 'msw/node';
import { handlers } from './handlers';

export const server = setupServer(...handlers);
```

### `src/test/setup.ts` — agregar MSW lifecycle

```typescript
import '@testing-library/jest-dom';
import { server } from './mocks/server';

beforeAll(() => server.listen());
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
```

---

### Pruebas Unitarias — `EvaluacionesList.test.tsx`

Ubicación: `src/components/`

```typescript
import { render, screen } from '@testing-library/react';
import EvaluacionesList from './EvaluacionesList';
import { EvaluacionCredito } from '../types';

const evaluaciones: EvaluacionCredito[] = [
  {
    id: 'id-001', cedula: '1713175071', montoSolicitado: 5000,
    plazoAnios: 3, salario: 2000, scoreRiesgo: 85, deudaMensualTotal: 200,
    estadoFinal: 'APROBADO', fechaEvaluacion: '2026-05-05T14:30:00Z',
    evaluadoPorId: 'user-001',
  },
  {
    id: 'id-002', cedula: '0912345678', montoSolicitado: 3000,
    plazoAnios: 2, salario: 1500, scoreRiesgo: 45, deudaMensualTotal: 100,
    estadoFinal: 'RECHAZADO', fechaEvaluacion: '2026-05-05T15:00:00Z',
    evaluadoPorId: 'user-001',
  },
];

describe('EvaluacionesList', () => {
  test('muestra mensaje cuando no hay evaluaciones', () => {
    render(<EvaluacionesList evaluaciones={[]} />);
    expect(screen.getByText(/no hay evaluaciones/i)).toBeInTheDocument();
  });

  test('renderiza todas las evaluaciones recibidas', () => {
    render(<EvaluacionesList evaluaciones={evaluaciones} />);
    expect(screen.getByText('1713175071')).toBeInTheDocument();
    expect(screen.getByText('0912345678')).toBeInTheDocument();
  });

  test('muestra APROBADO y RECHAZADO con estilos distintos', () => {
    render(<EvaluacionesList evaluaciones={evaluaciones} />);
    expect(screen.getByText('APROBADO')).toBeInTheDocument();
    expect(screen.getByText('RECHAZADO')).toBeInTheDocument();
  });

  test('muestra el monto formateado', () => {
    render(<EvaluacionesList evaluaciones={evaluaciones} />);
    expect(screen.getByText('$5000.00')).toBeInTheDocument();
  });
});
```

### Pruebas Unitarias — `CreditForm.test.tsx`

```typescript
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { server } from '../test/mocks/server';
import { handlersError } from '../test/mocks/handlers';
import CreditForm from './CreditForm';

describe('CreditForm', () => {
  test('renderiza todos los campos del formulario', () => {
    render(<CreditForm onEvaluacionCreada={vi.fn()} />);
    expect(screen.getByLabelText(/cédula/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/monto/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/plazo/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/salario/i)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /evaluar/i })).toBeInTheDocument();
  });

  test('submitting el formulario llama a onEvaluacionCreada con la respuesta', async () => {
    const onCreada = vi.fn();
    render(<CreditForm onEvaluacionCreada={onCreada} />);
    const user = userEvent.setup();

    await user.type(screen.getByLabelText(/cédula/i), '1713175071');
    await user.type(screen.getByLabelText(/monto/i), '5000');
    await user.clear(screen.getByLabelText(/plazo/i));
    await user.type(screen.getByLabelText(/plazo/i), '3');
    await user.type(screen.getByLabelText(/salario/i), '2000');
    await user.click(screen.getByRole('button', { name: /evaluar/i }));

    await waitFor(() => {
      expect(onCreada).toHaveBeenCalledWith(
        expect.objectContaining({ estadoFinal: 'APROBADO' })
      );
    });
  });

  test('muestra error cuando el API retorna 422', async () => {
    server.use(...handlersError);
    render(<CreditForm onEvaluacionCreada={vi.fn()} />);
    const user = userEvent.setup();

    await user.type(screen.getByLabelText(/cédula/i), '1234567890');
    await user.type(screen.getByLabelText(/monto/i), '5000');
    await user.type(screen.getByLabelText(/salario/i), '2000');
    await user.click(screen.getByRole('button', { name: /evaluar/i }));

    await waitFor(() => {
      expect(screen.getByText(/cédula inválida/i)).toBeInTheDocument();
    });
  });

  test('botón queda deshabilitado mientras se evalúa', async () => {
    render(<CreditForm onEvaluacionCreada={vi.fn()} />);
    const user = userEvent.setup();

    await user.type(screen.getByLabelText(/cédula/i), '1713175071');
    await user.type(screen.getByLabelText(/monto/i), '5000');
    await user.type(screen.getByLabelText(/salario/i), '2000');
    await user.click(screen.getByRole('button', { name: /evaluar/i }));

    expect(screen.getByRole('button', { name: /evaluando/i })).toBeDisabled();
  });
});
```

### Prueba de Integración — `App.test.tsx`

```typescript
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import App from './App';
import keycloak from './keycloak';

// Usar el mock de keycloak definido en __mocks__
vi.mock('./keycloak');

describe('App — integración', () => {
  test('muestra el email del usuario autenticado', () => {
    render(<App keycloak={keycloak as any} />);
    expect(screen.getByText(/analyst@banco\.com/i)).toBeInTheDocument();
  });

  test('ANALYST ve el botón Nueva Evaluación', () => {
    render(<App keycloak={keycloak as any} />);
    expect(screen.getByRole('button', { name: /nueva evaluación/i }))
        .toBeInTheDocument();
  });

  test('VIEWER no ve el botón Nueva Evaluación', () => {
    (keycloak.tokenParsed as any).groups = ['VIEWER'];
    render(<App keycloak={keycloak as any} />);
    expect(screen.queryByRole('button', { name: /nueva evaluación/i }))
        .not.toBeInTheDocument();
    // Restaurar
    (keycloak.tokenParsed as any).groups = ['ANALYST'];
  });

  test('lista de evaluaciones se carga al montar', async () => {
    render(<App keycloak={keycloak as any} />);
    const user = userEvent.setup();

    await user.click(screen.getByRole('button', { name: /ver evaluaciones/i }));

    await waitFor(() => {
      expect(screen.getByText('1713175071')).toBeInTheDocument();
    });
  });

  test('logout llama a keycloak.logout', async () => {
    render(<App keycloak={keycloak as any} />);
    const user = userEvent.setup();

    await user.click(screen.getByRole('button', { name: /cerrar sesión/i }));

    expect(keycloak.logout).toHaveBeenCalledTimes(1);
  });
});
```

### Ejecutar tests del Frontend

```bash
cd frontend

# Todos los tests
npx vitest run

# Watch mode (durante desarrollo)
npx vitest

# Con UI interactiva
npx vitest --ui

# Cobertura
npx vitest run --coverage
```

---

## Estado esperado al finalizar
- [ ] Frontend arranca en http://localhost:3000
- [ ] Login redirige a Keycloak (Authorization Code + PKCE)
- [ ] JWT con claim `groups` visible en el token
- [ ] Formulario envía evaluación y muestra resultado
- [ ] Lista muestra evaluaciones existentes
- [ ] VIEWER no ve el formulario de nueva evaluación
- [ ] Token se renueva automáticamente antes de expirar
- [ ] Logout funciona correctamente
- [ ] `npx vitest run` pasa: unitarios de componentes + integración de App
