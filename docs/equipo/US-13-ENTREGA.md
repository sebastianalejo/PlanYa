# US-13 Mis reportes: entrega

Rama: `feature/reportes` (creada desde `steve` en `0ad47e3`). Responsable: Alfonso López.

**Estado:** Android y backend programados, con pruebas. Falta lo que no se puede hacer
desde código: crear el índice y las rutas en AWS, y la prueba de extremo a extremo con un
reporte real creado por US-12 (Rodrigo) y cerrado por un moderador.

## Qué hace

Perfil → **Mis reportes** (ruta Android `reportes`). Muestra solo los reportes de la cuenta:

- Volver, **Actualizar** (manual, no push) y aviso «Solo se muestran los reportes de tu cuenta».
- Filtros **Todos / Abiertos / Cerrados**. Abiertos = Recibido + En revisión; Cerrados = Resuelto + Descartado.
- Tarjeta: título del plan, estado con color, código corto `#A7F2`, fecha de envío y motivo.
- **Ver detalle** expande la tarjeta en la misma pantalla (no hay quinta pantalla): tu descripción,
  respuesta de moderación si el reporte se cerró, o aviso de pendiente, y fecha de actualización.
- Si el plan o mensaje original ya no existe (TTL del chat, cancelado, vencido): «Contenido no disponible»,
  sin reconstruir el mensaje y sin perder el reporte.
- **Cargar más** con cursor opaco; una página vacía con continuación muestra «Continúa la búsqueda».
- Estados de carga, vacío confirmado, error con Reintentar, datos previos con aviso, sesión vencida.
- No hay controles para cambiar estado, borrar ni sancionar.

## Archivos

| Archivo | Qué hace |
| --- | --- |
| `backend/report_queries.py` | GET /reportes (Query a `GSI_ReportsByReporter`, filtros, cursor protegido) y GET /reportes/{id} (404 igual para ajeno o inexistente; `targetAvailable`) |
| `backend/lambda_function.py` | Despacho de las 2 rutas (5 líneas) y `reporte#` excluido de los planes públicos |
| `backend/tests/test_report_queries.py` | 15 pruebas Python |
| `domain/model/ReportsModels.kt` | Modelo compartido con US-12: estados, motivos, filtros, resumen y detalle |
| `data/remote/api/ReportsApi.kt`, `data/remote/dto/ReportDto.kt`, `data/mapper/ReportMapper.kt` | Contrato Retrofit, JSON y conversión (descarta filas mal formadas) |
| `domain/repository/ReportsRepository.kt`, `data/repository/ReportsRepositoryImpl.kt` | Acceso de solo lectura ligado a la cuenta; traduce 401/404/409/503 y sin red |
| `presentation/screens/reports/MyReportsViewModel.kt`, `MyReportsScreen.kt` | Estado y pantalla según el mockup |
| `di/NetworkModule.kt`, `presentation/navigation/AppNavigation.kt`, `presentation/screens/profile/ProfileScreen.kt` | `reportsApi`, ruta `reportes`, opción «Mis reportes» en Comunidad |
| `test/.../data/ReportsTest.kt` | 8 pruebas JVM (mapper, filtros, repositorio, errores) |
| `androidTest/.../MyReportsIntegrationTest.kt` | 8 pruebas de pantalla con datos aislados |
| `docs/equipo/CONTRATO-REPORTES.md` | Modelo REPORT propuesto a Rodrigo (US-12) |

## Criterios de aceptación

