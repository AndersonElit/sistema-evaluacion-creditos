import type { ReactNode } from 'react';
import { cn } from '@/shared/lib/cn';

export interface Column<T> {
  key: string;
  header: string;
  cell: (row: T) => ReactNode;
  align?: 'left' | 'right' | 'center';
  width?: string;
}

interface Props<T> {
  caption?: string;
  columns: Column<T>[];
  data: T[];
  rowKey: (row: T) => string;
  emptyState?: ReactNode;
}

export function DataTable<T>({ caption, columns, data, rowKey, emptyState }: Props<T>) {
  if (data.length === 0 && emptyState) return <>{emptyState}</>;

  const align = (a?: 'left' | 'right' | 'center') =>
    a === 'right' ? 'text-right' : a === 'center' ? 'text-center' : 'text-left';

  return (
    <div className="overflow-x-auto rounded-lg border">
      <table className="w-full text-sm">
        {caption && <caption className="sr-only">{caption}</caption>}
        <thead className="bg-muted/50">
          <tr>
            {columns.map((c) => (
              <th
                key={c.key}
                scope="col"
                style={c.width ? { width: c.width } : undefined}
                className={cn('h-11 px-4 font-semibold', align(c.align))}
              >
                {c.header}
              </th>
            ))}
          </tr>
        </thead>
        <tbody className="divide-y">
          {data.map((row) => (
            <tr key={rowKey(row)} className="hover:bg-muted/30">
              {columns.map((c) => (
                <td key={c.key} className={cn('px-4 py-3', align(c.align))}>
                  {c.cell(row)}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
