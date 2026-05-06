import { Link } from 'react-router-dom';
import { useEvaluations } from '@/features/credit-evaluations/hooks/useEvaluations';
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/shared/ui/Card';
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
        title={`Hola, ${user?.name ?? ''}`}
        description="Resumen de actividad reciente."
        actions={
          canEvaluate ? (
            <Button asChild>
              <Link to="/evaluaciones/nueva">Nueva evaluación</Link>
            </Button>
          ) : undefined
        }
      />
      <div className="grid gap-4 sm:grid-cols-3">
        {isLoading ? (
          Array.from({ length: 3 }).map((_, i) => <Skeleton key={i} className="h-32" />)
        ) : (
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
