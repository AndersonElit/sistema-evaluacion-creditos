package com.msrisk.postgres.repository;

import io.smallrye.mutiny.helpers.test.UniAssertSubscriber;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.*;

class MockRiskAdapterTest {

    private final MockRiskAdapter adapter = new MockRiskAdapter();

    @RepeatedTest(5)
    void getScore_siempre_retorna_valor_entre_0_y_100() {
        Integer score = adapter.getScore("1713175071")
                .await().atMost(Duration.ofSeconds(5));
        assertThat(score).isBetween(0, 100);
    }

    @Test
    void getProfile_retorna_cedula_correcta() {
        var profile = adapter.getProfile("1713175071")
                .await().atMost(Duration.ofSeconds(5));
        assertThat(profile.cedula()).isEqualTo("1713175071");
    }

    @Test
    void getProfile_lista_de_deudas_no_es_nula() {
        var profile = adapter.getProfile("1713175071")
                .await().atMost(Duration.ofSeconds(5));
        assertThat(profile.debts()).isNotNull();
    }

    @Test
    void getProfile_totalMonthlyDebt_es_suma_exacta_de_deudas_individuales() {
        var profile = adapter.getProfile("1713175071")
                .await().atMost(Duration.ofSeconds(5));
        var sumaManual = profile.debts().stream()
                .map(d -> d.monthlyPayment())
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
        assertThat(profile.totalMonthlyDebt()).isEqualByComparingTo(sumaManual);
    }

    @Test
    void getScore_retorna_uni_no_nulo() {
        UniAssertSubscriber<Integer> sub = adapter.getScore("0912345678")
                .subscribe().withSubscriber(UniAssertSubscriber.create());
        sub.awaitItem(Duration.ofSeconds(5));
        assertThat(sub.getItem()).isBetween(0, 100);
    }
}
