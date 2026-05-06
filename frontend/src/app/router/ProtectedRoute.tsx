import type { ReactNode } from 'react';
import { Navigate } from 'react-router-dom';
import { ShieldAlert } from 'lucide-react';
import { useAuth, type Role } from '@/features/auth/hooks/useAuth';
import { EmptyState } from '@/shared/ui/EmptyState';

interface Props {
  roles?: Role[];
  children: ReactNode;
}

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
