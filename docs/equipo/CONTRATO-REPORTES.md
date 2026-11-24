# Contrato REPORT compartido — US-12 (Rodrigo) y US-13 (Alfonso)

Propuesta de Alfonso para cerrar el acuerdo que pide la especificación de las pantallas 11–14
(«US-12 y US-13 acuerdan primero el modelo REPORT, los estados y las respuestas de la API»).
US-13 ya lee exactamente este formato. Si US-12 necesita otro nombre de campo, se cambia aquí
y en `backend/report_queries.py` + `domain/model/ReportsModels.kt` en el mismo PR.

## Registro en DynamoDB (`PlanYaPlanes`)

| Campo | Tipo | Quién lo escribe | Regla |
| --- | --- | --- | --- |
| `id` | S | US-12 | `reporte#<uuid4>` (UUID en minúsculas, formato canónico) |
| `entityType` | S | US-12 | Siempre `REPORT` |
| `reporterId` | S | US-12 | `sub` del JWT. Nunca del body |
| `targetType` | S | US-12 | `PLAN` o `MESSAGE` |
| `planId` | S | US-12 | UUID del plan (también para mensajes) |
| `messageId` | S | US-12 | Solo `MESSAGE`. Formato del chat sin el prefijo `msg#`: `<ms 13 dígitos>#<uuid>` (= `cursor` del mensaje en `GET /planes/{id}/mensajes`) |
| `reason` | S | US-12 | `INAPPROPRIATE`, `SPAM`, `SAFETY` u `OTHER` |
| `description` | S | US-12 | 10 a 500 caracteres tras `strip()` |
| `planTitle` | S | US-12 | Referencia mínima: título del plan al reportar (máx. 120). No se copia el texto del mensaje |
| `status` | S | US-12 crea / moderación cambia | `RECIBIDO` → `EN_REVISION` → `RESUELTO` o `DESCARTADO` |
| `createdAt` | N | US-12 | Segundos UTC del servidor |
| `updatedAt` | N | US-12 / moderación | Segundos UTC; igual a `createdAt` al crear |
| `revision` | N | US-12 / moderación | 0 al crear; +1 en cada PATCH |
| `reportSortKey` | S | US-12 | `f"{createdAt:010d}#{uuid}"` (el mismo uuid del id) |
| `requestId` | S | US-12 | UUID del intento (idempotencia) |
| `payloadHash` | S | US-12 | Hash del contenido del intento. Nunca se devuelve |
| `publicResponse` | S | Moderación | 10 a 500 caracteres; obligatoria al pasar a `RESUELTO`/`DESCARTADO` |

Campos que **nunca** salen por `GET /reportes*`: `reporterId` (se devuelve como `userId` de la sesión),
`requestId`, `payloadHash`, `revision`, identidad del moderador y notas internas.

## Índices

| Índice | Partición | Orden | Lo usa |
| --- | --- | --- | --- |
| `GSI_ReportsByReporter` | `reporterId` (S) | `reportSortKey` (S) | US-13 (`GET /reportes`) |
| `GSI_ReportsByStatus` | `status` (S) | `reportSortKey` (S) | US-12 (`GET /moderacion/reportes`) |

Proyección recomendada: `ALL`. Esperar estado **ACTIVE** antes de probar.

## Respuestas de US-13 (ya implementadas)

`GET /reportes?estado=TODOS|ABIERTOS|CERRADOS&nextKey=<cursor opaco>`

```json
{"success": true, "data": {"userId": "<sub>", "filter": "TODOS", "nextKey": null,
  "items": [{"id": "<uuid>", "shortCode": "A7F2", "targetType": "PLAN", "planTitle": "Café por Miraflores",
             "reason": "INAPPROPRIATE", "status": "EN_REVISION", "createdAt": 1795262400,
             "updatedAt": 1795262400, "hasResponse": false}]}}
```

`GET /reportes/{id}` → `{"success": true, "data": {"userId": "<sub>", "report": { ...lo anterior...,
"description": "...", "publicResponse": null, "targetAvailable": true }}}`

- `ABIERTOS` = `RECIBIDO` + `EN_REVISION`; `CERRADOS` = `RESUELTO` + `DESCARTADO`.
- Reporte ajeno o inexistente: `404 {"code": "REPORT_NOT_FOUND"}` (no revela si existe).
- Índice faltante: `503 {"code": "REPORTS_INDEX_UNAVAILABLE"}` (nunca lista vacía falsa).
- Cursores: los mismos cursores protegidos de Steve (`read_page_cursor`/`save_page_cursor`), ligados a cuenta y filtro.

## Lo que necesita US-13 de US-12 para la prueba de extremo a extremo

1. Que `POST /reportes` devuelva el `id` (uuid sin prefijo) para el botón «Ver mi reporte».
2. Una cuenta moderadora real (`PlanYaModeradores`) que cierre un reporte con `publicResponse`.
