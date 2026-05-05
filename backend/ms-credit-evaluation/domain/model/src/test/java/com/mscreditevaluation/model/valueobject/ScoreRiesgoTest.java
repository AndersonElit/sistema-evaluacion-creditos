package com.mscreditevaluation.model.valueobject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.*;

class ScoreRiesgoTest {

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 70, 71, 99, 100})
    void scores_en_rango_son_aceptados(int valor) {
        assertThatNoException().isThrownBy(() -> new ScoreRiesgo(valor));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 101, Integer.MAX_VALUE})
    void scores_fuera_de_rango_son_rechazados(int valor) {
        assertThatIllegalArgumentException().isThrownBy(() -> new ScoreRiesgo(valor));
    }

    @Test
    void score_71_es_suficiente() {
        assertThat(new ScoreRiesgo(71).esSuficiente()).isTrue();
    }

    @Test
    void score_70_NO_es_suficiente_limite_estricto() {
        assertThat(new ScoreRiesgo(70).esSuficiente()).isFalse();
    }

    @Test
    void score_0_no_es_suficiente() {
        assertThat(new ScoreRiesgo(0).esSuficiente()).isFalse();
    }

    @Test
    void score_100_es_suficiente() {
        assertThat(new ScoreRiesgo(100).esSuficiente()).isTrue();
    }
}
