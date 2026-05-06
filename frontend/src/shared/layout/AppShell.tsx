import type { ReactNode } from 'react';
import { NavLink, Outlet } from 'react-router-dom';
import { LayoutDashboard, FilePlus2, ListChecks } from 'lucide-react';
import { UserMenu } from '@/features/auth/components/UserMenu';
import { RoleGuard } from '@/features/auth/guards/RoleGuard';
import { cn } from '@/shared/lib/cn';

const navItem = ({ isActive }: { isActive: boolean }) =>
  cn(
    'flex items-center gap-2 rounded-md px-3 py-2 text-sm font-medium transition-colors',
    isActive
      ? 'bg-primary/10 text-primary'
      : 'text-muted-foreground hover:bg-muted hover:text-foreground',
  );

const NavItem = ({ to, icon, children }: { to: string; icon: ReactNode; children: ReactNode }) => (
  <NavLink to={to} className={navItem} end>
    {icon}
    {children}
  </NavLink>
);

export const AppShell = () => (
  <div className="min-h-screen bg-background">
    <header className="sticky top-0 z-40 border-b bg-card/80 backdrop-blur">
      <div className="mx-auto flex h-16 max-w-7xl items-center justify-between px-6">
        <div className="flex items-center gap-2">
          <div className="grid h-8 w-8 place-items-center rounded-md bg-primary font-bold text-primary-foreground">
            B
          </div>
          <span className="font-semibold">Banco · Evaluación de Créditos</span>
        </div>
        <UserMenu />
      </div>
    </header>

    <div className="mx-auto flex max-w-7xl gap-6 px-6 py-6">
      <aside className="hidden w-56 shrink-0 lg:block">
        <nav aria-label="Principal" className="flex flex-col gap-1">
          <NavItem to="/" icon={<LayoutDashboard className="h-4 w-4" />}>
            Dashboard
          </NavItem>
          <NavItem to="/evaluaciones" icon={<ListChecks className="h-4 w-4" />}>
            Evaluaciones
          </NavItem>
          <RoleGuard roles={['ANALYST', 'ADMIN']}>
            <NavItem to="/evaluaciones/nueva" icon={<FilePlus2 className="h-4 w-4" />}>
              Nueva Evaluación
            </NavItem>
          </RoleGuard>
        </nav>
      </aside>

      <main className="min-w-0 flex-1">
        <Outlet />
      </main>
    </div>
  </div>
);
