import { vi } from 'vitest';

export const keycloak = {
  token: 'fake-jwt',
  tokenParsed: {
    sub: '550e8400-e29b-41d4-a716-446655440000',
    email: 'analyst@banco.com',
    name: 'Ana Lista',
    groups: ['ANALYST'],
  } as Record<string, unknown>,
  authenticated: true,
  init: vi.fn().mockResolvedValue(true),
  login: vi.fn(),
  logout: vi.fn(),
  updateToken: vi.fn().mockResolvedValue(true),
  onTokenExpired: undefined as undefined | (() => void),
};

export const initKeycloak = () => keycloak.init();
