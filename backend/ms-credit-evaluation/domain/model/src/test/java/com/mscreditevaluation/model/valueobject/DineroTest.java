package com.mscreditevaluation.model.valueobject;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.*;

class DineroTest {

    @Test
    void monto_positivo_minimo_es_aceptado() {
        assertThatNoException().isThrownBy(() -> Dinero.usd(new BigDecimal("0.01")));
    }

    @Test
    void monto_cero_es_rechazado() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> Dinero.usd(BigDecimal.ZERO))
                .withMessageContaining("positivo");
    }

    @Test
    void monto_negativo_es_rechazado() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> Dinero.usd(new BigDecimal("-1")));
    }

    @Test
    void monto_nulo_es_rechazado() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Dinero(null, "USD"));
    }

    @Test
    void moneda_vacia_es_rechazada() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Dinero(new BigDecimal("100"), ""));
    }

    @Test
    void sumar_retorna_suma_correcta() {
        var resultado = Dinero.usd(new BigDecimal("100.00"))
                .sumar(Dinero.usd(new BigDecimal("50.50")));
        assertThat(resultado.cantidad()).isEqualByComparingTo("150.50");
    }

    @Test
    void menorQue_es_correcto() {
        var menor = Dinero.usd(new BigDecimal("99"));
        var mayor = Dinero.usd(new BigDecimal("100"));
        assertThat(menor.menorQue(mayor)).isTrue();
        assertThat(mayor.menorQue(menor)).isFalse();
    }
}
