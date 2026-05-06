import { z } from 'zod';

export const creditSchema = z.object({
  cedula: z.string().regex(/^\d{10}$/, 'La cédula debe tener exactamente 10 dígitos'),
  montoSolicitado: z.coerce
    .number({ message: 'Ingrese un monto válido' })
    .positive('El monto debe ser mayor a 0')
    .max(1_000_000, 'El monto no puede exceder $1,000,000'),
  plazoAnios: z.coerce
    .number({ message: 'Ingrese un plazo válido' })
    .int('El plazo debe ser entero')
    .min(1, 'Mínimo 1 año')
    .max(30, 'Máximo 30 años'),
  salario: z.coerce
    .number({ message: 'Ingrese un salario válido' })
    .positive('El salario debe ser mayor a 0'),
  destinatarioEmail: z
    .email('Email inválido')
    .optional()
    .or(z.literal('').transform(() => undefined)),
});

export type CreditFormInput = z.input<typeof creditSchema>;
export type CreditFormOutput = z.output<typeof creditSchema>;
