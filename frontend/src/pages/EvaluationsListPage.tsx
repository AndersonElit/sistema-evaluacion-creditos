import { useEvaluations } from '@/features/credit-evaluations/hooks/useEvaluations';
import { EvaluationsTable } from '@/features/credit-evaluations/components/EvaluationsTable';
import { PageHeader } from '@/shared/layout/PageHeader';
import { Skeleton } from '@/shared/ui/Skeleton';
import { ErrorState } from '@/shared/ui/ErrorState';
import { extractApiError } from '@/shared/lib/apiClient';

const TableSkeleton = () => (
  <div className="space-y-2">
    <Skeleton className="h-11 w-full" />
    {Array.from({ length: 5 }).map((_, i) => (
      <Skeleton key={i} className="h-12 w-full" />
    ))}
  </div>
);

export const EvaluationsListPage = () => {
  const { data, isLoading, isError, error, refetch } = useEvaluations();

  return (
    <>
      <PageHeader title="Evaluaciones" description="Histórico de evaluaciones de crédito." />
      {isLoading && <TableSkeleton />}
      {isError && <ErrorState message={extractApiError(error)} onRetry={() => refetch()} />}
      {data && <EvaluationsTable data={data} />}
    </>
  );
};
