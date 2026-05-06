const currencyFmt = new Intl.NumberFormat('es-EC', {
  style: 'currency',
  currency: 'USD',
  maximumFractionDigits: 2,
});

const dateFmt = new Intl.DateTimeFormat('es-EC', {
  dateStyle: 'medium',
  timeStyle: 'short',
});

export const formatCurrency = (n: number) => currencyFmt.format(n);
export const formatDateTime = (iso: string) => dateFmt.format(new Date(iso));
