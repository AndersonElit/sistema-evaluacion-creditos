package com.msnotifications.config;

import org.eclipse.microprofile.config.spi.ConfigSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.SsmClientBuilder;
import software.amazon.awssdk.services.ssm.model.GetParametersByPathRequest;
import software.amazon.awssdk.services.ssm.model.GetParametersByPathResponse;
import software.amazon.awssdk.services.ssm.model.Parameter;

import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * MicroProfile ConfigSource que carga parámetros desde AWS SSM Parameter Store al arrancar.
 * Se activa cuando la variable de entorno SSM_PREFIX está definida.
 * Si SSM_PREFIX no está seteada (tests, build Maven), retorna vacío sin lanzar error.
 */
public class SsmConfigSource implements ConfigSource {

    private static final int ORDINAL = 280;
    private static final String NAME = "aws-ssm-config-source";

    private final Map<String, String> properties;

    public SsmConfigSource() {
        String prefix = System.getenv("SSM_PREFIX");
        if (prefix == null || prefix.isBlank()) {
            this.properties = Collections.emptyMap();
            return;
        }

        String endpoint = System.getenv("AWS_SSM_ENDPOINT");
        String region = Optional.ofNullable(System.getenv("AWS_REGION")).orElse("us-east-1");
        String accessKey = Optional.ofNullable(System.getenv("AWS_ACCESS_KEY_ID")).orElse("test");
        String secretKey = Optional.ofNullable(System.getenv("AWS_SECRET_ACCESS_KEY")).orElse("test");

        SsmClientBuilder builder = SsmClient.builder()
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .httpClientBuilder(UrlConnectionHttpClient.builder());

        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));
        }

        Map<String, String> loaded = new HashMap<>();
        try (SsmClient client = builder.build()) {
            String nextToken = null;
            do {
                GetParametersByPathRequest.Builder req = GetParametersByPathRequest.builder()
                        .path(prefix)
                        .withDecryption(true)
                        .maxResults(10);
                if (nextToken != null) {
                    req.nextToken(nextToken);
                }
                GetParametersByPathResponse response = client.getParametersByPath(req.build());
                for (Parameter param : response.parameters()) {
                    String key = param.name().substring(prefix.length());
                    if (key.startsWith("/")) {
                        key = key.substring(1);
                    }
                    loaded.put(key, param.value());
                }
                nextToken = response.nextToken();
            } while (nextToken != null);
        } catch (Exception e) {
            throw new RuntimeException(
                    "[SsmConfigSource] No se pudo cargar la configuración desde SSM (prefix=" + prefix + "): " + e.getMessage(), e);
        }

        this.properties = Collections.unmodifiableMap(loaded);
        System.out.printf("[SsmConfigSource] %d parámetros cargados desde %s%n", properties.size(), prefix);
    }

    @Override
    public Map<String, String> getProperties() {
        return properties;
    }

    @Override
    public Set<String> getPropertyNames() {
        return properties.keySet();
    }

    @Override
    public String getValue(String propertyName) {
        return properties.get(propertyName);
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public int getOrdinal() {
        return ORDINAL;
    }
}
