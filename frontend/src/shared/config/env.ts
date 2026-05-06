import { z } from 'zod';

const envSchema = z.object({
  VITE_API_BASE_URL: z.string().url().default('http://localhost:8080'),
  VITE_KEYCLOAK_URL: z.string().url().default('http://localhost:9000'),
  VITE_KEYCLOAK_REALM: z.string().default('banco'),
  VITE_KEYCLOAK_CLIENT_ID: z.string().default('credit-evaluation-spa'),
});

export const env = envSchema.parse(import.meta.env);
