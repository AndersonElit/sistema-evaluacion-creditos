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
npm create vite@latest frontend -- --template react-ts
cd frontend
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

## Estado esperado al finalizar
- [ ] Frontend arranca en http://localhost:3000
- [ ] Login redirige a Keycloak (Authorization Code + PKCE)
- [ ] JWT con claim `groups` visible en el token
- [ ] Formulario envía evaluación y muestra resultado
- [ ] Lista muestra evaluaciones existentes
- [ ] VIEWER no ve el formulario de nueva evaluación
- [ ] Token se renueva automáticamente antes de expirar
- [ ] Logout funciona correctamente
