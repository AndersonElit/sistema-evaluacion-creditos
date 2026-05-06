import { apiClient } from '@/shared/lib/apiClient';
import type { EvaluacionCredito } from '../types';
import type { CreditFormOutput } from '../schemas/creditSchema';

const BASE = '/v1/credit-evaluations';

export const creditApi = {
  create: async (payload: CreditFormOutput): Promise<EvaluacionCredito> => {
    const { data } = await apiClient.post<EvaluacionCredito>(BASE, payload);
    return data;
  },
  list: async (page = 0, size = 20): Promise<EvaluacionCredito[]> => {
    const { data } = await apiClient.get<EvaluacionCredito[]>(BASE, { params: { page, size } });
    return data;
  },
  byId: async (id: string): Promise<EvaluacionCredito> => {
    const { data } = await apiClient.get<EvaluacionCredito>(`${BASE}/${id}`);
    return data;
  },
};
