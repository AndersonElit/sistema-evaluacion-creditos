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
