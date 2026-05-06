# Paso 10 — Frontend React: Arquitectura por Features con Design System

## Objetivo
Construir una SPA en **React 18 + TypeScript** con arquitectura **feature-based**, design system propio,
gestión de estado servidor con **TanStack Query**, validación con **React Hook Form + Zod**, routing con
**React Router v6**, autenticación **Keycloak OIDC + PKCE** y testing con **Vitest + Testing Library + MSW**.

El frontend consume `ms-credit-evaluation` enviando JWT en cada request.

## Prerrequisitos
- Paso 02 completado (Keycloak con realm `banco` y client `credit-evaluation-spa`)
- Paso 07 completado (`ms-credit-evaluation` corriendo con CORS configurado)
- Node.js 20+, npm 10+

---

## 1. Decisiones técnicas y stack

| Capa | Tecnología | Razón |
|------|-----------|-------|
| Build | Vite 5 | HMR rápido, build optimizado |
| Lenguaje | TypeScript 5 (strict) | Seguridad de tipos en compile-time |
| Estilos | TailwindCSS 3 + CSS variables | Tokens de diseño + utility-first |
| Primitives UI | Radix UI | Accesibilidad WAI-ARIA out of the box |
| Iconos | lucide-react | Tree-shakeable, consistente |
| Forms | React Hook Form + Zod | Validación tipada, mínimas re-renders |
| Server state | TanStack Query v5 | Cache, retries, invalidación, optimistic UI |
| Routing | React Router v6 | Nested routes, loaders |
| Auth | keycloak-js | OIDC + PKCE oficial |
| HTTP | axios + interceptor | Inyección automática de JWT |
| Notificaciones | sonner | Toast accesible, ligero |
| Testing | Vitest + Testing Library + MSW | Tests unitarios e integración |

### Principios de diseño aplicados
1. **Feature-based architecture** — el código se organiza por dominio, no por tipo técnico.
2. **Atomic design** en `shared/ui/` — primitives reusables (Button, Input, Badge…).
3. **Separation of concerns** — UI tonta vs. hooks de negocio vs. capa de API.
4. **Composition over configuration** — componentes pequeños y componibles.
5. **Accesibilidad first** — labels asociados, foco visible, roles ARIA, contraste AA.
6. **Resiliencia UI** — todo componente que carga datos maneja `loading | empty | error | success`.
7. **Type-safe boundaries** — Zod en runtime, TS en compile-time, tipos derivados del schema.

---

## 2. Estructura del proyecto

```
frontend/
├── public/
├── src/
│   ├── app/                          # Composición raíz: providers + router
│   │   ├── App.tsx
│   │   ├── providers/
│   │   │   ├── AppProviders.tsx
│   │   │   ├── QueryProvider.tsx
│   │   │   └── AuthProvider.tsx
│   │   └── router/
│   │       ├── AppRouter.tsx
│   │       └── ProtectedRoute.tsx
│   │
│   ├── features/                     # Módulos por dominio
│   │   ├── auth/
│   │   │   ├── hooks/useAuth.ts
│   │   │   ├── components/UserMenu.tsx
│   │   │   └── guards/RoleGuard.tsx
│   │   └── credit-evaluations/
│   │       ├── api/creditApi.ts
│   │       ├── api/queryKeys.ts
│   │       ├── hooks/useEvaluations.ts
│   │       ├── hooks/useCreateEvaluation.ts
│   │       ├── components/
│   │       │   ├── CreditEvaluationForm.tsx
│   │       │   ├── EvaluationsTable.tsx
│   │       │   ├── EvaluationStatusBadge.tsx
│   │       │   └── EvaluationResultCard.tsx
│   │       ├── schemas/creditSchema.ts
│   │       └── types/index.ts
│   │
│   ├── shared/                       # Reutilizable cross-feature
│   │   ├── ui/                       # Componentes atómicos
│   │   │   ├── Button.tsx
│   │   │   ├── Input.tsx
│   │   │   ├── FormField.tsx
│   │   │   ├── Card.tsx
│   │   │   ├── Badge.tsx
│   │   │   ├── Skeleton.tsx
│   │   │   ├── EmptyState.tsx
│   │   │   ├── ErrorState.tsx
│   │   │   └── DataTable.tsx
│   │   ├── layout/
│   │   │   ├── AppShell.tsx
│   │   │   └── PageHeader.tsx
│   │   ├── lib/
│   │   │   ├── keycloak.ts
│   │   │   ├── apiClient.ts
│   │   │   ├── cn.ts
│   │   │   └── format.ts
│   │   └── config/env.ts
│   │
│   ├── pages/                        # Componentes de ruta
│   │   ├── DashboardPage.tsx
│   │   ├── NewEvaluationPage.tsx
│   │   └── EvaluationsListPage.tsx
│   │
│   ├── styles/globals.css
│   ├── test/
│   │   ├── setup.ts
│   │   ├── test-utils.tsx
│   │   └── mocks/{handlers,server}.ts
│   └── main.tsx
│
├── index.html
├── package.json
├── tailwind.config.ts
├── tsconfig.json
└── vite.config.ts
```

---

## 3. Crear el proyecto e instalar dependencias

```bash
cd frontend
npm create vite@latest . -- --template react-ts
npm install

# Runtime
npm install \
  keycloak-js \
  axios \
  @tanstack/react-query \
  react-router-dom \
  react-hook-form \
  @hookform/resolvers \
  zod \
  @radix-ui/react-dialog \
  @radix-ui/react-dropdown-menu \
  @radix-ui/react-label \
  @radix-ui/react-slot \
  lucide-react \
  sonner \
  clsx \
  tailwind-merge

# Dev
npm install -D \
  tailwindcss postcss autoprefixer \
  @tanstack/react-query-devtools \
  vitest @vitest/ui jsdom \
  @testing-library/react \
  @testing-library/user-event \
  @testing-library/jest-dom \
  msw

npx tailwindcss init -p
```

---

## 4. Configuración base

### `src/shared/config/env.ts`
Centraliza variables de entorno con validación al boot.

```typescript
import { z } from 'zod';

const envSchema = z.object({
  VITE_API_BASE_URL: z.string().url().default('http://localhost:8080'),
  VITE_KEYCLOAK_URL: z.string().url().default('http://localhost:9000'),
  VITE_KEYCLOAK_REALM: z.string().default('banco'),
  VITE_KEYCLOAK_CLIENT_ID: z.string().default('credit-evaluation-spa'),
});

export const env = envSchema.parse(import.meta.env);
```

### `vite.config.ts`

```typescript
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'path';

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: { '@': path.resolve(__dirname, './src') },
  },
  server: { port: 3000, strictPort: true },
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    css: false,
  },
});
```

### `tsconfig.json` — strict mode + alias

```json
{
  "compilerOptions": {
    "target": "ES2022",
    "module": "ESNext",
    "moduleResolution": "bundler",
    "jsx": "react-jsx",
    "strict": true,
    "noUncheckedIndexedAccess": true,
    "noImplicitOverride": true,
    "exactOptionalPropertyTypes": true,
    "baseUrl": ".",
    "paths": { "@/*": ["src/*"] },
    "types": ["vitest/globals", "@testing-library/jest-dom"]
  },
  "include": ["src"]
}
```

