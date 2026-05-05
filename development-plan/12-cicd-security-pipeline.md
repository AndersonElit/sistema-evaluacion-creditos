# Paso 12 — CI/CD Security Pipeline

## Objetivo
Configurar el pipeline de seguridad automatizado basado en el modelo SDD del proyecto:
pre-commit hook local con GitLeaks, análisis estático con SonarQube, escaneo de
dependencias con OWASP Dependency Check, escaneo de imagen Docker con Trivy,
y branch protection rules que bloquean PRs que no superen los security gates.

## Prerrequisitos
- Pasos 03–10 completados (todos los microservicios y frontend implementados)
- Paso 11 completado (Dockerfiles y docker-compose disponibles)
- Repositorio en GitHub con rama `main` protegida
- SonarQube corriendo (puede ser SonarCloud free tier o instancia local)

---

## 1. Pre-commit hook local — GitLeaks

Instalar GitLeaks para detectar secretos antes de cada commit:

```bash
# macOS
brew install gitleaks

# Linux
curl -sSfL https://raw.githubusercontent.com/gitleaks/gitleaks/main/scripts/install.sh | sh -s -- -b /usr/local/bin

# Verificar instalación
gitleaks version
```

Registrar el hook en el repositorio:

```bash
# .git/hooks/pre-commit
cat > .git/hooks/pre-commit << 'EOF'
#!/bin/sh
gitleaks protect --staged --verbose
if [ $? -ne 0 ]; then
  echo "[BLOQUEADO] Secretos detectados en el diff. Revisa los hallazgos antes de hacer commit."
  exit 1
fi
EOF
chmod +x .git/hooks/pre-commit
```

Verificación manual (escaneo completo del repositorio):

```bash
gitleaks detect --source . --verbose
# "leaks found: 0" es el resultado esperado
```

---

## 2. `sonar-project.properties` — raíz del repositorio

```properties
sonar.projectKey=sistema-evaluacion-creditos
sonar.projectName=Sistema de Evaluación de Créditos
sonar.sources=src/main/java
sonar.tests=src/test/java
sonar.java.binaries=target/classes
sonar.coverage.jacoco.xmlReportPaths=target/site/jacoco/jacoco.xml

# Reglas de seguridad OWASP Top 10 habilitadas
# java:S2076  — OS command injection
# java:S2077  — SQL injection via string formatting
# java:S2078  — LDAP injection
# java:S5131  — XSS via HTTP response
# java:S5145  — Log injection
# java:S5167  — HTTP response splitting
# java:S6096  — Path traversal
```

---

## 3. OWASP Dependency Check — root `pom.xml`

Agregar el plugin en la sección `<build><plugins>` del POM raíz:

```xml
<plugin>
    <groupId>org.owasp</groupId>
    <artifactId>dependency-check-maven</artifactId>
    <version>10.0.3</version>
    <configuration>
        <failBuildOnCVSS>7</failBuildOnCVSS>
        <suppressionFiles>
            <suppressionFile>owasp-suppressions.xml</suppressionFile>
        </suppressionFiles>
        <formats>
            <format>HTML</format>
            <format>JSON</format>
        </formats>
    </configuration>
</plugin>
```

### `owasp-suppressions.xml` — raíz del repositorio

Archivo para suprimir falsos positivos con justificación obligatoria:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<suppressions xmlns="https://jeremylong.github.io/DependencyCheck/dependency-suppression.1.3.xsd">
    <!--
    Ejemplo de supresión justificada:
    <suppress>
        <notes>CVE-XXXX-YYYY: no aplica porque usamos la ruta de código X, no Y.
               Revisado por: [nombre] en [fecha]. Re-evaluar en [fecha].</notes>
        <cve>CVE-XXXX-YYYY</cve>
    </suppress>
    -->
</suppressions>
```

---

## 4. GitHub Actions — `.github/workflows/security.yml`

```yaml
name: Security Gates

on:
  push:
    branches: [main, develop]
  pull_request:
    branches: [main]

jobs:
  secret-scan:
    name: Secret Scanning — GitLeaks
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with:
          fetch-depth: 0
      - name: GitLeaks
        uses: gitleaks/gitleaks-action@v2
        env:
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}

  sast:
    name: SAST — SonarQube
    runs-on: ubuntu-latest
    needs: secret-scan
    steps:
      - uses: actions/checkout@v4
        with:
          fetch-depth: 0
      - uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'
      - name: Cache Maven packages
        uses: actions/cache@v4
        with:
          path: ~/.m2
          key: ${{ runner.os }}-m2-${{ hashFiles('**/pom.xml') }}
      - name: Build & Analyze
        env:
          SONAR_TOKEN: ${{ secrets.SONAR_TOKEN }}
          SONAR_HOST_URL: ${{ secrets.SONAR_HOST_URL }}
        run: mvn verify sonar:sonar -Dsonar.qualitygate.wait=true

  dependency-check:
    name: Dependency Vulnerabilities — OWASP
    runs-on: ubuntu-latest
    needs: secret-scan
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'
      - name: Cache Maven packages
        uses: actions/cache@v4
        with:
          path: ~/.m2
          key: ${{ runner.os }}-m2-${{ hashFiles('**/pom.xml') }}
      - name: OWASP Dependency Check
        run: mvn dependency-check:check -DfailBuildOnCVSS=7
      - name: Upload report
        uses: actions/upload-artifact@v4
        if: always()
        with:
          name: dependency-check-report
          path: target/dependency-check-report.html

  image-scan:
    name: Docker Image Scan — Trivy
    runs-on: ubuntu-latest
    needs: sast
    strategy:
      matrix:
        service: [ms-risk, ms-credit-evaluation, ms-notifications]
    steps:
      - uses: actions/checkout@v4
      - name: Build image
        run: |
          docker build -t ${{ matrix.service }}:${{ github.sha }} \
            -f ${{ matrix.service }}/Dockerfile .
      - name: Trivy scan
        uses: aquasecurity/trivy-action@master
        with:
          image-ref: ${{ matrix.service }}:${{ github.sha }}
          severity: HIGH,CRITICAL
          exit-code: '1'
          format: table

  # Gates: el PR no puede fusionarse si alguno de los jobs anteriores falla
