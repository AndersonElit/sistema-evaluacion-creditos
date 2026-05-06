import { useQuery } from '@tanstack/react-query';
import { creditApi } from '../api/creditApi';
import { evaluationKeys } from '../api/queryKeys';

export const useEvaluations = (page = 0, size = 20) =>
  useQuery({
    queryKey: evaluationKeys.list(page, size),
    queryFn: () => creditApi.list(page, size),
    staleTime: 30_000,
  });