### `tailwind.config.ts` — design tokens

```typescript
import type { Config } from 'tailwindcss';

export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        background: 'hsl(var(--background))',
        foreground: 'hsl(var(--foreground))',
        muted: { DEFAULT: 'hsl(var(--muted))', foreground: 'hsl(var(--muted-foreground))' },
        primary: { DEFAULT: 'hsl(var(--primary))', foreground: 'hsl(var(--primary-foreground))' },
        success: { DEFAULT: 'hsl(var(--success))', foreground: 'hsl(var(--success-foreground))' },
        danger:  { DEFAULT: 'hsl(var(--danger))',  foreground: 'hsl(var(--danger-foreground))' },
        warning: { DEFAULT: 'hsl(var(--warning))', foreground: 'hsl(var(--warning-foreground))' },
        border: 'hsl(var(--border))',
        ring: 'hsl(var(--ring))',
        card: 'hsl(var(--card))',
      },
      borderRadius: { lg: '12px', md: '8px', sm: '4px' },
      fontFamily: { sans: ['Inter', 'system-ui', 'sans-serif'] },
    },
  },
} satisfies Config;
```

### `src/styles/globals.css` — tokens semánticos light/dark

```css
@tailwind base;
@tailwind components;
@tailwind utilities;

@layer base {
  :root {
    --background: 0 0% 100%;
    --foreground: 222 47% 11%;
    --muted: 210 40% 96%;
    --muted-foreground: 215 16% 47%;
    --primary: 221 83% 53%;
    --primary-foreground: 0 0% 100%;
    --success: 142 71% 45%;
    --success-foreground: 0 0% 100%;
    --danger: 0 72% 51%;
    --danger-foreground: 0 0% 100%;
    --warning: 38 92% 50%;
    --warning-foreground: 0 0% 100%;
    --border: 214 32% 91%;
    --ring: 221 83% 53%;
    --card: 0 0% 100%;
  }

  .dark {
    --background: 222 47% 11%;
    --foreground: 210 40% 98%;
    --muted: 217 33% 17%;
    --muted-foreground: 215 20% 65%;
    --border: 217 33% 22%;
    --card: 222 47% 14%;
  }

  *  { @apply border-border; }
  body {
    @apply bg-background text-foreground antialiased;
    font-feature-settings: 'cv11', 'ss01';
  }

  /* Foco visible accesible */
  *:focus-visible {
    @apply outline-none ring-2 ring-ring ring-offset-2 ring-offset-background;
  }
}
```

### `src/shared/lib/cn.ts` — merge de clases con precedencia Tailwind

```typescript
import { clsx, type ClassValue } from 'clsx';
import { twMerge } from 'tailwind-merge';

export const cn = (...inputs: ClassValue[]) => twMerge(clsx(inputs));
```

### `src/shared/lib/format.ts` — helpers de formato (i18n-ready)

```typescript
const currencyFmt = new Intl.NumberFormat('es-EC', {
  style: 'currency', currency: 'USD', maximumFractionDigits: 2,
});
const dateFmt = new Intl.DateTimeFormat('es-EC', {
  dateStyle: 'medium', timeStyle: 'short',
});

export const formatCurrency = (n: number) => currencyFmt.format(n);
export const formatDateTime = (iso: string) => dateFmt.format(new Date(iso));
```

---

## 5. Capa de autenticación

### `src/shared/lib/keycloak.ts`

```typescript
import Keycloak from 'keycloak-js';
import { env } from '@/shared/config/env';

export const keycloak = new Keycloak({
  url: env.VITE_KEYCLOAK_URL,
  realm: env.VITE_KEYCLOAK_REALM,
  clientId: env.VITE_KEYCLOAK_CLIENT_ID,
});

export const initKeycloak = () =>
  keycloak.init({
    onLoad: 'login-required',
    pkceMethod: 'S256',
    checkLoginIframe: false,
  });
```

### `src/shared/lib/apiClient.ts` — axios + interceptor JWT + manejo de errores

```typescript
import axios, { AxiosError } from 'axios';
import { keycloak } from './keycloak';
import { env } from '@/shared/config/env';

export const apiClient = axios.create({
  baseURL: env.VITE_API_BASE_URL,
  headers: { 'Content-Type': 'application/json' },
  timeout: 15_000,
});

apiClient.interceptors.request.use(async (config) => {
  try {
    await keycloak.updateToken(30);
    if (keycloak.token) config.headers.Authorization = `Bearer ${keycloak.token}`;
  } catch {
    keycloak.login();
  }
  return config;
});

apiClient.interceptors.response.use(
  (res) => res,
  (error: AxiosError) => {
    if (error.response?.status === 401) keycloak.login();
    return Promise.reject(error);
  },
);

export interface ApiError {
  status: number;
  error: string;
  message?: string;
}

export const extractApiError = (err: unknown): string => {
  if (err instanceof AxiosError) {
    const data = err.response?.data as Partial<ApiError> | undefined;
    return data?.error ?? data?.message ?? err.message;
  }
  return 'Error desconocido';
};
```

### `src/features/auth/hooks/useAuth.ts`

```typescript
import { useMemo } from 'react';
import { keycloak } from '@/shared/lib/keycloak';

export type Role = 'ANALYST' | 'ADMIN' | 'VIEWER';

export interface AuthUser {
  id: string;
  email: string;
  name: string;
  roles: Role[];
}

export const useAuth = () => {
  const user = useMemo<AuthUser | null>(() => {
    const t = keycloak.tokenParsed as
      | { sub?: string; email?: string; name?: string; preferred_username?: string; groups?: string[] }
      | undefined;
    if (!t?.sub) return null;
    return {
      id: t.sub,
      email: t.email ?? '',
      name: t.name ?? t.preferred_username ?? t.email ?? '',
      roles: (t.groups ?? []) as Role[],
    };
  }, []);

  const hasRole = (...roles: Role[]) => roles.some((r) => user?.roles.includes(r));

  return {
    user,
    hasRole,
    canEvaluate: hasRole('ANALYST', 'ADMIN'),
    logout: () => keycloak.logout({ redirectUri: window.location.origin }),
  };
};
```

### `src/features/auth/guards/RoleGuard.tsx`

```typescript
import type { ReactNode } from 'react';
import { useAuth, type Role } from '../hooks/useAuth';

interface Props {
  roles: Role[];
  fallback?: ReactNode;
  children: ReactNode;
}

export const RoleGuard = ({ roles, fallback = null, children }: Props) => {
  const { hasRole } = useAuth();
  return hasRole(...roles) ? <>{children}</> : <>{fallback}</>;
};
```

---

## 6. Sistema de diseño — componentes atómicos

### `src/shared/ui/Button.tsx`

