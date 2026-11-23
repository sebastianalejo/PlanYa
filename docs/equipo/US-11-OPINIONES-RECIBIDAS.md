# US-11 — Opiniones recibidas

Fecha: 23 de noviembre de 2026.
Rama: `us11-sebastian`, creada desde `steve` (`0ad47e3`).
Responsable: Sebastián.
Estado: implementación local con pruebas; **pendiente el despliegue en AWS y la
validación con cuentas reales**. Este documento no acredita el despliegue.

## Alcance

Perfil → Confianza → **Opiniones recibidas** permite consultar el historial propio
de notas recibidas, con fecha, referencia neutral del encuentro, promedio y total
calculados por el servidor. No crea votos, no los modifica y no revela la identidad
de quien valoró.

Ruta Android: `perfil/opiniones`.

## Contrato

```http
GET /perfil/valoraciones-recibidas?nextKey=<uuid opaco>
Authorization: Bearer <id token de Cognito>
```

Respuesta 200:

```json
{
  "success": true,
  "data": {
    "userId": "<sub>",
    "count": 2,
    "average": 4.5,
    "items": [
      {
        "planId": "52b13d85-3994-5eb5-aa67-1e412dbc9528",
        "planTitle": "Café por Miraflores",
        "score": 5,
        "createdAt": 1795262400,
        "planAvailable": true
      }
    ],
    "nextKey": null
  }
}
```

- `count` y `average` se leen de `perfil#<sub>` (`ratingCount`/`ratingSum`) con
  lectura consistente y el mismo redondeo que `GET /perfil/confianza`. No se
  calculan solo con la página visible.
- Orden descendente por `createdAt`; 20 elementos por página.
- `planTitle` es el título real o «Encuentro no disponible» cuando el plan falta
  o está cancelado. La nota y la fecha se conservan.
- La respuesta no incluye `authorUserId`, nombre, correo, foto ni identificador
  del evaluador.
- No se acepta un `userId` de la petición: la cuenta sale exclusivamente del JWT.

Errores:

| Código | Situación |
| --- | --- |
| 400 `CURSOR_INVALID` | `nextKey` mal formado, de otra cuenta, de otro modo o con ámbito incorrecto |
| 401 | Sin sesión o token que no es de identidad |
| 409 `CURSOR_EXPIRED` | Continuación vencida |
| 503 | Fallo de DynamoDB, almacenamiento del cursor o tope defensivo del Scan |

## Backend

Archivos: `backend/ratings.py`, `backend/lambda_function.py`.

- La ruta se despacha junto a `GET/POST /valoraciones` y recibe la tabla de
  cursores `PlanYaMensajes`.
- **Con índice**: si `RATINGS_RECEIVED_INDEX` está definida, se usa `Query` al
  GSI `targetUserId-createdAt-index` (PK `targetUserId`, SK `createdAt`,
  `ScanIndexForward=False`).
- **Sin índice**: fallback de `Scan` consistente paginado con tope de
  100 páginas × 100 registros. Si se supera, responde 503 en lugar de presentar
  una lista parcial como vacía. Es un fallback MVP, no la solución de producción.
- **Cursor privado**: UUID aleatorio guardado en `PlanYaMensajes` con
  `entityType=PAGE_CURSOR`, `scope=received-ratings#<userId>`, `mode=index|scan`,
  clave interna y TTL de 15 minutos. La clave interna nunca se acepta desde HTTP
  ni se devuelve si falla su almacenamiento.
- **Sin escrituras**: no toca `RATING`, `ratingCount`, `ratingSum` ni el perfil.

### Índice propuesto

Crear en `PlanYaPlanes` un GSI:

- Nombre: `targetUserId-createdAt-index`.
- Partition key: `targetUserId` (String).
- Sort key: `createdAt` (Number).
- Proyección: `ALL`.

Después, definir en la Lambda `PlanYaApi`:

```text
RATINGS_RECEIVED_INDEX=targetUserId-createdAt-index
```

Si el laboratorio no permite crear el índice o el permiso `Query`, no definir la
variable: la ruta funciona con el fallback Scan y queda registrada esa limitación.

## Android

- `data/profile/ReceivedRatingsRepository.kt`: DTOs, API Retrofit, validación de
  sesión/notas/resumen y mapeo de 400/401/404/409/IO.
- `presentation/screens/profile/ReceivedRatingsViewModel.kt`: carga inicial,
  actualización, paginación, reintento de la misma página, cursor vencido y
  limpieza total al vencer la sesión.
