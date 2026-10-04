# AI Apprentice — API de Capacitación (Módulo Teach)

Backend en **Java 21 + Spring Boot 3** que recibe la información de cada empresa
(el Work Map que produce el módulo de *recording*) y la convierte en un sistema
de capacitación. Claude dirige la conversación; ElevenLabs solo sintetiza sus respuestas.

**Arranque recomendado:** ejecuta `Main.cmd` en la raíz. Inicia esta API con el perfil
`local` (H2 persistente) y la web conectada. El README de la raíz documenta Studio,
las cuentas, la captura, el debrief, los mapas y la práctica. Las APIs descritas
abajo se conservan para integraciones; Studio no abre un agente ElevenLabs.

Pensado para **miles de empresas**: todas comparten las mismas tablas y cada
registro lleva `tenant_id`. El aislamiento se aplica en tres capas:

1. Filtro HTTP que valida la clave de la empresa (`x-api-key`, guardada como hash SHA-256).
2. Hibernate 6 (`@TenantId`) que filtra automáticamente cada consulta.
3. Row Level Security de Postgres como segunda barrera.

Si una empresa grande necesita su propia base de datos, se le asigna una URL
dedicada en `tenants.db_url` sin cambiar la API.

## Requisitos

- Java 21
- Maven 3.9+
- Postgres 16 (o `docker compose up -d db`)

## Arranque rápido

```bash
docker compose up -d db
export ADMIN_API_KEY="una-clave-fuerte"
export ANTHROPIC_API_KEY="tu-clave-de-anthropic" # sin ella no hay casos generados ni calificacion IA
export ANTHROPIC_MODEL="claude-sonnet-4-6"        # opcional; valor predeterminado
mvn spring-boot:run
```

Con `DEMO_DATA=true` (por defecto) se crea la empresa `demo` (clave `demo-key`)
con el proceso de facturas de Sabine, 6 casos y la alumna Lena.

## Cómo entra cada empresa

El backend **solo recibe información**; no le importa de dónde viene.

### 1. Dar de alta la empresa (una vez, con la clave de administrador)

```bash
curl -X POST http://localhost:8080/api/tenants \
  -H "x-admin-key: $ADMIN_API_KEY" -H "Content-Type: application/json" \
  -d '{"slug": "acme", "name": "Acme SA"}'
# -> { "api_key": "ak_..." }  (se muestra una sola vez)
```

Rotar la clave: `POST /api/tenants/acme/rotate-key` (mismo encabezado admin).

### 2. Enviar el Work Map (lo que produce el recording)

```bash
curl -X POST http://localhost:8080/api/t/acme/ingest/workmaps \
  -H "x-api-key: ak_..." -H "Content-Type: application/json" \
  -d '{
    "title": "Procesar facturas de proveedores",
    "role": "Contabilidad",
    "expert_name": "Sabine",
    "language": "es",
    "steps": [
      {
        "position": 1,
        "title": "Recibir la factura",
        "screen_ts": "00:02:14",
        "screenshot_url": "https://...",
        "decision": "Revisar el buzón de facturas y descargar el PDF",
        "reason_quote": "Siempre empiezo por el buzón compartido",
        "reason_author": "Sabine",
        "reason_ts": "00:02:40",
        "risk": "low",
        "off_record": false
      }
    ],
    "guardrails": [
      {
        "kind": "limit",
        "condition": "la diferencia con el pedido supera 50 euros",
        "correct_action": "No aprobar; escalar al jefe de compras",
        "expert_quote": "Más de 50 euros de diferencia nunca lo apruebo yo",
        "step_position": 3
      }
    ]
  }'
```

`off_record: true` = ese paso o guardrail **nunca** llega al tutor ni a la IA.

### 3. Enviar casos y eventos (opcional)

- `POST /api/t/{slug}/ingest/cases` — caso de práctica con `data` (JSON libre) y `expected` (decisión correcta por paso).
- `POST /api/t/{slug}/ingest/events` — evento de una sesión (analítica).

## API de capacitación

Todo bajo `/api/t/{slug}/...` con `x-api-key`.

| Método y ruta | Qué hace |
|---|---|
| `POST /learners` | Crea alumno (`name`, `external_id`) |
| `GET /learners` | Lista alumnos |
| `GET /workmaps` / `GET /workmaps/{id}` | Lista/exporta Work Maps (sin off_record) |
| `GET /cases?workmap_id=` / `GET /cases/{id}` | Lista/lee casos |
| `POST /cases/generate` | Genera casos nuevos con IA (`workmap_id`, `kind`, `count`) |
| `POST /sessions` | Abre sesión (`learner_id`, `workmap_id`, `case_id?`, `level?`) |
| `GET /sessions/{id}` | Estado de la sesión |
| `GET /learners/{id}/progress?workmap_id=` | Dominio por paso y nivel sugerido |
| `GET /agent-prompt/{workmapId}` | System prompt listo para pegar en ElevenLabs |

### Las 5 herramientas del tutor (para ElevenLabs)

Registra estas herramientas en el agente de voz apuntando a
`https://tu-servidor/api/t/{slug}/tools/...` con el encabezado `x-api-key`:

1. `POST /tools/get_current_step` `{session_id}` → paso actual, caso y decisión del experto (oculta en niveles predict/exam).
2. `POST /tools/check_decision` `{session_id, decision}` → valida contra el experto y los guardrails; registra intervenciones.
3. `POST /tools/record_prediction` `{session_id, answer, explanation?}` → califica la predicción (la IA evalúa la explicación si está configurada) y actualiza el dominio.
4. `POST /tools/get_expert_moment` `{session_id}` → timestamps y cita del experto para ese paso.
5. `POST /tools/finish_session` `{session_id}` → puntaje 0–100 y lista de pasos por practicar.

## Método de aprendizaje

- **4 niveles**: `observe` → `predict` → `guided` → `exam`.
- **Dominio de un paso**: 3 aciertos seguidos, incluyendo al menos un caso nuevo (`novelty: new`).
- **Repetición espaciada**: acierto duplica el plazo de repaso (2^racha días); fallo lo vuelve a pedir en 10 minutos.
- **Guardrails**: límites, excepciones y "para y pregunta" con la cita textual del experto.

## Privacidad

- Correos, IBAN y teléfonos se reemplazan por `[email]`, `[iban]`, `[telefono]` antes de salir hacia el tutor o la IA.
- Lo marcado `off_record` nunca se exporta.
- Las claves de empresa se guardan solo como hash.

## Pruebas

```bash
./mvnw test
```

Incluye pruebas unitarias (matcher de decisiones, redactor de PII, dominio) y una
prueba de integración con Testcontainers que verifica que una empresa no ve los
datos de otra.

## Estructura

```
src/main/java/com/apprentice/
├── tenant/     Empresas, filtro de clave, contexto, RLS
├── workmap/    Work Maps, pasos y guardrails
├── cases/      Casos de práctica y generación con IA
├── learner/    Alumnos
├── session/    Sesiones, eventos, predicciones, intervenciones
├── mastery/    Dominio por paso (repetición espaciada)
├── tools/      Las 5 herramientas del tutor + matcher de decisiones
├── ingest/     Entrada de información (workmaps, casos, eventos)
├── ai/         Cliente de Anthropic Messages API
├── privacy/    Redactor de datos personales
├── demo/       Datos de ejemplo (modo demo)
└── common/     Errores de API
```
