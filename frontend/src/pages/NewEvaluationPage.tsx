import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { CreditEvaluationForm } from '@/features/credit-evaluations/components/CreditEvaluationForm';
import { EvaluationResultCard } from '@/features/credit-evaluations/components/EvaluationResultCard';
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/shared/ui/Card';
import { Button } from '@/shared/ui/Button';
import { PageHeader } from '@/shared/layout/PageHeader';
import type { EvaluacionCredito } from '@/features/credit-evaluations/types';

export const NewEvaluationPage = () => {
  const [resultado, setResultado] = useState<EvaluacionCredito | null>(null);
  const navigate = useNavigate();

  return (
    <>
      <PageHeader
        title="Nueva evaluación"
        description="Completa los datos del solicitante para evaluar el crédito."
        actions={
          <Button variant="ghost" onClick={() => navigate('/evaluaciones')}>
            Ver listado
          </Button>
        }
      />
      <div className="grid gap-6 lg:grid-cols-5">
        <Card className="lg:col-span-3">
          <CardHeader>
            <CardTitle>Solicitud</CardTitle>
            <CardDescription>Los campos marcados con * son obligatorios.</CardDescription>
          </CardHeader>
          <CardContent>
            <CreditEvaluationForm onSuccess={setResultado} />
          </CardContent>
        </Card>
        <div className="lg:col-span-2">
          {resultado ? (
            <EvaluationResultCard evaluacion={resultado} />
          ) : (
            <Card>
              <CardHeader>
                <CardTitle>Resultado</CardTitle>
                <CardDescription>
                  El resultado aparecerá aquí tras la evaluación.
                </CardDescription>
              </CardHeader>
            </Card>
          )}
        </div>
      </div>
    </>
  );
};