- `presentation/screens/profile/ReceivedRatingsScreen.kt`: resumen, estrellas
  vacías sin votos, tarjetas con fecha y referencia del encuentro, aviso de
  privacidad, estados de carga/error/vacío, «Cargar más» y caso defensivo
  `count > 0` con lista aún vacía.
- `ProfileScreen.kt`: opción «Opiniones recibidas» dentro de Confianza, solo con
  sesión.
- `AppNavigation.kt`: ruta `perfil/opiniones`, guardia de sesión, ViewModel por
  `userId + loginEvent` y retorno a Perfil.

La fecha se muestra con `SimpleDateFormat("d MMM yyyy", es-PE)` para mantener
compatibilidad con `minSdk 24` sin desugaring.

## Criterios de aceptación

| CA | Cubierto por |
| --- | --- |
| 11-01 Cuenta sin opiniones | Resumen 0 / `average=null`, estrellas vacías y «0 valoraciones · Sin nota todavía»; backend y Compose |
| 11-02 Resumen correcto | Perfil `ratingCount=2`, `ratingSum=9` → 4.5 y 2; no se calcula con la página |
| 11-03 Privacidad por cuenta | Whitelist de campos; JWT manda; cursor de otra cuenta → 400; `userId` ajeno ignorado |
| 11-04 Continuación | 20 por página, orden descendente, cursor opaco reutilizable, sin duplicados ni solicitudes simultáneas |
| 11-05 Error de consulta | Tarjeta de error con «Reintentar»; no se muestra el estado vacío |
| 11-06 Plan no disponible | Se conserva nota/fecha con «Encuentro no disponible» |
| 11-07 Sesión y cambio de cuenta | 401 limpia lista, resumen y cursor; ViewModel keyed por cuenta/login |
| 11-08 Consulta sin escrituras | Prueba backend verifica que no hay transacción ni cambio del agregado |

## Evidencia local

- **Backend**: `python -m unittest discover -s backend/tests -q` → **175 pruebas
  OK**, incluidas 11 nuevas de US-11 (vacío, resumen, privacidad, paginación,
  cursor ajeno/vencido, plan no disponible, sin escrituras, modo índice, fallback
  Scan y tope defensivo).
- **Android JVM**: `gradlew testDebugUnitTest` → **112 pruebas, 0 fallos**,
  incluidas 6 nuevas de `ReceivedRatingsTest`.
- **Android instrumentado**: `ReceivedRatingsIntegrationTest` (9) y
  `ProfileScreenTest` (15) → **24/24 correctas** en el AVD
  `Medium_Phone_API_37.0`.
- **Compilaciones**: `assembleDebug`, `assembleDebugAndroidTest` y
  `testDebugUnitTest` correctas con Gradle 9.5 y el JBR de Android Studio.
- La captura de la pantalla con datos aislados está en
  [us11-opiniones-recibidas.png](evidencia/us11-opiniones-recibidas.png). Se
  generó en el AVD, no con cuentas reales.

Estas pruebas usan repositorios simulados y tablas DynamoDB simuladas. No son un
voto real ni una consulta autenticada a AWS.

## Despliegue pendiente

Paquete local preparado: `app/build/backend/PlanYaApi.zip` (ignorado por Git), con
los cinco módulos en la raíz. SHA-256:
`B86C6BA518C09F2695EC2D1956768423F7FD5D4ABBB8BA8CF7B989CD6EBC146D`.

1. Regenerar el ZIP con los cinco módulos actuales
   (`lambda_function.py`, `chat.py`, `benefit_redemption.py`, `operator_panel.py`,
   `ratings.py`) y subirlo a `PlanYaApi`.
2. Crear `GET /perfil/valoraciones-recibidas` en `PlanYaApiHttp`, con la
   integración `PlanYaApi` y el autorizador JWT `PlanYaUsuarios`.
3. Crear el GSI y definir `RATINGS_RECEIVED_INDEX` si el laboratorio lo permite.
4. Comprobar 401 sin token y, con dos cuentas reales, un historial propio.
5. Revisar CloudWatch si aparece 503; no eludir denegaciones de permisos.

## Límites

- No hay comentarios libres ni edición de notas.
- El historial no promete anonimato absoluto: en encuentros con pocos
  participantes puede inferirse quién valoró. Solo se ocultan los datos directos.
- Sin índice, el fallback Scan no es adecuado para producción y tiene tope
  defensivo.
- El porcentaje de avance y la validación en la nube quedan pendientes hasta el
  despliegue y la prueba con cuentas reales.