```typescript
import { forwardRef, type ButtonHTMLAttributes } from 'react';
import { Slot } from '@radix-ui/react-slot';
import { Loader2 } from 'lucide-react';
import { cn } from '@/shared/lib/cn';

type Variant = 'primary' | 'secondary' | 'ghost' | 'danger';
type Size = 'sm' | 'md' | 'lg';

const variants: Record<Variant, string> = {
  primary: 'bg-primary text-primary-foreground hover:bg-primary/90',
  secondary: 'bg-muted text-foreground hover:bg-muted/80',
  ghost: 'hover:bg-muted text-foreground',
  danger: 'bg-danger text-danger-foreground hover:bg-danger/90',
};

const sizes: Record<Size, string> = {
  sm: 'h-8 px-3 text-sm',
  md: 'h-10 px-4 text-sm',
  lg: 'h-12 px-6 text-base',
};

interface Props extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: Variant;
  size?: Size;
  loading?: boolean;
  asChild?: boolean;
}

export const Button = forwardRef<HTMLButtonElement, Props>(
  ({ className, variant = 'primary', size = 'md', loading, disabled, asChild, children, ...props }, ref) => {
    const Comp = asChild ? Slot : 'button';
    return (
      <Comp
        ref={ref}
        disabled={disabled || loading}
        aria-busy={loading || undefined}
        className={cn(
          'inline-flex items-center justify-center gap-2 rounded-md font-medium transition-colors',
          'disabled:pointer-events-none disabled:opacity-50',
          variants[variant], sizes[size], className,
        )}
        {...props}
      >
        {loading && <Loader2 className="h-4 w-4 animate-spin" aria-hidden />}
        {children}
      </Comp>
    );
  },
);
Button.displayName = 'Button';
```

### `src/shared/ui/Input.tsx`

```typescript
import { forwardRef, type InputHTMLAttributes } from 'react';
import { cn } from '@/shared/lib/cn';

interface Props extends InputHTMLAttributes<HTMLInputElement> {
  invalid?: boolean;
}

export const Input = forwardRef<HTMLInputElement, Props>(
  ({ className, invalid, ...props }, ref) => (
    <input
      ref={ref}
      aria-invalid={invalid || undefined}
      className={cn(
        'flex h-10 w-full rounded-md border border-border bg-background px-3 py-2 text-sm',
        'placeholder:text-muted-foreground',
        'disabled:cursor-not-allowed disabled:opacity-50',
        invalid && 'border-danger focus-visible:ring-danger',
        className,
      )}
      {...props}
    />
  ),
);
Input.displayName = 'Input';
```

### `src/shared/ui/FormField.tsx` — label + control + error + hint

```typescript
import type { ReactNode } from 'react';
import * as RadixLabel from '@radix-ui/react-label';
import { cn } from '@/shared/lib/cn';

interface Props {
  label: string;
  htmlFor: string;
  error?: string;
  hint?: string;
  required?: boolean;
  children: ReactNode;
  className?: string;
}

export const FormField = ({ label, htmlFor, error, hint, required, children, className }: Props) => {
  const errorId = `${htmlFor}-error`;
  const hintId = `${htmlFor}-hint`;
  return (
    <div className={cn('flex flex-col gap-1.5', className)}>
      <RadixLabel.Root htmlFor={htmlFor} className="text-sm font-medium">
        {label}
        {required && <span className="ml-0.5 text-danger" aria-hidden>*</span>}
      </RadixLabel.Root>
      {children}
      {hint && !error && <p id={hintId} className="text-xs text-muted-foreground">{hint}</p>}
      {error && <p id={errorId} role="alert" className="text-xs text-danger">{error}</p>}
    </div>
  );
};
```

### `src/shared/ui/Card.tsx`

```typescript
import { forwardRef, type HTMLAttributes } from 'react';
import { cn } from '@/shared/lib/cn';

export const Card = forwardRef<HTMLDivElement, HTMLAttributes<HTMLDivElement>>(
  ({ className, ...props }, ref) => (
    <div ref={ref} className={cn('rounded-lg border bg-card shadow-sm', className)} {...props} />
  ),
);
Card.displayName = 'Card';

export const CardHeader = ({ className, ...props }: HTMLAttributes<HTMLDivElement>) => (
  <div className={cn('flex flex-col gap-1.5 p-6', className)} {...props} />
);

export const CardTitle = ({ className, ...props }: HTMLAttributes<HTMLHeadingElement>) => (
  <h3 className={cn('text-lg font-semibold leading-none', className)} {...props} />
);

export const CardDescription = ({ className, ...props }: HTMLAttributes<HTMLParagraphElement>) => (
  <p className={cn('text-sm text-muted-foreground', className)} {...props} />
);

export const CardContent = ({ className, ...props }: HTMLAttributes<HTMLDivElement>) => (
  <div className={cn('p-6 pt-0', className)} {...props} />
);

export const CardFooter = ({ className, ...props }: HTMLAttributes<HTMLDivElement>) => (
  <div className={cn('flex items-center p-6 pt-0', className)} {...props} />
);
```

### `src/shared/ui/Badge.tsx`

```typescript
import type { HTMLAttributes } from 'react';
import { cn } from '@/shared/lib/cn';

type Tone = 'neutral' | 'success' | 'danger' | 'warning' | 'primary';

const tones: Record<Tone, string> = {
  neutral: 'bg-muted text-muted-foreground',
  success: 'bg-success/15 text-success',
  danger:  'bg-danger/15 text-danger',
  warning: 'bg-warning/15 text-warning',
  primary: 'bg-primary/15 text-primary',
};

interface Props extends HTMLAttributes<HTMLSpanElement> {
  tone?: Tone;
}

export const Badge = ({ className, tone = 'neutral', ...props }: Props) => (
  <span
    className={cn(
      'inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-semibold',
      tones[tone], className,
    )}
    {...props}
  />
);
```

### `src/shared/ui/Skeleton.tsx`, `EmptyState.tsx`, `ErrorState.tsx`

```typescript
// Skeleton.tsx
import { cn } from '@/shared/lib/cn';
import type { HTMLAttributes } from 'react';
export const Skeleton = ({ className, ...props }: HTMLAttributes<HTMLDivElement>) => (
  <div className={cn('animate-pulse rounded-md bg-muted', className)} {...props} />
);
```

```typescript
// EmptyState.tsx
import type { ReactNode } from 'react';
import { Inbox } from 'lucide-react';
interface Props { title: string; description?: string; icon?: ReactNode; action?: ReactNode; }
export const EmptyState = ({ title, description, icon, action }: Props) => (
  <div className="flex flex-col items-center justify-center gap-3 py-12 text-center">
    <div className="rounded-full bg-muted p-4 text-muted-foreground">
      {icon ?? <Inbox className="h-6 w-6" aria-hidden />}
    </div>
    <div>
      <h3 className="font-semibold">{title}</h3>
      {description && <p className="mt-1 text-sm text-muted-foreground">{description}</p>}
    </div>
    {action}
  </div>
);
```

