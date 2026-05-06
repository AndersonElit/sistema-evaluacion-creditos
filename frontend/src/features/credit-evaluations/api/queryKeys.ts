export const evaluationKeys = {
  all: ['evaluations'] as const,
  lists: () => [...evaluationKeys.all, 'list'] as const,
  list: (page: number, size: number) => [...evaluationKeys.lists(), { page, size }] as const,
  detail: (id: string) => [...evaluationKeys.all, 'detail', id] as const,
};
