package com.msrisk.restapi.dto;

import java.time.Instant;

public record ScoreResponse(String cedula, int score, Instant timestamp) {}
