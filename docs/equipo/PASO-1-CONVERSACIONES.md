# Paso 1 — Mis conversaciones

Implementación local sobre `04ef1e5`. No se ha hecho push ni desplegado AWS.

## Recorrido

Perfil → Comunidad → Mis conversaciones → Abrir chat → Volver a la lista → Perfil.
La lista incluye planes organizados o con inscripción vigente, aunque todavía no
tengan mensajes. No muestra contadores de no leídos ni una última actividad ficticia.
Un plan vencido sigue teniendo chat hasta `expiresAt + 7200`; uno cancelado se
excluye inmediatamente. Salir del plan revoca lectura/escritura en el servidor.

La entrada va directamente a `chat/{planId}`: no necesita el detalle público,
que devuelve 404 al vencer el plan. El chat recibe título y hostId desde su API,
por lo que conserva la etiqueta del anfitrión al abrir desde esta lista.

## Contrato local implementado

`GET /conversaciones?nextKey=<cursor>` con el mismo JWT de Cognito que Chat.
Solo acepta identidad de los claims verificados, no un userId elegido en la URL.

Respuesta: `{success,data:{userId,items,nextKey,serverTime}}`.
Cada resumen contiene `planId,title,hostId,hostName,category,planExpiresAt,
chatExpiresAt,isHost`. No incluye mensajes, direcciones, coordenadas ni correos.

- Reutiliza PlanYaPlanes y PlanYaMensajes; no añade tablas, índices, claves ni servicios.
- Un Scan consistente evalúa como máximo 25 registros por petición. Filtra por
  usuario, tipo de entidad, cancelación y horario; luego revalida cada candidato
  mediante GetItem consistente. No es una instantánea transaccional.
- Una página filtrada puede estar vacía y tener cursor: Android permite continuar
  sin anunciar falsamente que no existen conversaciones.
- LastEvaluatedKey nunca sale de la API: puede contener IDs/códigos privados de
  otras entidades. Se guarda bajo un UUID aleatorio ligado al usuario en
  PlanYaMensajes (`planId=conversations#<uuid>`, `sk=cursor`, tipo
  `CONVERSATION_CURSOR`). El cliente solo recibe ese UUID opaco.
- El cursor vence en 15 minutos; atributo TTL `expiresAt` de la tabla de mensajes.
  El servidor rechaza el cursor vencido aunque TTL aún no lo haya eliminado.
  409 `CURSOR_EXPIRED` obliga a actualizar desde la primera página.
- Si hay más páginas, se realiza un PutItem para el cursor; no hay consultas de
  fondo ni carga automática ilimitada. Para mayor escala conviene acordar índices
  por membresía; no se configuran recursos adicionales en este paso.

## Android y errores

- DTO/mapper, ConversationsRepository, caso de uso, ViewModel y pantalla propios,
  usando ChatApi y la misma sesión/token verificado del módulo de Chat.
- Actualiza al entrar/regresar, al volver del segundo plano y por acción explícita.
  El reloj local de la navegación oculta los chats cerrados con el desfase de hora
  recibido del servidor; no agrega sondeo de red periódico.
- Un fallo temporal conserva los resúmenes anteriores con aviso/reintento; el
  servidor comprueba permiso de nuevo al abrir el chat.
- Un 401 o una página de otra cuenta vacía los datos privados y solicita acceso.
  La clave de ViewModel incluye usuario y loginEvent para no reutilizar un chat
  terminalmente bloqueado después de iniciar otra sesión, incluso de la misma cuenta.
- Un 404 de ruta sin desplegar es error, no un vacío exitoso.
- Mantiene paleta/fuentes existentes, lista desplazable y texto grande. No añade
  otra barra inferior al entrar al chat o volver a Perfil.

## AWS pendiente — no hacerlo todavía como parte de las pruebas locales

Cuando toque despliegue conjunto, comprobar primero que existe PlanYaMensajes con
planId/sk y TTL expiresAt, y que el rol tiene Scan/GetItem sobre PlanYaPlanes y
GetItem/PutItem/Query sobre PlanYaMensajes. Mantener la configuración actual.

Subir juntos `backend/lambda_function.py` y `backend/chat.py` de la revisión final.
En API Gateway añadir **GET /conversaciones**, integrar PlanYaApi y asociar el
**autorizador JWT existente**. Conservar GET/POST mensajes, Perfil y Beneficios.
Verificar la implementación de la etapa `$default`. No abrir la ruta como pública.

No activar TTL sobre PlanYaPlanes con el expiresAt del plan: se necesita retener
la membresía hasta que cierre el chat. La limpieza de mensajes/cursor sigue
siendo asíncrona; el control de acceso es el del servidor.

## Evidencia

Backend: **99 pruebas aprobadas**, con 9 nuevas de conversaciones/despacho.
Incluyen cuenta ajena, tipo de token, cancelación, vencimiento exacto, membresía
revalidada, cursor opaco/bindado/vencido y reentrada sin detalle público.

Android: **84 pruebas JVM aprobadas** (77 anteriores y 7 nuevas) y **57 pruebas
instrumentadas aprobadas en el Motorola, 269,216 s** (47 anteriores y 10 nuevas).
APK principal y de instrumentación compilados correctamente en 6 min 19 s,
un solo worker y Kotlin in-process. Instalación con `adb install -r`, sin borrar
la app principal ni cerrar la sesión real. Se retira solo el APK auxiliar al terminar.
Las pruebas usan repositorios/cuentas aislados; no crean mensajes reales en AWS.
La captura `app/build/conversations-preview.png` fue revisada visualmente a
320 dp: mantiene paleta y fuentes, ambas tarjetas/CTA legibles y sin barras
duplicadas. Contiene fixtures de prueba, no actividad real.

Pendiente posterior: despliegue y recorrido con dos cuentas reales. Este paso no
implementa operadores/canje de Beneficios ni acredita asistencia o verificación.
