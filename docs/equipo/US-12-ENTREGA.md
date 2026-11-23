# US-12 — Reportar un problema: entrega de Rodrigo

Rama: `rodrigo`, actualizada desde `steve` (commit `0ad47e3`). Fuente: *PlanYa_4_Pantallas_Adic.docx*.
El mockup se usó solo como referencia: la pantalla usa los colores y componentes de la app.

## Qué incluye

**Pantalla nueva `ReportProblemScreen`** (`presentation/screens/report`). Una sola pantalla con dos entradas:

| Entrada | Dónde | Quién la ve |
| --- | --- | --- |
| «Reportar plan» | Detalle del plan, debajo de Asistentes | Quien no es anfitrión del plan |
| «Reportar mensaje» | Menú ⋮ junto a la hora de un mensaje ajeno en el chat | Participantes del chat, sobre mensajes de otras personas |

Contenido de la pantalla:

- Tarjeta con el contenido reportado (Encuentro / Mensaje en el chat, título del plan y autor del mensaje).
- «¿Qué ocurrió?»: motivo obligatorio (Contenido inapropiado, Spam o publicidad, Riesgo de seguridad, Otro motivo).
- «Describe lo ocurrido»: de 10 a 500 caracteres sin contar espacios extremos, con contador.
- Avisos: solo lo ven la persona y el equipo de moderación; reportar no cancela el plan, no borra
  mensajes ni bloquea cuentas. Si se elige «Riesgo de seguridad», indica que no es un servicio de emergencias.
- Botones «Enviar reporte» (naranja) y «Cancelar» (índigo). No hay adjuntos.
- Después del envío muestra el recibo del servidor: número, estado «Recibido», fecha, motivo y contenido.

Comportamiento:

- Los errores del formulario se marcan al intentar enviar y dicen qué corregir.
- Bloquea el doble envío: el botón queda en «Enviando…» y no acepta más toques ni cambios.
- Pide confirmación antes de descartar un borrador (Cancelar, flecha atrás o botón atrás del teléfono).
- Si falla la red, conserva el borrador, no muestra un éxito falso y ofrece «Reintentar envío».
  El reintento usa el mismo `requestId`, así que el servidor devuelve el mismo reporte en vez de duplicarlo.
- Si vence la sesión, pide ingresar de nuevo y conserva el borrador.

## Backend (`backend/reports.py`)

Módulo aparte; `lambda_function.py` solo agrega cuatro líneas de despacho.

| Método | Ruta | Quién | Respuesta |
| --- | --- | --- | --- |
| POST | `/reportes` | Cualquier cuenta con sesión | 201 `{data: reporte}`; 200 `{data, duplicate: true}` si es un reintento |
| GET | `/moderacion/reportes?status=RECIBIDO&nextKey=…` | Grupo `PlanYaModeradores` | `{items, nextKey, status}`, 20 por página, más reciente primero |
| PATCH | `/moderacion/reportes/{id}` | Grupo `PlanYaModeradores` | 200 `{data: reporte con historial}` |

Cuerpo de `POST /reportes`:

```json
{ "requestId": "uuid", "targetType": "PLAN | MESSAGE", "planId": "uuid",
  "messageId": "<ms 13 dígitos>#<uuid> (solo MESSAGE, el cursor del chat)",
  "reason": "INAPPROPRIATE | SPAM | SAFETY | OTHER", "description": "10 a 500 caracteres" }
```

Cuerpo de `PATCH`: `{requestId, expectedRevision, status, publicResponse?, internalNote?}`.

Reglas del servidor:

- `reporterId` sale del token verificado (ID token de Cognito), nunca del cuerpo. Campos como
  `status`, `revision`, `reporterId` o `isModerator` en el cuerpo se ignoran.
- Revalida el acceso. Plan: debe existir, no ser propio y estar visible (o ser participante).
  Mensaje: debe ser participante, el chat debe seguir abierto (hasta 2 h después del plan) y el
  mensaje debe existir en ese plan y no ser propio. Los errores no revelan contenido ajeno.
- No copia el texto del chat: guarda solo `messageId`. Así se respeta el TTL del chat.
- Idempotencia: el mismo `requestId` de la misma cuenta con el mismo contenido devuelve el mismo
  reporte. Con otro contenido responde 409 `REQUEST_CONFLICT`.
- Moderación solo con `PlanYaModeradores` en `cognito:groups` del token. El operador de cupones no
  recibe ese rol.
- Flujo: `RECIBIDO → EN_REVISION → RESUELTO | DESCARTADO`, sin saltos ni reapertura.
  Para cerrar se exige `publicResponse` (10 a 500 caracteres).