```typescript
// ErrorState.tsx
import { AlertTriangle } from 'lucide-react';
import { Button } from './Button';
interface Props { title?: string; message: string; onRetry?: () => void; }
export const ErrorState = ({ title = 'Algo salió mal', message, onRetry }: Props) => (
  <div role="alert" className="flex flex-col items-center gap-3 rounded-lg border border-danger/30 bg-danger/5 p-6 text-center">
    <AlertTriangle className="h-8 w-8 text-danger" aria-hidden />
    <div>
      <h3 className="font-semibold text-danger">{title}</h3>
      <p className="mt-1 text-sm text-muted-foreground">{message}</p>
    </div>
    {onRetry && <Button variant="secondary" size="sm" onClick={onRetry}>Reintentar</Button>}
  </div>
);
```

### `src/shared/ui/DataTable.tsx` — tabla genérica accesible

```typescript
import type { ReactNode } from 'react';
import { cn } from '@/shared/lib/cn';

export interface Column<T> {
  key: string;
  header: string;
  cell: (row: T) => ReactNode;
  align?: 'left' | 'right' | 'center';
  width?: string;
}

interface Props<T> {
  caption?: string;
  columns: Column<T>[];
  data: T[];
  rowKey: (row: T) => string;
  emptyState?: ReactNode;
}

export function DataTable<T>({ caption, columns, data, rowKey, emptyState }: Props<T>) {
  if (data.length === 0 && emptyState) return <>{emptyState}</>;

  const align = (a?: 'left' | 'right' | 'center') =>
    a === 'right' ? 'text-right' : a === 'center' ? 'text-center' : 'text-left';

  return (
    <div className="overflow-x-auto rounded-lg border">
      <table className="w-full text-sm">
        {caption && <caption className="sr-only">{caption}</caption>}
        <thead className="bg-muted/50">
          <tr>
            {columns.map((c) => (
              <th
                key={c.key}
                scope="col"
                style={{ width: c.width }}
                className={cn('h-11 px-4 font-semibold', align(c.align))}
              >
                {c.header}
              </th>
            ))}
          </tr>
        </thead>
        <tbody className="divide-y">
          {data.map((row) => (
            <tr key={rowKey(row)} className="hover:bg-muted/30">
              {columns.map((c) => (
                <td key={c.key} className={cn('px-4 py-3', align(c.align))}>
                  {c.cell(row)}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
```

---

## 7. Layout — `AppShell` y `PageHeader`

### `src/shared/layout/AppShell.tsx`

```typescript
import type { ReactNode } from 'react';
import { NavLink, Outlet } from 'react-router-dom';
import { LayoutDashboard, FilePlus2, ListChecks } from 'lucide-react';
import { UserMenu } from '@/features/auth/components/UserMenu';
import { RoleGuard } from '@/features/auth/guards/RoleGuard';
import { cn } from '@/shared/lib/cn';

const navItem = ({ isActive }: { isActive: boolean }) =>
  cn(
    'flex items-center gap-2 rounded-md px-3 py-2 text-sm font-medium transition-colors',
    isActive ? 'bg-primary/10 text-primary' : 'text-muted-foreground hover:bg-muted hover:text-foreground',
  );

const NavItem = ({ to, icon, children }: { to: string; icon: ReactNode; children: ReactNode }) => (
  <NavLink to={to} className={navItem} end>
    {icon}{children}
  </NavLink>
);

export const AppShell = () => (
  <div className="min-h-screen bg-background">
    <header className="sticky top-0 z-40 border-b bg-card/80 backdrop-blur">
      <div className="mx-auto flex h-16 max-w-7xl items-center justify-between px-6">
        <div className="flex items-center gap-2">
          <div className="grid h-8 w-8 place-items-center rounded-md bg-primary text-primary-foreground font-bold">B</div>
          <span className="font-semibold">Banco · Evaluación de Créditos</span>
        </div>
        <UserMenu />
      </div>
    </header>

    <div className="mx-auto flex max-w-7xl gap-6 px-6 py-6">
      <aside className="hidden w-56 shrink-0 lg:block">
        <nav aria-label="Principal" className="flex flex-col gap-1">
          <NavItem to="/" icon={<LayoutDashboard className="h-4 w-4" />}>Dashboard</NavItem>
          <NavItem to="/evaluaciones" icon={<ListChecks className="h-4 w-4" />}>Evaluaciones</NavItem>
          <RoleGuard roles={['ANALYST', 'ADMIN']}>
            <NavItem to="/evaluaciones/nueva" icon={<FilePlus2 className="h-4 w-4" />}>Nueva Evaluación</NavItem>
          </RoleGuard>
        </nav>
      </aside>

      <main className="flex-1 min-w-0">
        <Outlet />
      </main>
    </div>
  </div>
);
```

### `src/shared/layout/PageHeader.tsx`

```typescript
import type { ReactNode } from 'react';
interface Props { title: string; description?: string; actions?: ReactNode; }
export const PageHeader = ({ title, description, actions }: Props) => (
  <div className="mb-6 flex flex-wrap items-end justify-between gap-4">
    <div>
      <h1 className="text-2xl font-bold tracking-tight">{title}</h1>
      {description && <p className="mt-1 text-sm text-muted-foreground">{description}</p>}
    </div>
    {actions && <div className="flex items-center gap-2">{actions}</div>}
  </div>
);
```

### `src/features/auth/components/UserMenu.tsx` — menú accesible con Radix

```typescript
import * as Dropdown from '@radix-ui/react-dropdown-menu';
import { LogOut, User } from 'lucide-react';
import { useAuth } from '../hooks/useAuth';
import { Button } from '@/shared/ui/Button';
import { Badge } from '@/shared/ui/Badge';

export const UserMenu = () => {
  const { user, logout } = useAuth();
  if (!user) return null;
  return (
    <Dropdown.Root>
      <Dropdown.Trigger asChild>
        <Button variant="ghost" size="sm" className="gap-2">
          <User className="h-4 w-4" aria-hidden />
          <span className="hidden sm:inline">{user.email}</span>
        </Button>
      </Dropdown.Trigger>
      <Dropdown.Portal>
        <Dropdown.Content
          align="end" sideOffset={8}
          className="z-50 min-w-56 rounded-md border bg-card p-2 shadow-lg"
        >
          <div className="px-2 py-1.5">
            <p className="text-sm font-medium">{user.name}</p>
            <p className="text-xs text-muted-foreground">{user.email}</p>
            <div className="mt-2 flex flex-wrap gap-1">
              {user.roles.map((r) => <Badge key={r} tone="primary">{r}</Badge>)}
            </div>
          </div>
          <Dropdown.Separator className="my-1 h-px bg-border" />
          <Dropdown.Item
            onSelect={logout}
            className="flex cursor-pointer items-center gap-2 rounded-sm px-2 py-1.5 text-sm outline-none data-[highlighted]:bg-muted"
          >
            <LogOut className="h-4 w-4" aria-hidden />
            Cerrar sesión
          </Dropdown.Item>
        </Dropdown.Content>
      </Dropdown.Portal>
    </Dropdown.Root>
  );
};
```

---

## 8. Feature: `credit-evaluations`

### `src/features/credit-evaluations/types/index.ts`

```typescript
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
```

### `src/features/credit-evaluations/schemas/creditSchema.ts`

