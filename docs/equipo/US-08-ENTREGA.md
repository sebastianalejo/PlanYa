# US-08 — Chat temporal: entrega de Rodrigo

Rama: `rodrigo`, creada desde `steve` (commit `7e8e98b`). Historia: [US-08-CHAT.md](US-08-CHAT.md).
Nada se despliega automáticamente: los pasos de AWS de abajo se hacen a mano en el laboratorio.

## Qué incluye

**Pantalla nueva `ChatScreen`** (`presentation/screens/chat`):

- Burbujas propias a la derecha (índigo) y ajenas a la izquierda (blancas), con nombre, hora del
  servidor y etiqueta «Anfitrión».
- Aviso naranja con el tiempo que falta para el cierre y la hora exacta de cierre. Usa la hora
  del servidor, no la del teléfono.
- Campo multilínea (hasta 1000 caracteres, contador al acercarse al límite) y botón Enviar.
- Estado por mensaje: «Enviando…», «Enviado» o «Envío sin confirmar» con **Reintentar** y **Eliminar**.
  Si una consulta encuentra el mismo clientMessageId, se retira la burbuja pendiente sin duplicarlo.
- Botón Actualizar (↻) y «Cargar mensajes anteriores», sin perder el texto escrito.
- Estados de carga, error con reintento, chat vacío y bloqueo: salió del plan, cancelado,
  cerrado, sin horario o inexistente. Al bloquearse se retira el historial de la pantalla.
- Se actualiza al abrir, al volver a la app, después de enviar y con ↻. **No es en tiempo real**
  y la pantalla lo dice.

**Entrada desde Detalle**: tarjeta «Chat del encuentro» con «Abrir chat del plan». Solo la ven
el anfitrión y los inscritos, mientras el chat esté abierto. La protección real está en el servidor.

**Backend** (`backend/chat.py`, módulo aparte; `lambda_function.py` solo agrega el despacho):

| Método | Ruta | Respuesta |
| --- | --- | --- |
| GET | `/planes/{id}/mensajes` | 30 más recientes: `{items, nextKey, afterKey, chatExpiresAt, serverTime, planTitle, isHost, realtime:false}` |
| GET | `/planes/{id}/mensajes?nextKey=C` | Mensajes anteriores al cursor C |
| GET | `/planes/{id}/mensajes?after=C` | Mensajes nuevos posteriores al cursor C (hasta 50) |
| POST | `/planes/{id}/mensajes` | Cuerpo `{text, clientMessageId}` → 201 mensaje confirmado (200 si es un reintento) |

Mensaje: `{id, planId, senderId, senderName, text, createdAt (ms), cursor, expiresAt}`.
Errores: 400 `INVALID`, 401 sin sesión, 403 `NOT_MEMBER`, 404 `PLAN_NOT_FOUND`,
410 `CHAT_CLOSED` / `PLAN_CANCELLED` / `NO_SCHEDULE`, 409 `RETRY_CONFLICT`.

Reglas del servidor, comprobadas en **cada** lectura y envío:

- Identidad tomada del JWT (`sub`, `token_use=id`); se ignoran `senderId`/`senderName` del cuerpo.
- Acceso solo para el anfitrión y quien esté en `attendeeIds`. A una persona ajena no se le dice
  si el plan fue cancelado.
- `chatExpiresAt = expiresAt + 7200`, como acordó el equipo. Plan cancelado → 410 inmediato,
  sin otra ventana. Plan sin `expiresAt` → 410, sin caducidad inventada.
- Lee el registro del plan directamente, no el GET público. Así funciona hasta 2 h después de
  que el plan termina.
- Reintento idempotente: un registro `req#…` por usuario y `clientMessageId` evita duplicados,
  incluso si la Lambda se cortó entre las dos escrituras.
- Hora y orden del servidor: `sk = msg#<ms>#<uuid>`. Texto de 1 a 1000 caracteres; se rechazan
  caracteres de control y emojis partidos.
- No registra en logs el texto de los mensajes.

## Pasos en AWS (región us-east-1)

1. **DynamoDB → Crear tabla**
   - Nombre: `PlanYaMensajes`.
   - Clave de partición: `planId` (String). Clave de ordenación: `sk` (String).
   - Configuración predeterminada (capacidad bajo demanda).
