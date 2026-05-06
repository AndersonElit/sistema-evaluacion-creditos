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
