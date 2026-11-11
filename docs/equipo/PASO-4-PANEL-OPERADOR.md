# Paso 4 — Panel del operador (local)

11 de noviembre de 2026. No desplegar ni aprovisionar cuentas/ofertas todavía.

Actualización posterior: los cursores antiguos de planes/beneficios ya están
corregidos y conectados con Android localmente en el [Paso 5](PASO-5-LISTAS-PRIVADAS-Y-CONEXION.md).
Las menciones a esa corrección como pendiente más abajo son el estado histórico
del paso 4. La comprobación y el despliegue AWS siguen pendientes.

## Entrada y experiencia

Perfil → Personal autorizado → Acceso de operadores. La entrada no concede un
rol: el servidor consulta `BENEFIT_OPERATOR` en cada lectura/escritura. Una
cuenta normal ve «Acceso restringido» sin ofertas, participantes ni formulario.
No hay otra barra inferior ni cambios en las seis pantallas acordadas.

Asistencias: oferta asignada → encuentro autorizado → participantes actuales →
revisar asistencia → confirmación explícita. Se muestran nombre, anfitrión,
oferta, descuento y condiciones antes del envío. No aparecen el propio operador,
correos ni direcciones privadas. El operador debe comprobar presencialmente la
asistencia; la lista no demuestra presencia.

Canjear código: escribir/pegar código del cupón → revisar → confirmar. No se pide
contraseña, código de registro ni token Cognito. No se añade cámara ni un lector
QR falso: el escaneo de cámara queda pendiente; se usa entrada manual real.
No se muestra éxito hasta una respuesta válida, ligada a la solicitud.

Paleta/fuentes existentes; tarjetas blancas, controles de al menos 48 dp,
desplazamiento vertical y ancho máximo 720 dp. Confirmaciones blancas, acción
naranja y opción de volver; no cambia el diseño principal de Perfil.

## Lecturas nuevas, JWT existente

- `GET /operador/ofertas?nextKey=...`: solo ofertas activas, vigentes y de los
  locales del operador; máximo 20 registros evaluados por página. Devuelve
  `{userId,items:[{id,title,merchantName,discount,conditions,planIds}],nextKey}`.
- `GET /operador/ofertas/{id}/planes/{planId}`: oferta/local/plan autorizados y
  dentro de horario; devuelve `{userId,offerId,planId,title,members}`. Miembros
  `{userId,name,isHost}`; excluye operador y duplicados.
- Los clientes verifican el userId de las páginas y los IDs del encuentro.
  Una respuesta de otra sesión exige volver a ingresar y borra los datos del panel.
- Las ofertas piloto admiten hasta 32 planes y operadores hasta 32 locales.
  La administración deberá dividir ofertas/permisos si exceden ese límite.

El cursor de estas lecturas es UUID opaco, ligado a operador y lista de locales;
se guarda 15 minutos en `PlanYaMensajes` con `entityType=OPERATOR_CURSOR`,
`planId=operator#UUID`, `sk=cursor`, TTL `expiresAt`. No devuelve una clave
auxiliar de PlanYaPlanes ni el código de un cupón. Una página vacía con cursor
ofrece «Cargar más ofertas». Cambiar permisos invalida su continuación.

**Esto no corrige todavía los cursores antiguos de GET /planes y /beneficios**;
esa revisión sigue pendiente antes de declarar cerrado el flujo de privacidad.

## Reintentos, sesión y persistencia

Antes del envío, `OperatorJournal` confirma escritura local del requestId. Solo
guarda SHA-256 de cuenta/tipo/payload → UUID, nunca el código, sub, nombre,
contraseña o JWT en texto. La preferencia `operator_requests` está excluida de
copias cloud y transferencia de dispositivo. Las peticiones no se registran en
logs y no se añaden claves/configuración sensible.

El hash incluye la cuenta: otro usuario no reutiliza su identificador. El mismo
payload recupera el UUID tras rotación/reapertura/proceso; el formulario/código
no se guardan: si la app se cierra hay que seleccionar o escribir de nuevo los
mismos datos para consultar/reintentar la solicitud anterior. No hay cola de
reintento automática ni envío en segundo plano. Borrar datos/desinstalar borra
este diario; resolver un caso incierto antes de hacerlo.

Con resultado incierto se conserva la revisión y permite «Reintentar misma
solicitud». También se puede volver sin un nuevo envío; el hash/requestId queda
guardado para reingresar la operación. Durante escritura se bloquea doble click,
cambios y Atrás. 401 borra datos del panel y solicita acceso; nuevo loginEvent
crea un ViewModel nuevo. 403 borra el contenido y muestra acceso restringido.
404 es servicio no desplegado/código inexistente, no una lista vacía exitosa.

## Despliegue futuro — no realizado

Pruebas locales específicas: **142 Python**, **92 JVM** y **12 Android del
panel** correctas. Se verificaron acceso restringido, selección válida,
confirmación explícita, pérdida de respuesta, doble toque, requestId persistente
ligado a cuenta y limpieza por sesión vencida. Captura aislada revisada a 320 dp
y escala de fuente 1,4: opciones apiladas, etiquetas completas y desplazamiento.
El resumen es desplazable y cerrar una revisión incierta no se presenta como
cancelación de lo ya enviado. Los datos de prueba solo viven en tests, no en AWS.

Regresión seleccionada final en Motorola: **69/69 correctas, 320,4 segundos**,
incluyendo las 12 del panel, conversaciones, mapa, ciclo de vida del plan, chat,
beneficios, perfil, edición de perfil y recorrido principal. Builds finales
`assembleDebug`, `assembleDebugAndroidTest` y `testDebugUnitTest` correctos.
Se instaló con `adb install -r`, sin desinstalar la app principal ni borrar su
sesión; al finalizar se retiró únicamente el APK auxiliar de pruebas y se abrió
PlanYa normal. No se utilizó `connectedDebugAndroidTest`.

El paquete Lambda necesita ahora **cuatro archivos**:
`lambda_function.py`, `chat.py`, `benefit_redemption.py` y `operator_panel.py`.
Conservar rol, variables y rutas previas. Las dos consultas nuevas, `/asistencias`
y `/canjes` requieren el autorizador JWT y la integración existentes. El panel
reutiliza tablas y TTL de mensajes; no requiere activar servicios nuevos.
Verificar permisos del laboratorio y transacciones sin eludir denegaciones.

Antes de desplegar: cerrar revisión de cursores antiguos, completar pruebas
locales y preparar cuentas/oferta/local/plan de prueba explícitos. Después:
validar dos cuentas, rechazo de usuario normal, operador de local incorrecto,
asistencia real de prueba, reintento incierto, canje doble y estado en Mis beneficios.
No hay descuentos comerciales reales ni aceptación al 100 % por compilar.
