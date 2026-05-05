package com.mscreditevaluation.model.valueobject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.*;

class CedulaTest {

    @ParameterizedTest
    @ValueSource(strings = {"1713175071", "0901234567"})
    void cedulas_validas_son_aceptadas(String valor) {
        assertThatNoException().isThrownBy(() -> new Cedula(valor));
    }

    @Test
    void valor_es_accesible_tras_construccion() {
        assertThat(new Cedula("1713175071").valor()).isEqualTo("1713175071");
    }

    @Test
    void cedula_nula_lanza_excepcion() {
        assertThatIllegalArgumentException().isThrownBy(() -> new Cedula(null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"123456789", "17131750711", "ABCDEFGHIJ", "1713 75071", ""})
    void formato_invalido_es_rechazado(String valor) {
        assertThatIllegalArgumentException().isThrownBy(() -> new Cedula(valor));
    }

    @Test
    void provincia_00_es_rechazada() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Cedula("0013175071"))
                .withMessageContaining("provincia");
    }

    @Test
    void provincia_25_es_rechazada() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Cedula("2500000000"))
                .withMessageContaining("provincia");
    }

    @ParameterizedTest
    @ValueSource(strings = {"1234567890", "1713175072", "9999999999"})
    void digito_verificador_incorrecto_es_rechazado(String valor) {
        assertThatIllegalArgumentException().isThrownBy(() -> new Cedula(valor));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "1' OR '1'='1",
        "'; DROP TABLE t;--",
        "<script>alert(1)</script>",
        "${7*7}"
    })
    void payloads_inyeccion_son_rechazados(String payload) {
        assertThatIllegalArgumentException().isThrownBy(() -> new Cedula(payload));
    }
}
