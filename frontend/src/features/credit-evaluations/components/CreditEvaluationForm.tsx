import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { Send } from 'lucide-react';
import {
  creditSchema,
  type CreditFormInput,
  type CreditFormOutput,
} from '../schemas/creditSchema';
import { useCreateEvaluation } from '../hooks/useCreateEvaluation';
import { Button } from '@/shared/ui/Button';
import { Input } from '@/shared/ui/Input';
import { FormField } from '@/shared/ui/FormField';
import type { EvaluacionCredito } from '../types';

interface Props {
  onSuccess?: (ev: EvaluacionCredito) => void;
}

export const CreditEvaluationForm = ({ onSuccess }: Props) => {
  const { mutateAsync, isPending } = useCreateEvaluation();
  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<CreditFormInput, unknown, CreditFormOutput>({
    resolver: zodResolver(creditSchema),
    mode: 'onBlur',
    defaultValues: { plazoAnios: 1 },
  });

  const onSubmit = handleSubmit(async (values) => {
    try {
      const result = await mutateAsync(values);
      reset();
      onSuccess?.(result);
    } catch {
      // error toast handled by hook
    }
  });

  return (
    <form
      onSubmit={onSubmit}
      noValidate
      aria-busy={isPending ? true : undefined}
      className="grid gap-4 md:grid-cols-2"
    >
      <FormField
        label="Cédula"
        htmlFor="cedula"
        required
        hint="10 dígitos numéricos"
        error={errors.cedula?.message}
        className="md:col-span-2"
      >
        <Input
          id="cedula"
          inputMode="numeric"
          maxLength={10}
          autoComplete="off"
          invalid={!!errors.cedula}
          {...register('cedula')}
        />
      </FormField>

      <FormField
        label="Monto solicitado (USD)"
        htmlFor="monto"
        required
        error={errors.montoSolicitado?.message}
      >
        <Input
          id="monto"
          type="number"
          step="0.01"
          min={1}
          invalid={!!errors.montoSolicitado}
          {...register('montoSolicitado')}
        />
      </FormField>

      <FormField
        label="Plazo (años)"
        htmlFor="plazo"
        required
        hint="Entre 1 y 30 años"
        error={errors.plazoAnios?.message}
      >
        <Input
          id="plazo"
          type="number"
          min={1}
          max={30}
          invalid={!!errors.plazoAnios}
          {...register('plazoAnios')}
        />
      </FormField>

      <FormField
        label="Salario mensual (USD)"
        htmlFor="salario"
        required
        error={errors.salario?.message}
      >
        <Input
          id="salario"
          type="number"
          step="0.01"
          min={1}
          invalid={!!errors.salario}
          {...register('salario')}
        />
      </FormField>

      <FormField
        label="Email del solicitante"
        htmlFor="email"
        hint="Opcional — recibirá el resultado"
        error={errors.destinatarioEmail?.message}
      >
        <Input
          id="email"
          type="email"
          autoComplete="email"
          invalid={!!errors.destinatarioEmail}
          {...register('destinatarioEmail')}
        />
      </FormField>

      <div className="flex justify-end gap-2 pt-2 md:col-span-2">
        <Button type="button" variant="ghost" onClick={() => reset()} disabled={isPending}>
          Limpiar
        </Button>
        <Button type="submit" loading={isPending}>
          <Send className="h-4 w-4" aria-hidden />
          Evaluar crédito
        </Button>
      </div>
    </form>
  );
};
