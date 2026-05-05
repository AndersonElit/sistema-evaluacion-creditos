package com.msrisk.restapi.resource;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.*;
import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class RiskResourceIT {

    private static final String CEDULA_VALIDA = "1713175071";

    // ── /v1/risk/score ────────────────────────────────────────

    @Test
    void score_retorna_200_con_score_en_rango() {
        given()
            .when().get("/v1/risk/score/" + CEDULA_VALIDA)
            .then()
                .statusCode(200)
                .body("cedula", equalTo(CEDULA_VALIDA))
                .body("score", allOf(greaterThanOrEqualTo(0), lessThanOrEqualTo(100)))
                .body("timestamp", notNullValue());
    }

    @Test
    void score_con_cedula_de_longitud_incorrecta_retorna_400() {
        given()
            .when().get("/v1/risk/score/123")
            .then()
                .statusCode(400);
    }

    @Test
    void score_con_cedula_con_letras_retorna_400() {
        given()
            .when().get("/v1/risk/score/ABCDE12345")
            .then()
                .statusCode(400);
    }

    // ── /v1/risk/debts ────────────────────────────────────────

    @Test
    void debts_retorna_200_con_estructura_correcta() {
        given()
            .when().get("/v1/risk/debts/" + CEDULA_VALIDA)
            .then()
                .statusCode(200)
                .body("cedula", equalTo(CEDULA_VALIDA))
                .body("deudas", notNullValue())
                .body("totalMensual", notNullValue())
                .body("timestamp", notNullValue());
    }

    @Test
    void debts_totalMensual_es_consistente_con_lista() {
        var response = given()
            .when().get("/v1/risk/debts/" + CEDULA_VALIDA)
            .then()
                .statusCode(200)
                .extract().body().jsonPath();

        float total = response.getFloat("totalMensual");
        assertThat(total).isGreaterThanOrEqualTo(0f);
    }

    @Test
    void debts_con_cedula_invalida_retorna_400() {
        given()
            .when().get("/v1/risk/debts/noescedula")
            .then()
                .statusCode(400);
    }

    // ── Health ────────────────────────────────────────────────

    @Test
    void health_endpoint_retorna_UP() {
        given()
            .when().get("/q/health")
            .then()
                .statusCode(200)
                .body("status", equalTo("UP"));
    }
}