```typescript
import { z } from 'zod';

export const creditSchema = z.object({
  cedula: z
    .string()
    .regex(/^\d{10}$/, 'La cédula debe tener exactamente 10 dígitos'),
  montoSolicitado: z
    .coerce.number({ invalid_type_error: 'Ingrese un monto válido' })
    .positive('El monto debe ser mayor a 0')
    .max(1_000_000, 'El monto no puede exceder $1,000,000'),
  plazoAnios: z
    .coerce.number({ invalid_type_error: 'Ingrese un plazo válido' })
    .int('El plazo debe ser entero')
    .min(1, 'Mínimo 1 año')
    .max(30, 'Máximo 30 años'),
  salario: z
    .coerce.number({ invalid_type_error: 'Ingrese un salario válido' })
    .positive('El salario debe ser mayor a 0'),
  destinatarioEmail: z
    .string()
    .email('Email inválido')
    .optional()
    .or(z.literal('').transform(() => undefined)),
});

export type CreditFormValues = z.infer<typeof creditSchema>;
```

### `src/features/credit-evaluations/api/creditApi.ts`

```typescript
import { apiClient } from '@/shared/lib/apiClient';
import type { EvaluacionCredito } from '../types';
import type { CreditFormValues } from '../schemas/creditSchema';

const BASE = '/v1/credit-evaluations';

export const creditApi = {
  create: async (payload: CreditFormValues): Promise<EvaluacionCredito> => {
    const { data } = await apiClient.post<EvaluacionCredito>(BASE, payload);
    return data;
  },
  list: async (page = 0, size = 20): Promise<EvaluacionCredito[]> => {
    const { data } = await apiClient.get<EvaluacionCredito[]>(BASE, { params: { page, size } });
    return data;
  },
  byId: async (id: string): Promise<EvaluacionCredito> => {
    const { data } = await apiClient.get<EvaluacionCredito>(`${BASE}/${id}`);
    return data;
  },
};
```

### `src/features/credit-evaluations/api/queryKeys.ts`

```typescript
export const evaluationKeys = {
  all: ['evaluations'] as const,
  lists: () => [...evaluationKeys.all, 'list'] as const,
  list: (page: number, size: number) => [...evaluationKeys.lists(), { page, size }] as const,
  detail: (id: string) => [...evaluationKeys.all, 'detail', id] as const,
};
```

### `src/features/credit-evaluations/hooks/useEvaluations.ts`

```typescript
import { useQuery } from '@tanstack/react-query';
import { creditApi } from '../api/creditApi';
import { evaluationKeys } from '../api/queryKeys';

export const useEvaluations = (page = 0, size = 20) =>
  useQuery({
    queryKey: evaluationKeys.list(page, size),
    queryFn: () => creditApi.list(page, size),
    staleTime: 30_000,
  });
```

### `src/features/credit-evaluations/hooks/useCreateEvaluation.ts`

```typescript
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { creditApi } from '../api/creditApi';
import { evaluationKeys } from '../api/queryKeys';
import { extractApiError } from '@/shared/lib/apiClient';
import type { CreditFormValues } from '../schemas/creditSchema';
import type { EvaluacionCredito } from '../types';

export const useCreateEvaluation = () => {
  const qc = useQueryClient();
  return useMutation<EvaluacionCredito, unknown, CreditFormValues>({
    mutationFn: creditApi.create,
    onSuccess: (data) => {
      qc.invalidateQueries({ queryKey: evaluationKeys.lists() });
      toast.success(`Evaluación ${data.estadoFinal.toLowerCase()}`, {
        description: `Score de riesgo: ${data.scoreRiesgo}`,
      });
    },
    onError: (err) => {
      toast.error('No se pudo crear la evaluación', { description: extractApiError(err) });
    },
  });
};
```

### `src/features/credit-evaluations/components/EvaluationStatusBadge.tsx`

```typescript
import { Badge } from '@/shared/ui/Badge';
import type { EstadoEvaluacion } from '../types';

const map: Record<EstadoEvaluacion, { tone: 'success' | 'danger' | 'warning'; label: string }> = {
  APROBADO:  { tone: 'success', label: 'Aprobado' },
  RECHAZADO: { tone: 'danger',  label: 'Rechazado' },
  PENDIENTE: { tone: 'warning', label: 'Pendiente' },
};

export const EvaluationStatusBadge = ({ estado }: { estado: EstadoEvaluacion }) => {
  const { tone, label } = map[estado];
  return <Badge tone={tone}>{label}</Badge>;
};
```

### `src/features/credit-evaluations/components/CreditEvaluationForm.tsx`

```typescript
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { Send } from 'lucide-react';
import { creditSchema, type CreditFormValues } from '../schemas/creditSchema';
import { useCreateEvaluation } from '../hooks/useCreateEvaluation';
import { Button } from '@/shared/ui/Button';
import { Input } from '@/shared/ui/Input';
import { FormField } from '@/shared/ui/FormField';
import type { EvaluacionCredito } from '../types';

interface Props {
  onSuccess?: (ev: EvaluacionCredito) => void;
}

export const CreditEvaluationForm = ({ onSuccess }: Props) => {
  const { mutateAsync, isPending } = useCreateEvaluation();
  const {
    register, handleSubmit, reset,
    formState: { errors, isValid },
  } = useForm<CreditFormValues>({
    resolver: zodResolver(creditSchema),
    mode: 'onBlur',
    defaultValues: { plazoAnios: 1 },
  });

  const onSubmit = handleSubmit(async (values) => {
    const result = await mutateAsync(values);
    reset();
    onSuccess?.(result);
  });

  return (
    <form onSubmit={onSubmit} noValidate aria-busy={isPending} className="grid gap-4 md:grid-cols-2">
      <FormField label="Cédula" htmlFor="cedula" required hint="10 dígitos numéricos"
        error={errors.cedula?.message} className="md:col-span-2">
        <Input id="cedula" inputMode="numeric" maxLength={10}
          autoComplete="off" invalid={!!errors.cedula} {...register('cedula')} />
      </FormField>

      <FormField label="Monto solicitado (USD)" htmlFor="monto" required
        error={errors.montoSolicitado?.message}>
        <Input id="monto" type="number" step="0.01" min={1}
          invalid={!!errors.montoSolicitado} {...register('montoSolicitado')} />
      </FormField>

      <FormField label="Plazo (años)" htmlFor="plazo" required hint="Entre 1 y 30 años"
        error={errors.plazoAnios?.message}>
        <Input id="plazo" type="number" min={1} max={30}
          invalid={!!errors.plazoAnios} {...register('plazoAnios')} />
      </FormField>

      <FormField label="Salario mensual (USD)" htmlFor="salario" required
        error={errors.salario?.message}>
        <Input id="salario" type="number" step="0.01" min={1}
          invalid={!!errors.salario} {...register('salario')} />
      </FormField>

      <FormField label="Email del solicitante" htmlFor="email" hint="Opcional — recibirá el resultado"
        error={errors.destinatarioEmail?.message}>
        <Input id="email" type="email" autoComplete="email"
          invalid={!!errors.destinatarioEmail} {...register('destinatarioEmail')} />
      </FormField>

      <div className="md:col-span-2 flex justify-end gap-2 pt-2">
        <Button type="button" variant="ghost" onClick={() => reset()} disabled={isPending}>
          Limpiar
        </Button>
        <Button type="submit" loading={isPending} disabled={!isValid && !isPending}>
          <Send className="h-4 w-4" aria-hidden />
          Evaluar crédito
        </Button>
      </div>
    </form>
  );
};
```

