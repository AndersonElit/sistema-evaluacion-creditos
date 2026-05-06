import type { ReactNode } from 'react';
import { Toaster } from 'sonner';
import { QueryProvider } from './QueryProvider';

export const AppProviders = ({ children }: { children: ReactNode }) => (
  <QueryProvider>
    {children}
    <Toaster position="top-right" richColors closeButton />
  </QueryProvider>
);