- Cada cambio comprueba `expectedRevision` de forma atómica (escritura condicional) y agrega una
  entrada al historial `{status, from, at, by, requestId}`. Si se repite el mismo `requestId`, devuelve
  el mismo recibo sin aplicar dos veces el cambio.
- Reportar **no** cancela planes, no borra mensajes y no sanciona cuentas.

Errores: 400 `INVALID`, 401 `UNAUTHORIZED`, 403 `NOT_MEMBER` / `NOT_MODERATOR`,
404 `PLAN_NOT_FOUND` / `MESSAGE_NOT_FOUND` / `REPORT_NOT_FOUND`, 409 `REQUEST_CONFLICT` /
`REVISION_CONFLICT` / `INVALID_TRANSITION` / `CURSOR_EXPIRED`, 410 `CHAT_CLOSED`, 503 `REPORTS_INDEX_UNAVAILABLE`.

### Persistencia

Tabla `PlanYaPlanes`, `id = "reporte#<uuid>"`, `entityType = "REPORT"`. Campos: `reporterId`,
`reportedUserId`, `targetType`, `planId`, `planTitle`, `messageId`, `reason`,
`description`, `status`, `revision`, `createdAt`, `updatedAt`, `closedAt`, `publicResponse`,
`reportSortKey = "<createdAt 10 dígitos>#<uuid>"`, `history`, `internalNotes`, `requestId`, `payloadHash`.

Índice de esta entrega (GSI, proyección ALL): `GSI_ReportsByStatus` (`status` + `reportSortKey`), que usa la
cola de moderación. El reporte ya guarda `reporterId` y `reportSortKey` para el índice de US-13.

Si falta el índice, la API responde 503 `REPORTS_INDEX_UNAVAILABLE` en vez de una lista vacía. Los cursores de
página son opacos, duran 15 minutos y están atados a la cuenta y al filtro.

## Modelo REPORT acordado con US-13

Se sigue el contrato que publicó US-13 en `docs/equipo/CONTRATO-REPORTES.md` (rama `feature/reportes`):
mismos campos, `messageId` con el formato del cursor del chat, `reportSortKey` con el uuid y el mismo
código de error si falta un índice. `domain/model/ReportsModels.kt` es una copia idéntica del archivo
acordado, para que al integrar no haya dos versiones. Los archivos propios de US-12 usan nombres
distintos (`ReportProblem*`, `CreateReportDto`, `ReportReceiptDto`, `reportProblemApi`) para no chocar
con los de US-13. «Mis reportes», `GET /reportes` y el índice `GSI_ReportsByReporter` son de US-13;
esta entrega no los implementa ni los crea.

## Fragmento de integración (archivos compartidos)

- `AppNavigation.kt`: ruta `reportar/{planId}?titulo=&mensaje=&autor=`, función `reportRoute(...)`,
  `onReport` en Detalle y `onReportMessage` en Chat.
- `NetworkModule.kt`: `reportProblemApi`.
- `lambda_function.py`: despacho de las tres rutas a `reports.handle(...)`.
- `PlanDetailScreen.kt` y `ChatScreen.kt`: solo parámetros nuevos con valor por defecto; nada cambia si no se usan.

Todos son agregados: no se modificó ni se borró código existente de otras historias.

## AWS (laboratorio)

1. Respaldo de la Lambda (`publish-version`) antes de cambiarla.
2. Crear `GSI_ReportsByStatus` en `PlanYaPlanes` y esperar a que esté `ACTIVE`.
3. Subir `reports.py` y agregar al `lambda_function.py` desplegado solo las cuatro líneas del despacho; el resto del código queda igual.
4. Crear en API Gateway (`g0msb4hkn6`) las rutas `POST /reportes`, `GET /moderacion/reportes` y
   `PATCH /moderacion/reportes/{id}` con el autorizador JWT y la integración de `PlanYaApi`, y dar
   permiso de invocación a la Lambda para cada ruta.
5. Crear el grupo de Cognito `PlanYaModeradores` y agregar solo la cuenta moderadora que elija el equipo.

## Pruebas

- Backend: `python -m unittest discover -s backend/tests` → `test_reports.py` agrega 14 pruebas
  (CA 12 01 a 08: creación, validación, acceso, contexto manipulado, idempotencia, permisos de
  moderación, flujo auditado, cola paginada, índice faltante y no copia del chat).
- Android: `ReportProblemTest` (validación, `requestId` estable, mapper, errores, repositorio, sin éxito sin recibo).
