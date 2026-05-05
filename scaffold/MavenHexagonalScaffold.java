///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS info.picocli:picocli:4.7.5
//JAVA 17+

import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "MavenHexagonalScaffold", mixinStandardHelpOptions = true, version = "2.0",
        description = "Genera un proyecto base Quarkus Reactivo con Mutiny multimódulo.")
public class MavenHexagonalScaffold implements Runnable {

    @Option(names = {"-n", "--service-name"},
            description = "Nombre del microservicio",
            defaultValue = "mi-microservicio",
            required = true)
    private String projectName;

    @Option(names = {"-m", "--messaging-system"},
            description = "Sistema de mensajeria a configurar: sqs-producer, sqs-consumer",
            defaultValue = "none")
    private String messagingSystem;

    public static void main(String... args) {
        int exitCode = new CommandLine(new MavenHexagonalScaffold()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public void run() {
        try {
            String safeProjectName = projectName.replace("-", "");
            Path rootPath = Paths.get(projectName);
            System.out.println("[INFO] Creando proyecto: " + projectName);

            List<String> modules = new java.util.ArrayList<>(List.of(
                    "domain/model",
                    "application/use-cases",
                    "infrastructure/driven-adapters/postgres",
                    "infrastructure/entry-points/rest-api",
                    "infrastructure/entry-points/app"
            ));

            if ("sqs-producer".equalsIgnoreCase(messagingSystem)) {
                modules.add("infrastructure/driven-adapters/sqs-producer");
            } else if ("sqs-consumer".equalsIgnoreCase(messagingSystem)) {
                modules.add("infrastructure/entry-points/sqs-consumer");
            }

            for (String module : modules) {
                String moduleName = module.substring(module.lastIndexOf("/") + 1).replace("-", "");
                String basePackage = "com." + safeProjectName + "." + moduleName;
                String packagePath = "/src/main/java/" + basePackage.replace(".", "/");
                Files.createDirectories(rootPath.resolve(module + packagePath));

                String modulePom = getModulePomTemplate(projectName, safeProjectName, module);
                Files.writeString(rootPath.resolve(module + "/pom.xml"), modulePom);

                if (module.equals("infrastructure/entry-points/rest-api")) {
                    String helloResource = String.format("""
package %s;

import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/hello")
public class HelloResource {

    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public Uni<String> hello() {
        return Uni.createFrom().item("¡Hola desde el scaffold Hexagonal Reactivo con Quarkus!");
    }
}
""", basePackage);
                    Files.writeString(rootPath.resolve(module + packagePath + "/HelloResource.java"), helloResource);
                }

                if (module.equals("infrastructure/entry-points/app")) {
                    String mainPackage = "com." + safeProjectName;
                    String mainClassPath = "/src/main/java/" + mainPackage.replace(".", "/");
                    Files.createDirectories(rootPath.resolve(module + mainClassPath));

                    String mainClass = String.format("""
package %s;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.annotations.QuarkusMain;

@QuarkusMain
public class MainApplication {
    public static void main(String... args) {
        Quarkus.run(args);
    }
}
""", mainPackage);
                    Files.writeString(rootPath.resolve(module + mainClassPath + "/MainApplication.java"), mainClass);

                    String beanConfig = String.format("""
package %s;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

@ApplicationScoped
public class BeanConfig {
    // Wire domain use cases with infrastructure adapters here using @Produces/@Inject
    // Example:
    // @Inject SomePort somePort;
    // @Produces @ApplicationScoped
    // public SomeUseCase someUseCase() { return new SomeUseCase(somePort); }
}
""", mainPackage);
                    Files.writeString(rootPath.resolve(module + mainClassPath + "/BeanConfig.java"), beanConfig);

                    Path resourcesPath = rootPath.resolve(module + "/src/main/resources");
                    Files.createDirectories(resourcesPath);
                    Files.writeString(resourcesPath.resolve("application.properties"), getAppPropertiesContent());
                }
            }

            if ("sqs-producer".equalsIgnoreCase(messagingSystem)) {
                createSqsProducerFiles(rootPath, safeProjectName);
            } else if ("sqs-consumer".equalsIgnoreCase(messagingSystem)) {
                createSqsConsumerFiles(rootPath, safeProjectName);
            }

            Files.writeString(rootPath.resolve(".env"), getEnvContent());
            Files.writeString(rootPath.resolve(".env.example"), getEnvExampleContent());

            String gitIgnore = """
target/
!.mvn/wrapper/maven-wrapper.jar
*.class
*.log
*.ctxt
.mtj.tmp/
*.jar
*.war
*.ear
*.zip
*.tar.gz
*.rar
hs_err_pid*
.idea/
*.iml
.classpath
.project
.settings/
bin/
.vscode/
.env
""";
            Files.writeString(rootPath.resolve(".gitignore"), gitIgnore);

            String pomContent = getRootPomTemplate(projectName, messagingSystem);
            Files.writeString(rootPath.resolve("pom.xml"), pomContent);

            System.out.println("[SUCCESS] Proyecto creado en: " + rootPath.toAbsolutePath());

        } catch (IOException e) {
            System.err.println("[ERROR] No se pudo crear el proyecto: " + e.getMessage());
        }
    }

    private String getAppPropertiesContent() {
        StringBuilder props = new StringBuilder();

        props.append("# HTTP\n");
        props.append("quarkus.http.port=${SERVER_PORT:8080}\n\n");

        props.append("# DataSource - PostgreSQL Reactive\n");
        props.append("quarkus.datasource.db-kind=postgresql\n");
        props.append("quarkus.datasource.username=${DB_USERNAME}\n");
        props.append("quarkus.datasource.password=${DB_PASSWORD}\n");
        props.append("quarkus.datasource.reactive.url=${DB_REACTIVE_URL}\n");
        props.append("quarkus.hibernate-orm.database.generation=validate\n\n");

        props.append("# Keycloak OIDC - Token validation\n");
        props.append("quarkus.oidc.auth-server-url=${KEYCLOAK_URL}/realms/${KEYCLOAK_REALM}\n");
        props.append("quarkus.oidc.client-id=${KEYCLOAK_CLIENT_ID}\n");
        props.append("quarkus.oidc.application-type=service\n");
        props.append("quarkus.http.auth.permission.authenticated.paths=/*\n");
        props.append("quarkus.http.auth.permission.authenticated.policy=authenticated\n\n");

        if ("sqs-producer".equalsIgnoreCase(messagingSystem) || "sqs-consumer".equalsIgnoreCase(messagingSystem)) {
            props.append("# AWS SQS\n");
            props.append("quarkus.sqs.aws.region=${AWS_REGION}\n");
            props.append("sqs.queue.url=${SQS_QUEUE_URL}\n");
            props.append("# sqs.endpoint.override=${SQS_ENDPOINT_URL:http://localhost:4566}\n\n");
        }

        return props.toString();
    }

    private String getEnvContent() {
        StringBuilder env = new StringBuilder();
        env.append("# ===================================================================\n");
        env.append("# Environment Variables - ").append(projectName).append("\n");
        env.append("# ===================================================================\n");
        env.append("# IMPORTANT: This file contains sensitive credentials.\n");
        env.append("# DO NOT commit this file to version control.\n");
        env.append("# Copy .env.example to .env and fill in the actual values.\n");
        env.append("# ===================================================================\n\n");

        env.append("# Server\n");
        env.append("SERVER_PORT=8080\n\n");

        env.append("# Database - PostgreSQL Reactive\n");
        env.append("DB_REACTIVE_URL=postgresql://localhost:5432/mydb\n");
        env.append("DB_USERNAME=postgres\n");
        env.append("DB_PASSWORD=password\n\n");

        env.append("# Keycloak\n");
        env.append("KEYCLOAK_URL=http://localhost:8180\n");
        env.append("KEYCLOAK_REALM=my-realm\n");
        env.append("KEYCLOAK_CLIENT_ID=my-client\n");

        if ("sqs-producer".equalsIgnoreCase(messagingSystem) || "sqs-consumer".equalsIgnoreCase(messagingSystem)) {
            env.append("\n# AWS SQS\n");
            env.append("AWS_REGION=us-east-1\n");
            env.append("AWS_ACCESS_KEY_ID=your-access-key\n");
            env.append("AWS_SECRET_ACCESS_KEY=your-secret-key\n");
            env.append("SQS_QUEUE_URL=https://sqs.us-east-1.amazonaws.com/123456789/my-queue\n");
            env.append("# For local dev with LocalStack:\n");
            env.append("# SQS_ENDPOINT_URL=http://localhost:4566\n");
        }

        return env.toString();
    }

    private String getEnvExampleContent() {
        StringBuilder env = new StringBuilder();
        env.append("# ===================================================================\n");
        env.append("# Environment Variables Template - ").append(projectName).append("\n");
        env.append("# ===================================================================\n");
        env.append("# Copy this file to .env and fill in the actual values.\n");
        env.append("# ===================================================================\n\n");

        env.append("# Server\n");
        env.append("SERVER_PORT=8080\n\n");

        env.append("# Database - PostgreSQL Reactive\n");
        env.append("DB_REACTIVE_URL=postgresql://localhost:5432/mydb\n");
        env.append("DB_USERNAME=\n");
        env.append("DB_PASSWORD=\n\n");

        env.append("# Keycloak\n");
        env.append("KEYCLOAK_URL=\n");
        env.append("KEYCLOAK_REALM=\n");
        env.append("KEYCLOAK_CLIENT_ID=\n");

        if ("sqs-producer".equalsIgnoreCase(messagingSystem) || "sqs-consumer".equalsIgnoreCase(messagingSystem)) {
            env.append("\n# AWS SQS\n");
            env.append("AWS_REGION=us-east-1\n");
            env.append("AWS_ACCESS_KEY_ID=\n");
            env.append("AWS_SECRET_ACCESS_KEY=\n");
            env.append("SQS_QUEUE_URL=\n");
            env.append("# SQS_ENDPOINT_URL=http://localhost:4566\n");
        }

        return env.toString();
    }

    private String getRootPomTemplate(String name, String messaging) {
        StringBuilder modulesSection = new StringBuilder();
        modulesSection.append("        <module>domain/model</module>\n");
        modulesSection.append("        <module>application/use-cases</module>\n");
        modulesSection.append("        <module>infrastructure/driven-adapters/postgres</module>\n");
        modulesSection.append("        <module>infrastructure/entry-points/rest-api</module>\n");
        modulesSection.append("        <module>infrastructure/entry-points/app</module>\n");
        if ("sqs-producer".equalsIgnoreCase(messaging)) {
            modulesSection.append("        <module>infrastructure/driven-adapters/sqs-producer</module>\n");
        } else if ("sqs-consumer".equalsIgnoreCase(messaging)) {
            modulesSection.append("        <module>infrastructure/entry-points/sqs-consumer</module>\n");
        }

        return """
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <groupId>com.%s</groupId>
    <artifactId>%s</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <packaging>pom</packaging>

    <properties>
        <java.version>21</java.version>
        <maven.compiler.source>21</maven.compiler.source>
        <maven.compiler.target>21</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <quarkus.platform.version>3.17.4</quarkus.platform.version>
        <quarkus.platform.group-id>io.quarkus.platform</quarkus.platform.group-id>
        <quarkus-amazon-services.version>2.17.0</quarkus-amazon-services.version>
    </properties>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>${quarkus.platform.group-id}</groupId>
                <artifactId>quarkus-bom</artifactId>
                <version>${quarkus.platform.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
            <dependency>
                <groupId>io.quarkiverse.amazonservices</groupId>
                <artifactId>quarkus-amazon-services-bom</artifactId>
                <version>${quarkus-amazon-services.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <modules>
%s    </modules>

    <dependencies>
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <optional>true</optional>
        </dependency>
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-junit5</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <pluginManagement>
            <plugins>
                <plugin>
                    <groupId>io.quarkus.platform</groupId>
                    <artifactId>quarkus-maven-plugin</artifactId>
                    <version>${quarkus.platform.version}</version>
                </plugin>
            </plugins>
        </pluginManagement>
    </build>
</project>
""".formatted(name.replace("-", ""), name, modulesSection.toString());
    }

    private String getModulePomTemplate(String parentArtifactId, String safeProjectName, String modulePath) {
        String moduleArtifactId = modulePath.replace("/", "-");
        String modulePackageName = modulePath.substring(modulePath.lastIndexOf("/") + 1).replace("-", "");
        boolean isDbAdapter = modulePath.startsWith("infrastructure/driven-adapters/");
        boolean isEntryPoints = modulePath.startsWith("infrastructure/entry-points/");
        boolean isInfrastructure = isDbAdapter || isEntryPoints;
        String relativePath = isInfrastructure ? "../../../pom.xml" : "../../pom.xml";
        StringBuilder sb = new StringBuilder();

        sb.append("""
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.%s</groupId>
        <artifactId>%s</artifactId>
        <version>1.0.0-SNAPSHOT</version>
        <relativePath>%s</relativePath>
    </parent>
    <groupId>com.%s.%s</groupId>
    <artifactId>%s</artifactId>
    <dependencies>
""".formatted(safeProjectName, parentArtifactId, relativePath, safeProjectName, modulePackageName, moduleArtifactId));

        if (modulePath.equals("application/use-cases")) {
            sb.append("""
        <dependency>
            <groupId>io.smallrye.reactive</groupId>
            <artifactId>mutiny</artifactId>
        </dependency>
""");
        } else if (modulePath.endsWith("/postgres")) {
            sb.append("""
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-hibernate-reactive-panache</artifactId>
        </dependency>
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-reactive-pg-client</artifactId>
        </dependency>
""");
        } else if (modulePath.endsWith("/sqs-producer")) {
            sb.append("""
        <dependency>
            <groupId>io.quarkiverse.amazonservices</groupId>
            <artifactId>quarkus-amazon-sqs</artifactId>
        </dependency>
        <dependency>
            <groupId>software.amazon.awssdk</groupId>
            <artifactId>url-connection-client</artifactId>
        </dependency>
""");
        } else if (modulePath.equals("infrastructure/entry-points/rest-api")) {
            sb.append("""
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-resteasy-reactive-jackson</artifactId>
        </dependency>
""");
        } else if (modulePath.equals("infrastructure/entry-points/app")) {
            sb.append(String.format("""
        <dependency>
            <groupId>com.%s.restapi</groupId>
            <artifactId>infrastructure-entry-points-rest-api</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-oidc</artifactId>
        </dependency>
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-smallrye-health</artifactId>
        </dependency>
""", safeProjectName));
        } else if (modulePath.equals("infrastructure/entry-points/sqs-consumer")) {
            sb.append("""
        <dependency>
            <groupId>io.quarkiverse.amazonservices</groupId>
            <artifactId>quarkus-amazon-sqs</artifactId>
        </dependency>
        <dependency>
            <groupId>software.amazon.awssdk</groupId>
            <artifactId>url-connection-client</artifactId>
        </dependency>
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-scheduler</artifactId>
        </dependency>
""");
        }

        sb.append("    </dependencies>\n");

        if (modulePath.equals("infrastructure/entry-points/app")) {
            sb.append("""
    <build>
        <plugins>
            <plugin>
                <groupId>io.quarkus.platform</groupId>
                <artifactId>quarkus-maven-plugin</artifactId>
                <extensions>true</extensions>
                <executions>
                    <execution>
                        <goals>
                            <goal>build</goal>
                            <goal>generate-code</goal>
                            <goal>generate-code-tests</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
""");
        }

        sb.append("</project>\n");
        return sb.toString();
    }

    private void createSqsProducerFiles(Path rootPath, String safeProjectName) throws IOException {
        String modulePath = "infrastructure/driven-adapters/sqs-producer";
        String moduleName = "sqsproducer";
        String basePackage = "com." + safeProjectName + "." + moduleName;
        String packagePath = "/src/main/java/" + basePackage.replace(".", "/");

        String publisher = "package " + basePackage + ";\n\n" +
            "import io.smallrye.mutiny.Uni;\n" +
            "import jakarta.enterprise.context.ApplicationScoped;\n" +
            "import jakarta.inject.Inject;\n" +
            "import org.eclipse.microprofile.config.inject.ConfigProperty;\n" +
            "import software.amazon.awssdk.services.sqs.SqsAsyncClient;\n" +
            "import software.amazon.awssdk.services.sqs.model.SendMessageRequest;\n" +
            "import software.amazon.awssdk.services.sqs.model.SendMessageResponse;\n\n" +
            "@ApplicationScoped\n" +
            "public class SqsMessagePublisher {\n\n" +
            "    @Inject\n" +
            "    SqsAsyncClient sqsClient;\n\n" +
            "    @ConfigProperty(name = \"sqs.queue.url\")\n" +
            "    String queueUrl;\n\n" +
            "    public Uni<SendMessageResponse> publish(String messageBody) {\n" +
            "        SendMessageRequest request = SendMessageRequest.builder()\n" +
            "                .queueUrl(queueUrl)\n" +
            "                .messageBody(messageBody)\n" +
            "                .build();\n" +
            "        return Uni.createFrom().completionStage(() -> sqsClient.sendMessage(request));\n" +
            "    }\n" +
            "}\n";
        Files.writeString(rootPath.resolve(modulePath + packagePath + "/SqsMessagePublisher.java"), publisher);
    }

    private void createSqsConsumerFiles(Path rootPath, String safeProjectName) throws IOException {
        String modulePath = "infrastructure/entry-points/sqs-consumer";
        String moduleName = "sqsconsumer";
        String basePackage = "com." + safeProjectName + "." + moduleName;
        String packagePath = "/src/main/java/" + basePackage.replace(".", "/");

        String consumer = "package " + basePackage + ";\n\n" +
            "import io.quarkus.scheduler.Scheduled;\n" +
            "import io.smallrye.mutiny.Uni;\n" +
            "import jakarta.enterprise.context.ApplicationScoped;\n" +
            "import jakarta.inject.Inject;\n" +
            "import org.eclipse.microprofile.config.inject.ConfigProperty;\n" +
            "import org.slf4j.Logger;\n" +
            "import org.slf4j.LoggerFactory;\n" +
            "import software.amazon.awssdk.services.sqs.SqsAsyncClient;\n" +
            "import software.amazon.awssdk.services.sqs.model.*;\n\n" +
            "@ApplicationScoped\n" +
            "public class SqsMessageConsumer {\n\n" +
            "    private static final Logger log = LoggerFactory.getLogger(SqsMessageConsumer.class);\n\n" +
            "    @Inject\n" +
            "    SqsAsyncClient sqsClient;\n\n" +
            "    @ConfigProperty(name = \"sqs.queue.url\")\n" +
            "    String queueUrl;\n\n" +
            "    @Scheduled(every = \"5s\")\n" +
            "    public Uni<Void> poll() {\n" +
            "        ReceiveMessageRequest request = ReceiveMessageRequest.builder()\n" +
            "                .queueUrl(queueUrl)\n" +
            "                .maxNumberOfMessages(10)\n" +
            "                .waitTimeSeconds(1)\n" +
            "                .build();\n\n" +
            "        return Uni.createFrom()\n" +
            "                .completionStage(() -> sqsClient.receiveMessage(request))\n" +
            "                .chain(response -> {\n" +
            "                    var entries = response.messages().stream()\n" +
            "                            .map(msg -> {\n" +
            "                                log.info(\"Mensaje recibido: {}\", msg.body());\n" +
            "                                return DeleteMessageBatchRequestEntry.builder()\n" +
            "                                        .id(msg.messageId())\n" +
            "                                        .receiptHandle(msg.receiptHandle())\n" +
            "                                        .build();\n" +
            "                            })\n" +
            "                            .toList();\n\n" +
            "                    if (entries.isEmpty()) return Uni.createFrom().voidItem();\n\n" +
            "                    return Uni.createFrom()\n" +
            "                            .completionStage(() -> sqsClient.deleteMessageBatch(\n" +
            "                                    DeleteMessageBatchRequest.builder()\n" +
            "                                            .queueUrl(queueUrl)\n" +
            "                                            .entries(entries)\n" +
            "                                            .build()))\n" +
            "                            .replaceWithVoid();\n" +
            "                });\n" +
            "    }\n" +
            "}\n";
        Files.writeString(rootPath.resolve(modulePath + packagePath + "/SqsMessageConsumer.java"), consumer);
    }
}
