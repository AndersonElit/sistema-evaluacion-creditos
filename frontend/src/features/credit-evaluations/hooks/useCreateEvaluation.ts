import { useMutation, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { creditApi } from '../api/creditApi';
import { evaluationKeys } from '../api/queryKeys';
import { extractApiError } from '@/shared/lib/apiClient';
import type { CreditFormOutput } from '../schemas/creditSchema';
import type { EvaluacionCredito } from '../types';

export const useCreateEvaluation = () => {
  const qc = useQueryClient();
  return useMutation<EvaluacionCredito, unknown, CreditFormOutput>({
    mutationFn: creditApi.create,
    onSuccess: (data) => {
      qc.invalidateQueries({ queryKey: evaluationKeys.lists() });
      toast.success(`Evaluación ${data.estadoFinal.toLowerCase()}`, {
        description: `Score de riesgo: ${data.scoreRiesgo}`,
      });
    },
    onError: (err) => {
      toast.error('No se pudo crear la evaluación', { description: extractApiError(err) });
    },
  });
};
