package com.mscreditevaluation.restapi.resource;

import com.mscreditevaluation.WireMockSetup;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.jwt.Claim;
import io.quarkus.test.security.jwt.JwtSecurity;
import org.junit.jupiter.api.*;

import static io.restassured.RestAssured.*;
import static io.restassured.http.ContentType.JSON;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CreditEvaluationResourceIT {

    static WireMockServer wireMock;

    @BeforeAll
    static void startWireMock() {
        wireMock = WireMockSetup.start();
    }

    @AfterAll
    static void stopWireMock() {
        wireMock.stop();
    }

    private static final String BODY_VALIDO = """
        {
          "cedula": "1713175071",
          "montoSolicitado": 5000.00,
          "plazoAnios": 3,
          "salario": 2000.00,
          "destinatarioEmail": "sol@email.com"
        }
        """;

    // ── Autenticación ─────────────────────────────────────────

    @Test
    void post_sin_token_retorna_401() {
        given().contentType(JSON).body(BODY_VALIDO)
            .when().post("/v1/credit-evaluations")
            .then().statusCode(401);
    }

    @Test
    void get_sin_token_retorna_401() {
        given().when().get("/v1/credit-evaluations")
            .then().statusCode(401);
    }

    // ── Autorización por rol ──────────────────────────────────

    @Test
    @TestSecurity(user = "viewer", roles = "VIEWER")
    @JwtSecurity(claims = {@Claim(key = "groups", value = "VIEWER"),
                           @Claim(key = "email",  value = "viewer@banco.com")})
    void post_con_rol_VIEWER_retorna_403() {
        given().contentType(JSON).body(BODY_VALIDO)
            .when().post("/v1/credit-evaluations")
            .then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "viewer", roles = "VIEWER")
    @JwtSecurity(claims = {@Claim(key = "groups", value = "VIEWER"),
                           @Claim(key = "email",  value = "viewer@banco.com")})
    void get_con_rol_VIEWER_retorna_200() {
        given().when().get("/v1/credit-evaluations")
            .then().statusCode(200);
    }

    // ── Happy path ────────────────────────────────────────────

    @Test
    @Order(1)
    @TestSecurity(user = "analyst", roles = "ANALYST")
    @JwtSecurity(claims = {@Claim(key = "groups", value = "ANALYST"),
                           @Claim(key = "sub",    value = "550e8400-e29b-41d4-a716-446655440000"),
                           @Claim(key = "email",  value = "analyst@banco.com")})
    void post_con_rol_ANALYST_retorna_201_con_estado_evaluacion() {
        given().contentType(JSON).body(BODY_VALIDO)
            .when().post("/v1/credit-evaluations")
            .then()
                .statusCode(201)
                .header("Location", containsString("/v1/credit-evaluations/"))
                .body("id", notNullValue())
                .body("cedula", equalTo("1713175071"))
                .body("estadoFinal", oneOf("APROBADO", "RECHAZADO"))
                .body("scoreRiesgo", allOf(greaterThanOrEqualTo(0), lessThanOrEqualTo(100)));
    }

    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    @JwtSecurity(claims = {@Claim(key = "groups", value = "ADMIN"),
                           @Claim(key = "sub",    value = "660e8400-e29b-41d4-a716-446655440000"),
                           @Claim(key = "email",  value = "admin@banco.com")})
    void post_con_rol_ADMIN_retorna_201() {
        given().contentType(JSON).body(BODY_VALIDO)
            .when().post("/v1/credit-evaluations")
            .then().statusCode(201);
    }

    // ── Validación de entrada ─────────────────────────────────

    @Test
    @TestSecurity(user = "analyst", roles = "ANALYST")
    @JwtSecurity(claims = {@Claim(key = "groups", value = "ANALYST"),
                           @Claim(key = "sub",    value = "550e8400-e29b-41d4-a716-446655440000")})
    void cedula_invalida_retorna_422() {
        given().contentType(JSON)
            .body("""
                {"cedula":"1234567890","montoSolicitado":5000,"plazoAnios":3,"salario":2000}
                """)
            .when().post("/v1/credit-evaluations")
            .then()
                .statusCode(422)
                .body("error", notNullValue())
                .body(not(containsString("Exception")))
                .body(not(containsString("at com.")));
    }

    @Test
    @TestSecurity(user = "analyst", roles = "ANALYST")
    @JwtSecurity(claims = @Claim(key = "groups", value = "ANALYST"))
    void monto_negativo_retorna_422() {
        given().contentType(JSON)
            .body("""
                {"cedula":"1713175071","montoSolicitado":-1,"plazoAnios":3,"salario":2000}
                """)
            .when().post("/v1/credit-evaluations")
            .then().statusCode(422);
    }

    @Test
    @TestSecurity(user = "analyst", roles = "ANALYST")
    @JwtSecurity(claims = @Claim(key = "groups", value = "ANALYST"))
    void salario_cero_retorna_422() {
        given().contentType(JSON)
            .body("""
                {"cedula":"1713175071","montoSolicitado":5000,"plazoAnios":3,"salario":0}
                """)
            .when().post("/v1/credit-evaluations")
            .then().statusCode(422);
    }

    @Test
    @TestSecurity(user = "analyst", roles = "ANALYST")
    @JwtSecurity(claims = @Claim(key = "groups", value = "ANALYST"))
    void plazo_fuera_de_rango_retorna_422() {
        given().contentType(JSON)
            .body("""
                {"cedula":"1713175071","montoSolicitado":5000,"plazoAnios":31,"salario":2000}
                """)
            .when().post("/v1/credit-evaluations")
            .then().statusCode(422);
    }

    @Test
    @TestSecurity(user = "analyst", roles = "ANALYST")
    @JwtSecurity(claims = @Claim(key = "groups", value = "ANALYST"))
    void json_malformado_retorna_400_sin_stack_trace() {
        given().contentType(JSON)
            .body("{ invalid }")
            .when().post("/v1/credit-evaluations")
            .then()
                .statusCode(400)
                .body(not(containsString("NullPointerException")))
                .body(not(containsString("at com.")));
    }

    // ── Resiliencia: ms-risk caído ────────────────────────────

    @Test
    @TestSecurity(user = "analyst", roles = "ANALYST")
    @JwtSecurity(claims = {@Claim(key = "groups", value = "ANALYST"),
                           @Claim(key = "sub",    value = "550e8400-e29b-41d4-a716-446655440000")})
    void risk_service_no_disponible_retorna_503() {
        WireMockSetup.stubRiskNoDisponible(wireMock);

        given().contentType(JSON).body(BODY_VALIDO)
            .when().post("/v1/credit-evaluations")
            .then()
                .statusCode(503)
                .body("error", containsString("riesgos"));

        WireMockSetup.stubRiskAprobado(wireMock);
    }

    // ── Inyección SQL ─────────────────────────────────────────

    @Test
    @TestSecurity(user = "analyst", roles = "ANALYST")
    @JwtSecurity(claims = @Claim(key = "groups", value = "ANALYST"))
    void inyeccion_sql_en_cedula_retorna_422_sin_ejecutar_sql() {
        given().contentType(JSON)
            .body("""
                {"cedula":"1' OR '1'='1","montoSolicitado":5000,"plazoAnios":3,"salario":2000}
                """)
            .when().post("/v1/credit-evaluations")
            .then().statusCode(422);
    }
}