### `src/features/credit-evaluations/components/EvaluationResultCard.tsx`

```typescript
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/shared/ui/Card';
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
```

### `src/features/credit-evaluations/components/EvaluationsTable.tsx`

```typescript
import { DataTable, type Column } from '@/shared/ui/DataTable';
import { EvaluationStatusBadge } from './EvaluationStatusBadge';
import { EmptyState } from '@/shared/ui/EmptyState';
import { formatCurrency, formatDateTime } from '@/shared/lib/format';
import type { EvaluacionCredito } from '../types';

const columns: Column<EvaluacionCredito>[] = [
  { key: 'cedula',  header: 'Cédula', cell: (e) => <span className="font-mono">{e.cedula}</span> },
  { key: 'monto',   header: 'Monto',  align: 'right', cell: (e) => formatCurrency(e.montoSolicitado) },
  { key: 'plazo',   header: 'Plazo',  align: 'right', cell: (e) => `${e.plazoAnios} años` },
  { key: 'score',   header: 'Score',  align: 'right', cell: (e) => e.scoreRiesgo },
  { key: 'estado',  header: 'Estado', cell: (e) => <EvaluationStatusBadge estado={e.estadoFinal} /> },
  { key: 'fecha',   header: 'Fecha',  cell: (e) => formatDateTime(e.fechaEvaluacion) },
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
```

---

## 9. Páginas (route components)

### `src/pages/EvaluationsListPage.tsx`

```typescript
import { useEvaluations } from '@/features/credit-evaluations/hooks/useEvaluations';
import { EvaluationsTable } from '@/features/credit-evaluations/components/EvaluationsTable';
import { PageHeader } from '@/shared/layout/PageHeader';
import { Skeleton } from '@/shared/ui/Skeleton';
import { ErrorState } from '@/shared/ui/ErrorState';
import { extractApiError } from '@/shared/lib/apiClient';

const TableSkeleton = () => (
  <div className="space-y-2">
    <Skeleton className="h-11 w-full" />
    {Array.from({ length: 5 }).map((_, i) => <Skeleton key={i} className="h-12 w-full" />)}
  </div>
);

export const EvaluationsListPage = () => {
  const { data, isLoading, isError, error, refetch } = useEvaluations();

  return (
    <>
      <PageHeader title="Evaluaciones" description="Histórico de evaluaciones de crédito." />
      {isLoading && <TableSkeleton />}
      {isError && <ErrorState message={extractApiError(error)} onRetry={() => refetch()} />}
      {data && <EvaluationsTable data={data} />}
    </>
  );
};
```

### `src/pages/NewEvaluationPage.tsx`

```typescript
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { CreditEvaluationForm } from '@/features/credit-evaluations/components/CreditEvaluationForm';
import { EvaluationResultCard } from '@/features/credit-evaluations/components/EvaluationResultCard';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/shared/ui/Card';
import { Button } from '@/shared/ui/Button';
import { PageHeader } from '@/shared/layout/PageHeader';
import type { EvaluacionCredito } from '@/features/credit-evaluations/types';

export const NewEvaluationPage = () => {
  const [resultado, setResultado] = useState<EvaluacionCredito | null>(null);
  const navigate = useNavigate();

  return (
    <>
      <PageHeader
        title="Nueva evaluación"
        description="Completa los datos del solicitante para evaluar el crédito."
        actions={
          <Button variant="ghost" onClick={() => navigate('/evaluaciones')}>
            Ver listado
          </Button>
        }
      />
      <div className="grid gap-6 lg:grid-cols-5">
        <Card className="lg:col-span-3">
          <CardHeader>
            <CardTitle>Solicitud</CardTitle>
            <CardDescription>Los campos marcados con * son obligatorios.</CardDescription>
          </CardHeader>
          <CardContent>
            <CreditEvaluationForm onSuccess={setResultado} />
          </CardContent>
        </Card>
        <div className="lg:col-span-2">
          {resultado ? (
            <EvaluationResultCard evaluacion={resultado} />
          ) : (
            <Card>
              <CardHeader>
                <CardTitle>Resultado</CardTitle>
                <CardDescription>El resultado aparecerá aquí tras la evaluación.</CardDescription>
              </CardHeader>
            </Card>
          )}
        </div>
      </div>
    </>
  );
};
```

### `src/pages/DashboardPage.tsx`

```typescript
import { Link } from 'react-router-dom';
import { useEvaluations } from '@/features/credit-evaluations/hooks/useEvaluations';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/shared/ui/Card';
import { PageHeader } from '@/shared/layout/PageHeader';
import { Skeleton } from '@/shared/ui/Skeleton';
import { Button } from '@/shared/ui/Button';
import { useAuth } from '@/features/auth/hooks/useAuth';

const Stat = ({ label, value, hint }: { label: string; value: string; hint?: string }) => (
  <Card>
    <CardHeader>
      <CardDescription>{label}</CardDescription>
      <CardTitle className="text-3xl">{value}</CardTitle>
    </CardHeader>
    {hint && <CardContent className="text-xs text-muted-foreground">{hint}</CardContent>}
  </Card>
);

export const DashboardPage = () => {
  const { user, canEvaluate } = useAuth();
  const { data, isLoading } = useEvaluations(0, 100);

  const aprobadas = data?.filter((e) => e.estadoFinal === 'APROBADO').length ?? 0;
  const rechazadas = data?.filter((e) => e.estadoFinal === 'RECHAZADO').length ?? 0;
  const total = data?.length ?? 0;

  return (
    <>
      <PageHeader
        title={`Hola, ${user?.name}`}
        description="Resumen de actividad reciente."
        actions={canEvaluate && (
          <Button asChild><Link to="/evaluaciones/nueva">Nueva evaluación</Link></Button>
        )}
      />
      <div className="grid gap-4 sm:grid-cols-3">
        {isLoading
          ? Array.from({ length: 3 }).map((_, i) => <Skeleton key={i} className="h-32" />)
          : (
            <>
              <Stat label="Total" value={String(total)} hint="Evaluaciones registradas" />
              <Stat label="Aprobadas" value={String(aprobadas)} />
              <Stat label="Rechazadas" value={String(rechazadas)} />
            </>
          )}
      </div>
    </>
  );
};
```

---

## 10. Router y providers

### `src/app/router/ProtectedRoute.tsx`

```typescript
import type { ReactNode } from 'react';
import { Navigate } from 'react-router-dom';
import { useAuth, type Role } from '@/features/auth/hooks/useAuth';
import { EmptyState } from '@/shared/ui/EmptyState';
import { ShieldAlert } from 'lucide-react';

interface Props { roles?: Role[]; children: ReactNode; }

export const ProtectedRoute = ({ roles, children }: Props) => {
  const { user, hasRole } = useAuth();
  if (!user) return <Navigate to="/" replace />;
  if (roles && !hasRole(...roles)) {
    return (
      <EmptyState
        icon={<ShieldAlert className="h-6 w-6" />}
        title="Acceso restringido"
        description="No tienes permisos para acceder a esta sección."
      />
    );
  }
  return <>{children}</>;
};
```

