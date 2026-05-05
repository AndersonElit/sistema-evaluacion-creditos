package com.mscreditevaluation;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import static com.github.tomakehurst.wiremock.client.WireMock.*;

public class WireMockSetup {

    public static WireMockServer start() {
        var server = new WireMockServer(WireMockConfiguration.options().port(9090));
        server.start();
        stubRiskAprobado(server);
        return server;
    }

    public static void stubRiskAprobado(WireMockServer server) {
        server.stubFor(get(urlPathMatching("/v1/risk/score/.*"))
                .willReturn(okJson("""
                    {"cedula":"1713175071","score":85,"timestamp":"2026-05-05T14:30:00Z"}
                """)));
        server.stubFor(get(urlPathMatching("/v1/risk/debts/.*"))
                .willReturn(okJson("""
                    {"cedula":"1713175071","deudas":[],"totalMensual":200.00,"timestamp":"2026-05-05T14:30:00Z"}
                """)));
    }

    public static void stubRiskNoDisponible(WireMockServer server) {
        server.stubFor(get(urlPathMatching("/v1/risk/score/.*"))
                .willReturn(serverError()));
        server.stubFor(get(urlPathMatching("/v1/risk/debts/.*"))
                .willReturn(serverError()));
    }
}
