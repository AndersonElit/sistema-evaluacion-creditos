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
    if (keycloak.token) {
      config.headers.Authorization = `Bearer ${keycloak.token}`;
    }
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
