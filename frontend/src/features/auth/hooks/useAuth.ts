import { useMemo } from 'react';
import { keycloak } from '@/shared/lib/keycloak';

export type Role = 'ANALYST' | 'ADMIN' | 'VIEWER';

export interface AuthUser {
  id: string;
  email: string;
  name: string;
  roles: Role[];
}

interface ParsedToken {
  sub?: string;
  email?: string;
  name?: string;
  preferred_username?: string;
  groups?: string[];
}

export const useAuth = () => {
  const user = useMemo<AuthUser | null>(() => {
    const t = keycloak.tokenParsed as ParsedToken | undefined;
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