2. **Tabla PlanYaMensajes → Configuración adicional → Tiempo de vida (TTL) → Activar**, con el
   atributo `expiresAt`. El TTL solo limpia, con retraso. El bloqueo exacto lo hace la Lambda.
   **No** activar TTL en PlanYaPlanes: el chat necesita el plan para validar la membresía.
3. **Lambda PlanYaApi → Código**
   - Archivo → Nuevo archivo `chat.py`: pegar completo `backend/chat.py`.
   - Usar `lambda_function.py` y `chat.py` de la misma revisión integrada y probada de `steve`.
     No pegar la versión original de esta rama sobre la integración: se perderían los handlers
     nuevos de Perfil y las correcciones de privacidad/Beneficios.
   - Pulsar **Deploy**.
4. **Configuración → Variables de entorno**: agregar `MESSAGES_TABLE = PlanYaMensajes`. Es
   opcional, porque ese es el valor predeterminado.
5. **Permisos**: el rol de la Lambda necesita `GetItem`, `PutItem` y `Query` sobre
   PlanYaMensajes. En el laboratorio la Lambda usa el rol del laboratorio. Si CloudWatch muestra
   AccessDenied, es falta de permiso del laboratorio: no abrir la tabla ni quitar el JWT.
6. **Probar en la consola (opcional)**: eventos `backend/events/chat-send-message.json` y
   `chat-list-messages.json`. Reemplazar `ID_DEL_PLAN` por el `id` de un plan vigente y
   `SUB_DE_UN_PARTICIPANTE` por su `ownerId`. El envío crea un mensaje real.
7. **API Gateway → PlanYaApiHttp → Rutas → Crear**
   - `GET /planes/{id}/mensajes` y `POST /planes/{id}/mensajes`.
   - Integración `PlanYaApi` (payload 2.0) y el **autorizador JWT de Cognito ya existente** en ambas;
     el nombre puede variar. Conservar emisor/audiencia actuales y no crear otro innecesariamente.
   - Revisar que `$default` tenga implementación automática habilitada; si no, implementar manualmente.

## Pruebas

- Backend: `python -m unittest discover -s backend/tests -v` → 75 pruebas: 49 existentes y
  26 de chat.
- Android: `ChatTest` (9 pruebas: mapeo, errores del servidor, repositorio, paginación).
  `.\gradlew.bat :app:testDebugUnitTest`.
- Recorrido manual para las capturas, con dos teléfonos o emulador + teléfono:
  1. A crea un plan y B se une. Ambos ven «Chat del encuentro» en Detalle.
  2. A envía un mensaje. B pulsa ↻ y lo ve con el nombre de A, la hora y la etiqueta «Anfitrión».
  3. B activa el modo avión y envía: queda «Envío sin confirmar». Quita el modo avión y pulsa
     Reintentar: aparece una sola vez.
  4. C, sin inscripción, no ve la tarjeta. Una llamada directa a la API responde 403.
  5. B sale del plan y vuelve al chat: «Ya no tienes acceso a este chat».
  6. A cancela el plan: el chat se bloquea al actualizar.

## Pendiente o por acordar

- **Mis conversaciones desde Perfil**: no está. Después de que un plan vence, el GET público
  devuelve 404 y el Detalle ya no se abre. El chat sigue disponible por API, pero la app no
  tiene otra entrada. Requiere `GET /conversaciones` y una fila en Perfil (US-10 / integrador).
- **Actualización periódica**: no se activó porque no está acordada. Hoy es manual.
- El campo se vacía al pulsar Enviar y el texto queda en la burbuja «Enviando…». Si falla, no
  se pierde: queda en la burbuja con Reintentar.
- Las pantallas Compose no se compilaron en el entorno donde se escribieron. Los modelos,
  datos y ViewModel sí se compilaron con Kotlin 2.2.10 y se probaron aparte. Compilar con
  `assembleDebug` antes del PR.
- Navegación tocada: `AppNavigation.kt` (ruta `chat/{planId}` y `onChat` del Detalle) y
  `NetworkModule.kt` (`chatApi`). Avisar al integrador.