```

### Secrets de GitHub requeridos

Configurar en **Settings → Secrets and variables → Actions**:

| Secret | Descripción |
|--------|-------------|
| `SONAR_TOKEN` | Token de autenticación en SonarQube/SonarCloud |
| `SONAR_HOST_URL` | URL del servidor SonarQube (ej. `https://sonarcloud.io`) |

---

## 5. Branch protection rules — GitHub UI

Ir a **Settings → Branches → Add rule** para la rama `main`:

```
Branch name pattern: main

✅ Require a pull request before merging
   ✅ Require approvals: 1

✅ Require status checks to pass before merging
   ✅ Require branches to be up to date before merging
   Status checks requeridos:
   - Secret Scanning — GitLeaks
   - SAST — SonarQube
   - Dependency Vulnerabilities — OWASP
   - Docker Image Scan — Trivy (ms-risk)
   - Docker Image Scan — Trivy (ms-credit-evaluation)
   - Docker Image Scan — Trivy (ms-notifications)

✅ Do not allow bypassing the above settings
```

---

## 6. Security Gate — Criterios de bloqueo de PR

| Gate | Bloquea PR | Condición |
|------|-----------|-----------|
| Secreto detectado | Siempre | Cualquier secreto detectado por GitLeaks |
| CVE crítico en dependencia | Siempre | CVSS ≥ 9.0 sin supresión justificada |
| CVE alto en dependencia | Siempre | CVSS ≥ 7.0 sin supresión justificada |
| Quality Gate SonarQube | Siempre | Cobertura < umbral o issue de seguridad nuevo |
| CVE crítico en imagen Docker | Siempre | Trivy detecta CRITICAL en imagen |
| CVE alto en imagen Docker | Warning | Trivy detecta HIGH — requiere revisión manual |

---

## 7. Verificación local

Ejecutar cada herramienta localmente antes de abrir un PR:

```bash
# 1. Secret scanning
gitleaks detect --source . --verbose

# 2. OWASP Dependency Check (requiere Maven y conexión a NVD)
mvn org.owasp:dependency-check-maven:check \
  -DfailBuildOnCVSS=7 \
  -DsuppressionFiles=owasp-suppressions.xml
# Reporte en: target/dependency-check-report.html

# 3. Trivy — escaneo de imagen (requiere Docker)
docker build -t ms-credit-evaluation:local ms-credit-evaluation/
trivy image ms-credit-evaluation:local --severity HIGH,CRITICAL --exit-code 1

docker build -t ms-notifications:local ms-notifications/
trivy image ms-notifications:local --severity HIGH,CRITICAL --exit-code 1

docker build -t ms-risk:local ms-risk/
trivy image ms-risk:local --severity HIGH,CRITICAL --exit-code 1

# 4. SAST local (requiere SONAR_TOKEN configurado)
mvn verify sonar:sonar \
  -Dsonar.host.url=$SONAR_HOST_URL \
  -Dsonar.token=$SONAR_TOKEN \
  -Dsonar.qualitygate.wait=true
```

---

## Security Acceptance Criteria (SDD §8)

Una funcionalidad está **done** desde la perspectiva de seguridad cuando cumple todos:

```
[ ] Escenarios BDD de seguridad pasan: sin JWT → 401, rol incorrecto → 403, input inválido → 422
[ ] No existen CVEs CVSS ≥ 7 sin suprimir en las dependencias del servicio modificado
[ ] El análisis SAST no introduce nuevos issues tipo Bug o Vulnerability
[ ] No hay secretos en el diff del PR (GitLeaks pasa sin alertas)
[ ] Los logs del servicio no contienen PII en plaintext para los nuevos flujos
[ ] El endpoint nuevo/modificado tiene @RolesAllowed o @Authenticated explícito
[ ] Los datos de entrada del endpoint nuevo/modificado tienen @Valid
[ ] La imagen Docker del servicio no tiene vulnerabilidades CRITICAL
[ ] Si la funcionalidad agrega superficie de ataque nueva, el STRIDE fue actualizado
```

---

## Estado esperado al finalizar
- [ ] `gitleaks detect` pasa sin hallazgos en todo el repositorio
- [ ] Pre-commit hook instalado y activo en el entorno local
- [ ] `sonar-project.properties` en la raíz del repositorio
- [ ] Plugin OWASP Dependency Check en root `pom.xml`
- [ ] `owasp-suppressions.xml` creado (vacío o con supresiones justificadas)
- [ ] `.github/workflows/security.yml` con los 4 jobs (secret-scan, sast, dependency-check, image-scan)
- [ ] Secrets `SONAR_TOKEN` y `SONAR_HOST_URL` configurados en GitHub
- [ ] Branch protection rules activas en `main` con los 6 status checks requeridos
- [ ] `mvn dependency-check:check` pasa sin CVEs ≥ 7 sin suprimir
- [ ] Trivy no reporta CRITICAL en ninguna de las 3 imágenes Docker