### `src/app/router/AppRouter.tsx`

```typescript
import { createBrowserRouter, RouterProvider } from 'react-router-dom';
import { AppShell } from '@/shared/layout/AppShell';
import { DashboardPage } from '@/pages/DashboardPage';
import { EvaluationsListPage } from '@/pages/EvaluationsListPage';
import { NewEvaluationPage } from '@/pages/NewEvaluationPage';
import { ProtectedRoute } from './ProtectedRoute';

const router = createBrowserRouter([
  {
    element: <AppShell />,
    children: [
      { index: true, element: <DashboardPage /> },
      { path: 'evaluaciones', element: <EvaluationsListPage /> },
      {
        path: 'evaluaciones/nueva',
        element: (
          <ProtectedRoute roles={['ANALYST', 'ADMIN']}>
            <NewEvaluationPage />
          </ProtectedRoute>
        ),
      },
    ],
  },
]);

export const AppRouter = () => <RouterProvider router={router} />;
```

### `src/app/providers/QueryProvider.tsx`

```typescript
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ReactQueryDevtools } from '@tanstack/react-query-devtools';
import type { ReactNode } from 'react';

const client = new QueryClient({
  defaultOptions: {
    queries: { retry: 1, refetchOnWindowFocus: false, staleTime: 30_000 },
    mutations: { retry: 0 },
  },
});

export const QueryProvider = ({ children }: { children: ReactNode }) => (
  <QueryClientProvider client={client}>
    {children}
    {import.meta.env.DEV && <ReactQueryDevtools initialIsOpen={false} />}
  </QueryClientProvider>
);
```

### `src/app/providers/AppProviders.tsx`

```typescript
import type { ReactNode } from 'react';
import { Toaster } from 'sonner';
import { QueryProvider } from './QueryProvider';

export const AppProviders = ({ children }: { children: ReactNode }) => (
  <QueryProvider>
    {children}
    <Toaster position="top-right" richColors closeButton />
  </QueryProvider>
);
```

### `src/app/App.tsx`

```typescript
import { AppProviders } from './providers/AppProviders';
import { AppRouter } from './router/AppRouter';

export const App = () => (
  <AppProviders>
    <AppRouter />
  </AppProviders>
);
```

### `src/main.tsx` — boot con Keycloak

```typescript
import React from 'react';
import ReactDOM from 'react-dom/client';
import { App } from './app/App';
import { keycloak, initKeycloak } from '@/shared/lib/keycloak';
import './styles/globals.css';

initKeycloak()
  .then((authenticated) => {
    if (!authenticated) {
      keycloak.login();
      return;
    }
    keycloak.onTokenExpired = () => {
      keycloak.updateToken(30).catch(() => keycloak.logout());
    };
    ReactDOM.createRoot(document.getElementById('root')!).render(
      <React.StrictMode><App /></React.StrictMode>,
    );
  })
  .catch((err) => {
    console.error('Keycloak init failed', err);
    document.body.innerHTML =
      '<div style="padding:2rem;font-family:system-ui">No se pudo inicializar la autenticación.</div>';
  });
```

---

## 11. Levantar el frontend

```bash
cd frontend
npm run dev
# http://localhost:3000
```

Asegura que en Keycloak (paso 02) `http://localhost:3000/*` esté en **Valid Redirect URIs**
y `http://localhost:3000` en **Web Origins**.

### Verificación funcional

```bash
# 1. http://localhost:3000 → redirige a Keycloak
# 2. Login: analyst@banco.com / Analyst123!
# 3. Dashboard muestra stats (Total / Aprobadas / Rechazadas)
# 4. /evaluaciones/nueva → formulario validado en cliente con Zod
#    Cédula 1713175071 | Monto 5000 | Plazo 3 | Salario 2000
# 5. Toast con resultado + EvaluationResultCard
# 6. /evaluaciones lista la nueva entrada (cache invalidada)
# 7. Logout → re-login como viewer@banco.com / Viewer123!
#    /evaluaciones/nueva → "Acceso restringido"
```

---

## 12. Pruebas

### `src/test/setup.ts`

```typescript
import '@testing-library/jest-dom';
import { afterAll, afterEach, beforeAll } from 'vitest';
import { server } from './mocks/server';

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
```

### `src/test/test-utils.tsx` — render con providers

```typescript
import type { ReactElement, ReactNode } from 'react';
import { render, type RenderOptions } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';

const createTestClient = () =>
  new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });

interface Options extends Omit<RenderOptions, 'wrapper'> {
  route?: string;
}

export const renderWithProviders = (ui: ReactElement, { route = '/', ...rest }: Options = {}) => {
  const client = createTestClient();
  const Wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[route]}>{children}</MemoryRouter>
    </QueryClientProvider>
  );
  return { client, ...render(ui, { wrapper: Wrapper, ...rest }) };
};

export * from '@testing-library/react';
export { default as userEvent } from '@testing-library/user-event';
```

### `src/test/mocks/handlers.ts`

```typescript
import { http, HttpResponse } from 'msw';
import { env } from '@/shared/config/env';
import type { EvaluacionCredito } from '@/features/credit-evaluations/types';

const evaluacion: EvaluacionCredito = {
  id: '550e8400-e29b-41d4-a716-446655440001',
  cedula: '1713175071',
  montoSolicitado: 5000, plazoAnios: 3, salario: 2000,
  scoreRiesgo: 85, deudaMensualTotal: 200,
  estadoFinal: 'APROBADO',
  fechaEvaluacion: '2026-05-05T14:30:00Z',
  evaluadoPorId: '550e8400-e29b-41d4-a716-446655440000',
};

const url = (p: string) => `${env.VITE_API_BASE_URL}${p}`;

export const handlers = [
  http.get(url('/v1/credit-evaluations'),  () => HttpResponse.json([evaluacion])),
  http.post(url('/v1/credit-evaluations'), () => HttpResponse.json(evaluacion, { status: 201 })),
];

export const errorHandlers = [
  http.post(url('/v1/credit-evaluations'), () =>
    HttpResponse.json({ status: 422, error: 'cédula inválida' }, { status: 422 }),
  ),
];
```

### `src/test/mocks/server.ts`

```typescript
import { setupServer } from 'msw/node';
import { handlers } from './handlers';
export const server = setupServer(...handlers);
```

### `src/test/__mocks__/keycloak.ts`

```typescript
import { vi } from 'vitest';
export const keycloak = {
  token: 'fake-jwt',
  tokenParsed: {
    sub: '550e8400-e29b-41d4-a716-446655440000',
    email: 'analyst@banco.com',
    name: 'Ana Lista',
    groups: ['ANALYST'],
  },
  authenticated: true,
  init: vi.fn().mockResolvedValue(true),
  login: vi.fn(), logout: vi.fn(),
  updateToken: vi.fn().mockResolvedValue(true),
  onTokenExpired: undefined as undefined | (() => void),
};
export const initKeycloak = () => keycloak.init();
```