| CA | Criterio | Cómo se cumple | Prueba |
| --- | --- | --- | --- |
| 13-01 | Historial vacío | Vacío confirmado, sin casos de demostración | Python `empty_history`, Android `emptyHistory…` |
| 13-02 | Trazabilidad del envío | El mismo `id` de US-12 se lista y se expande | Python `report_created_by_us12…`, Android `expandShows…` |
| 13-03 | Filtros y continuaciones | Filtro en servidor y Android; cursor ligado al filtro; página vacía con continuación; sin duplicados ni doble pedido | Python `filters…`, `pagination…`; Android `filters…`, `loadMore…` |
| 13-04 | Respuesta de moderación | `publicResponse` solo si el reporte está cerrado; nunca notas internas ni moderador | Python `moderation_response…`; JVM `responseIsShownOnlyForClosedReports` |
| 13-05 | Aislamiento de cuentas | Cuenta desde el JWT; 404 idéntico para ajeno o inexistente; cursor de otra cuenta rechazado | Python `other_account…`, `forged_cursor…` |
| 13-06 | Fallo sin borrar resultados | Se conservan los reportes con aviso y Reintentar | Android `networkFailureKeepsLoadedReports…` |
| 13-07 | Sesión y permisos | Sesión vencida limpia lista, expansión y cursores; ViewModel por cuenta y `loginEvent`; sin controles de estado | Android `expiredSessionClearsPrivateState`; Python `queries_never_write` |
| 13-08 | Contenido vencido | `targetAvailable=false` → «Contenido no disponible», sin devolver el texto | Python `expired_or_deleted_target…`; Android `unavailableTarget…` |

## Pruebas

- Backend: `python -m unittest discover -s backend/tests -v` → **179 aprobadas** (164 previas + 15 nuevas).
- Android: `gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest`.
  En el entorno donde se programó no hay Android SDK: **la compilación y las pruebas Android
  deben ejecutarse en Android Studio** antes de dar la US por cerrada.
- Pruebas de pantalla en el teléfono con el procedimiento seguro del equipo (`adb install -r`, `am instrument`
  solo con `MyReportsIntegrationTest`), sin desinstalar la app principal.

## Configuración en AWS (por quien tenga el laboratorio)

1. **DynamoDB → PlanYaPlanes → Índices → Crear índice**: partición `reporterId` (String), orden
   `reportSortKey` (String), nombre `GSI_ReportsByReporter`, proyección **Todos**. Esperar **ACTIVE**.
2. **Lambda PlanYaApi**: subir el ZIP con los 6 módulos (`lambda_function.py`, `chat.py`,
   `benefit_redemption.py`, `operator_panel.py`, `ratings.py`, `report_queries.py`) → **Deploy**.
   El rol necesita `dynamodb:Query` sobre `PlanYaPlanes/index/*` (además de lo que ya tenía).
3. **API Gateway (PlanYaApiHttp)**: rutas **GET /reportes** y **GET /reportes/{id}** → integración
   PlanYaApi → autorizador JWT PlanYaUsuarios. Confirmar que la etapa se implemente.
4. Probar desde la app: Perfil → Mis reportes debe mostrar «Aún no has enviado reportes».

Sin el paso 1 la pantalla muestra «Mis reportes todavía no está configurado en el servidor».

## Prueba sin esperar a US-12 (opcional, solo datos de prueba)

En DynamoDB → PlanYaPlanes → Crear elemento (JSON), con **tu propio** `sub` (Perfil de Cognito):
`id = reporte#<uuid>`, `entityType = REPORT`, `reporterId = <tu sub>`, `targetType = PLAN`,
`planId = <id de un plan>`, `reason = SPAM`, `description = Reporte de prueba de US-13`,
`planTitle = <título>`, `status = RESUELTO`, `createdAt`/`updatedAt` = segundos actuales,
`reportSortKey = <createdAt 10 dígitos>#<uuid>`, `publicResponse = Prueba de respuesta`.
Es un registro de prueba: **borrarlo después** y no presentarlo como reporte real.

## Pendiente

- Rutas, índice y Deploy en AWS (arriba).
- Prueba de extremo a extremo: reporte creado por US-12 → cerrado por un moderador real → visible aquí.
- Botón «Ver mi reporte» después de enviar en US-12 (entrada secundaria): cuando exista, navega a `reportes`.
- Capturas en el teléfono.