Activar el mock globalmente en cualquier test que lo necesite:
```typescript
vi.mock('@/shared/lib/keycloak', () => import('../test/__mocks__/keycloak'));
```

### Tests unitarios — `EvaluationStatusBadge.test.tsx`

```typescript
import { render, screen } from '@testing-library/react';
import { EvaluationStatusBadge } from '@/features/credit-evaluations/components/EvaluationStatusBadge';

describe('EvaluationStatusBadge', () => {
  test.each([
    ['APROBADO',  'Aprobado'],
    ['RECHAZADO', 'Rechazado'],
    ['PENDIENTE', 'Pendiente'],
  ] as const)('renderiza %s con label %s', (estado, label) => {
    render(<EvaluationStatusBadge estado={estado} />);
    expect(screen.getByText(label)).toBeInTheDocument();
  });
});
```

### Tests unitarios — `EvaluationsTable.test.tsx`

```typescript
import { render, screen } from '@testing-library/react';
import { EvaluationsTable } from '@/features/credit-evaluations/components/EvaluationsTable';
import type { EvaluacionCredito } from '@/features/credit-evaluations/types';

const sample: EvaluacionCredito[] = [
  { id: '1', cedula: '1713175071', montoSolicitado: 5000, plazoAnios: 3, salario: 2000,
    scoreRiesgo: 85, deudaMensualTotal: 200, estadoFinal: 'APROBADO',
    fechaEvaluacion: '2026-05-05T14:30:00Z', evaluadoPorId: 'u1' },
];

describe('EvaluationsTable', () => {
  it('muestra empty state cuando no hay datos', () => {
    render(<EvaluationsTable data={[]} />);
    expect(screen.getByText(/aún no hay evaluaciones/i)).toBeInTheDocument();
  });

  it('renderiza filas con datos formateados y badge', () => {
    render(<EvaluationsTable data={sample} />);
    expect(screen.getByText('1713175071')).toBeInTheDocument();
    expect(screen.getByText(/\$5,?000\.00/)).toBeInTheDocument();
    expect(screen.getByText('Aprobado')).toBeInTheDocument();
  });

  it('expone tabla accesible con caption oculto', () => {
    render(<EvaluationsTable data={sample} />);
    expect(screen.getByRole('table', { name: /listado de evaluaciones/i })).toBeInTheDocument();
  });
});
```

### Test de integración — `CreditEvaluationForm.test.tsx`

```typescript
import { renderWithProviders, screen, waitFor, userEvent } from '@/test/test-utils';
import { server } from '@/test/mocks/server';
import { errorHandlers } from '@/test/mocks/handlers';
import { CreditEvaluationForm } from '@/features/credit-evaluations/components/CreditEvaluationForm';

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
    await waitFor(() => expect(onSuccess).toHaveBeenCalledWith(
      expect.objectContaining({ estadoFinal: 'APROBADO' }),
    ));
  });

  it('muestra error del backend (422)', async () => {
    server.use(...errorHandlers);
    renderWithProviders(<CreditEvaluationForm />);
    const user = userEvent.setup();
    await fillValid(user);
    await user.click(screen.getByRole('button', { name: /evaluar crédito/i }));
    expect(await screen.findByText(/cédula inválida/i)).toBeInTheDocument();
  });

  it('deshabilita el botón mientras envía', async () => {
    renderWithProviders(<CreditEvaluationForm />);
    const user = userEvent.setup();
    await fillValid(user);
    const btn = screen.getByRole('button', { name: /evaluar crédito/i });
    await user.click(btn);
    expect(btn).toBeDisabled();
  });
});
```

### Test de integración — `EvaluationsListPage.test.tsx`

```typescript
import { renderWithProviders, screen, waitFor } from '@/test/test-utils';
import { EvaluationsListPage } from '@/pages/EvaluationsListPage';

vi.mock('@/shared/lib/keycloak', () => import('@/test/__mocks__/keycloak'));

describe('EvaluationsListPage', () => {
  it('renderiza skeleton y luego datos', async () => {
    renderWithProviders(<EvaluationsListPage />);
    await waitFor(() => expect(screen.getByText('1713175071')).toBeInTheDocument());
  });
});
```

### Ejecutar tests

```bash
npx vitest run            # CI
npx vitest                # watch
npx vitest --ui           # UI interactiva
npx vitest run --coverage # cobertura
```

---

## 13. Checklist de calidad

### Arquitectura
- [ ] Feature-based: cada dominio en `src/features/<feature>/{api,hooks,components,schemas,types}`.
- [ ] `shared/ui` solo contiene componentes sin dependencias de dominio.
- [ ] Páginas en `pages/` componen features, no contienen lógica de negocio.

### UX/UI
- [ ] Tokens de color semánticos (success/danger/warning) y soporte light/dark vía CSS vars.
- [ ] Todo componente con `loading | empty | error | success`.
- [ ] Toasts (sonner) para feedback de mutaciones.
- [ ] Skeletons en lugar de spinners para listas y dashboards.
- [ ] Layout responsive (grid `md:`/`lg:` breakpoints).

### Accesibilidad
- [ ] Inputs con `<label htmlFor>` y `aria-invalid` en errores.
- [ ] Errores de form con `role="alert"`.
- [ ] Foco visible (`focus-visible:ring`) en todos los interactivos.
- [ ] Iconos decorativos con `aria-hidden`.
- [ ] Tabla con `<caption>` accesible (sr-only) y `<th scope="col">`.
- [ ] Menús con Radix → navegación por teclado y ARIA correctos.

### Seguridad
- [ ] JWT inyectado por interceptor; nunca en localStorage manual.
- [ ] Refresh automático antes de expirar; logout en fallo.
- [ ] Guards por rol en rutas y secciones (`RoleGuard`, `ProtectedRoute`).
- [ ] CORS y `Web Origins` configurados en Keycloak.

### Calidad de código
- [ ] TypeScript strict + `noUncheckedIndexedAccess`.
- [ ] Tipos derivados del schema Zod (`z.infer`).
- [ ] Sin `any` salvo en boundaries explícitos.
- [ ] TanStack Query para todo state servidor (cache + invalidación).
- [ ] React Hook Form para formularios (mínimas re-renders).

### Testing
- [ ] Tests por componente atómico (badge, table).
- [ ] Tests de integración del formulario con MSW (happy path + error).
- [ ] Tests de página con render + providers.
- [ ] `onUnhandledRequest: 'error'` en MSW para detectar requests no mockeadas.

---

## Estado esperado al finalizar
- [ ] App arranca en `http://localhost:3000` con login Keycloak (PKCE).
- [ ] Design system con tokens, componentes atómicos y dark mode listo.
- [ ] Dashboard, listado y formulario funcionando con TanStack Query + Zod.
- [ ] Rutas protegidas por rol; UI adaptada a permisos del usuario.
- [ ] `npx vitest run` verde: unitarios + integración + página.
- [ ] Lighthouse a11y ≥ 95 en las páginas principales.
