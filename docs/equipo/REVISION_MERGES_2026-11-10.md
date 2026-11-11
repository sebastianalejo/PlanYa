# Revisión gradual de ramas — 10 de noviembre de 2026

## Punto de partida

- Base compartida: `7e8e98b`.
- José (`origin/jose`, `bba999f`) entró por fast-forward.
- Sebastián (`origin/us10-sebastian`, `f988a74`) quedó en conflicto en navegación y
  Perfil. Se resolvió conservando Beneficios y actividad/confianza, sin duplicar
  «Mis beneficios» ni invocar un callback nullable como si fuera obligatorio.
- `origin/steve` ya apunta a `9392244`, merge de Rodrigo/Chat. La rama local y la
  remota han divergido. No usar pull/push forzado ni mezclar otra rama durante
  el merge abierto. Esta revisión no despliega AWS ni hace push.

## Evidencia y hallazgos

### José — US-09 Beneficios

Entregó modelos, DTO/mapper, repositorio, casos de uso, ViewModel, lista/detalle,
QR con ZXing, navegación desde Perfil y tres handlers del backend.

La copia aislada de su rama pasó las 49 pruebas Python existentes. Una prueba
adicional con datos simulados reprodujo que `BENEFIT_CODE` podía aparecer en
GET público de planes y que GET detalle público devolvía HTTP 200 para su ID.
No se consultaron códigos ni cupones reales. Sus pruebas anteriores no cubrían
los registros nuevos y por eso no detectaban el problema.

Correcciones en la integración:

- Lista positiva de entidades públicas: planes legacy o PLAN; excluir cupones,
  códigos, perfiles, asistencias, valoraciones y tipos futuros desconocidos.
- El estado vencido se calcula también en servidor y en el filtro de listado;
  no depender de que un proceso cambie el campo status al vencer.
- El código temporal no dura más que el cupón; estados no disponibles se rechazan.
- Una página de Scan vacía con cursor permite seguir cargando desde Android.
- Consultas de estado solo en primer plano, cada 15 s mientras haya código;
  no mantener consultas cuando la app queda en segundo plano.
- Un error de red indica resultado no confirmado, no asegura falsamente que
  el servidor no generó un código. El detalle comprueba el ID recibido.

**Pendiente funcional:** no existe POST /canjes ni operación de emisión/asistencia
autorizada. QR/listado no equivalen a canje real ni cumplen toda US-09. No hay
evidencia en esta revisión de rutas desplegadas, QR leído por otro teléfono o
validación de operador/doble canje. No marcar Beneficios al 100%.

### Sebastián — US-10 Perfil

Entregó actividad (organizados, me uní y asistencias acreditadas), reputación de
lectura y estado de verificación, con UI y pruebas. Su documento acota la entrega
a lectura: no crea valoraciones, asistencias ni aprobaciones de verificación.

Corrección en integración: PUT /perfil reconstruía el registro y borraba
verificationStatus/method, verifiedAt/by y ratingCount/ratingSum al editar nombre,
avatar o sincronizar intereses. Se conservan los valores previos del servidor,
manteniendo el rechazo de campos privilegiados enviados por Android.
Los escritores administrativos de confianza deben incrementar revision para
mantener el control de concurrencia al editar desde otra sesión.

**Pendiente funcional:** nuevas lecturas GET /perfil/actividad y GET
/perfil/confianza necesitan integración Lambda/API Gateway protegida y prueba
real. No asumir que ya están desplegadas. Generación de votos, acreditación y
aprobación quedan fuera de su alcance de lectura; no contarlas como implementadas.

## Pruebas del conjunto

- Python integrado: **63 casos aprobados** (55 previos con Perfil + 8 nuevos de
  privacidad, autorización, estados/vencimiento y conservación de confianza).
- Compilación Android **correcta**, **58 tests JVM aprobados** (incluye 8 de
  BenefitMapper) y APK de instrumentación compilado. Primer pase: 6 min 30 s.
  El compilador Kotlin perdió su daemon bajo presión de memoria y completó
  con la estrategia de respaldo; no se confunde ese aviso con error del código.
  Segundo pase con un solo worker y estrategia Kotlin in-process: BUILD
  SUCCESSFUL en 12 s, todos los artefactos actuales y tests sin cambios pendientes.
- Nuevos casos UI en BenefitsIntegrationTest: paginación vacía, generación solo
  por clic, cupón vencido y opción única de Beneficios en Perfil.
- Actualización posterior: Motorola conectado y desbloqueado. **24 pruebas
  instrumentadas aprobadas en 93,6 s**: BenefitsIntegrationTest (4), Perfil UI
  (10), edición/lógica Perfil (9) y navegación principal (1). Se instalaron APKs
  con `adb install -r`, sin desinstalar la app principal ni cerrar la sesión real.
  Pruebas con APIs/usuarios aislados, sin emitir cupones o canjear en AWS.
  Capturas locales de Perfil revisadas en `app/build/profile-trust-empty.png`
  y `app/build/profile-trust-rated.png`; los nombres/estadísticas son fixtures
  de prueba, no datos reales del usuario ni evidencia de rutas AWS desplegadas.
- La compilación aislada de José fue detenida por presión de memoria del equipo;
  no se registra como compilación completa ni como pruebas JVM aprobadas.

## Segundo punto de control: Rodrigo — US-08 Chat

Se integró `origin/steve` (`9392244`) porque contiene exactamente la entrega
de Rodrigo (`6ffbd0b`) sobre la base compartida, sin otros cambios de producto.
Esto conserva el historial remoto y evita un futuro push forzado. El conflicto
en `lambda_function.py` se resolvió conservando tanto actividad/confianza de
Perfil como el despacho hacia `chat.py`; Beneficios y sus correcciones siguen presentes.

Hallazgos corregidos durante la revisión:

- Reconciliar el clientMessageId confirmado al consultar con las burbujas sin
  confirmar: una respuesta de envío perdida no produce dos burbujas al actualizar.
- No afirmar «No se envió» si solo se perdió la respuesta; «Envío sin confirmar».
- Cancelar consultas/envíos pendientes y vaciar historial al perder membresía o
  sesión. Respuestas atrasadas no pueden restaurarlo, incluso tras reconocer el
  evento de acceso. Ocultar historial/compositor al alcanzar el cierre conocido,
  aunque falle la revalidación de red.
- Comprobar plan de la página y usuario/plan/clientMessageId/texto de la confirmación.
- Suspender el reloj Compose en segundo plano; mantener actualización manual,
  sin anunciar tiempo real ni agregar consultas periódicas pagadas.
- Backend solo admite entidades PLAN o planes legacy; no abrir chat para un
  registro auxiliar que coincida con un ID válido.
- Documentación de despliegue coordinada: ambos archivos de la revisión integrada,
  autorizador JWT existente y comprobación de implementación automática de la etapa.

Pruebas del segundo punto de control:

- **90 pruebas Python aprobadas** (63 anteriores, 26 de Rodrigo y 1 de rechazo
  de entidades auxiliares).
- **69 pruebas JVM aprobadas**, cero errores/fallos: 58 anteriores y 11 de Chat
  (9 de Rodrigo, 2 nuevas de respuesta ajena/confirmación de otra solicitud).
- APK principal y de instrumentación compilados correctamente. Segundo pase:
  BUILD SUCCESSFUL en 3 min 56 s con un solo worker/Kotlin in-process.
- **30 pruebas instrumentadas aprobadas en el Motorola, 137,456 s**: 6 nuevas
  de Chat y las 24 de Beneficios/Perfil/navegación. Casos nuevos: reintento con
  el mismo ID, reconciliación de respuesta perdida, revocación con historial
  atrasado, sesión vencida con actualización atrasada, cierre local sin red y
  acciones del envío sin confirmar.
- Primera ronda: 29/30; falló la protección de sesión tras reconocer el evento.
  Se recompiló el APK incorporando el cierre terminal de acceso; el caso aislado
  pasó y después la ronda completa pasó 30/30. No se cuenta la primera como éxito.
- Pruebas con datos/repositorios aislados: no se escribieron mensajes reales en
  AWS, ni se extrajeron tokens, ni se cerró la sesión real. Instalación con `-r`;
  al terminar se retira solo el APK auxiliar de pruebas y se vuelve a PlanYa.

**No cerrado al 100%:** falta `GET /conversaciones` y entrada desde Perfil. Un
plan vencido desaparece del GET público: el backend permite las dos horas restantes,
pero la app no ofrece reentrada tras abandonar el chat. También faltan comprobar
tabla `PlanYaMensajes`, TTL, permisos, archivo `chat.py` desplegado y recorrido con
dos cuentas reales. Las rutas que pegó el usuario sí incluyen GET/POST mensajes
con JWT e integración Lambda, pero eso no prueba que el módulo funcione en AWS.

No se hizo push ni se modificó AWS en este punto de control.

## Tercer punto de control: Alfonso — US-07 Mapa

`origin/feature/mapa` (`43f78b4`) se integró sobre el punto de control de Chat
`d37d866`, sin conflictos textuales. Entrega pines de planes reales, filtros por
categoría, agrupación de coordenadas, ficha con distancia en línea recta y acceso
al detalle, paginación, reintento, cámara conservada y GPS por acción explícita.
No modifica backend ni requiere nuevas rutas de AWS.

Durante la revisión se comprobó GitHub (`git ls-remote`) y se descargó la punta
actual `338fbaf`, con padres `43f78b4` y `9392244`: únicamente une Chat en Mapa.
No contiene cambios adicionales en las funciones de Mapa ni resoluciones manuales
en el merge. Tras guardar el punto probado `1e618af`, se integró también ese
historial remoto. El resultado no cambió ningún archivo de código/pruebas
(`git diff HEAD` vacío antes de esta nota); no reintrodujo las versiones antiguas
de Chat. La evidencia de compilación/pruebas corresponde al mismo código final.

Correcciones de integración:

- Versionar las mutaciones del mapa: una lista iniciada antes de editar,
  publicar o cancelar no puede deshacer un cambio confirmado ni revivir un pin.
- Agregar los planes nuevos al mapa, no solo reemplazar IDs que ya estaban;
  conectar también la publicación desde el editor.
- Al recibir el detalle actualizado, reconciliar el pin. Una indisponibilidad
  definitiva comunica su ID al mapa; un error transitorio conserva los datos.
- En paginación, una versión más reciente del mismo ID reemplaza la anterior.
- Separar el aviso de vista inicial y «Mi ubicación» en filas para teléfonos
  estrechos/texto grande; no mostrar un vacío encima del aviso de SDK ausente.
- Si desaparece el plan elegido de un grupo y queda solo uno, seleccionar el
  restante y mantener disponible «Ver detalles».
- No describir un GPS aproximado como si fuera una dirección elegida manualmente.

Comprobación de lectura del GET público `/planes`: success true, 2 registros,
1 con latitude/longitude y sin siguiente página. No se escribieron planes ni
se imprimieron ubicaciones personales/tokens. El mapa no inventa coordenadas
para los registros sin ubicación.

Pruebas del tercer punto de control:

- Compilación de APK principal y de instrumentación correcta: BUILD SUCCESSFUL
  en 5 min 49 s, un solo worker y Kotlin in-process.
- **77 pruebas JVM aprobadas**, sin errores/fallos: las 69 anteriores y los 8
  casos de pines entregados por Alfonso.
- **47 pruebas instrumentadas aprobadas en el Motorola en 215,059 s**: 12 de
  Mapa, 5 de ciclo de vida y las 30 del conjunto Chat/Beneficios/Perfil/navegación.
- Mapa: edición/publicación/cancelación frente a respuesta atrasada, página
  vacía con cursor, actualización de duplicados, reintento de la misma página,
  cámara/filtro/GPS explícito, selección de pin y detalle por ID, grupos que
  pierden un miembro, vencimiento, controles sin solaparse a 320 dp/texto grande,
  falta de SDK, aviso de datos previos y CTA accesible con texto grande.
- El servidor conserva **90 pruebas Python aprobadas**; no se modificó backend.
- Instalación de ambos APKs con `adb install -r`; se conserva la sesión y no
  se desinstala la app principal. Al cerrar se elimina solo el APK de pruebas.

Las pruebas nuevas usan repositorio/GPS/lienzo aislados, sin crear cargas del SDK
de Google Maps ni modificar permisos reales del teléfono. Eso comprueba lógica
y controles, no sustituye comprobar mosaicos, clave restringida y GPS real en la app.

No se hizo push ni se modificó AWS/Google Cloud.

## Próximos pasos tras estos puntos de control

### Paso 1 implementado localmente — 11 de noviembre de 2026

Se añadió Perfil → Mis conversaciones → Chat → Volver, con lista autorizada,
cursor opaco ligado a cuenta y reentrada durante las dos horas posteriores al
vencimiento del plan. No depende de GET público del detalle. No hay mensajes
de demostración ni contadores inventados. El estado de anfitrión llega del servidor.
Las instancias de Chat/lista se separan también por loginEvent, para recuperar
correctamente el acceso tras otra sesión sin reutilizar un ViewModel cerrado.

Evidencia: **99 pruebas Python**, **84 JVM** y **57 instrumentadas en el Motorola
(269,216 s), todas aprobadas**. Compilación en 6 min 19 s. Captura de lista a
320 dp revisada; datos aislados, no actividad AWS real. Se actualizó el inventario
con los módulos integrados y porcentajes orientativos (aprox. 93%, no certificación).
Sin push ni despliegue. Contrato, límites y configuración posterior en
[Paso 1: Mis conversaciones](PASO-1-CONVERSACIONES.md).

### Orden restante

1. Conjunto José + Sebastián: integrado, corregido y probado localmente.
2. Chat de Rodrigo: integrado, corregido y probado localmente.
3. Mapa de Alfonso: integrado, corregido y probado localmente.
4. Comprobar mosaicos/GPS real y los recorridos AWS con cuentas de prueba; las
   pruebas aisladas no garantizan rutas/tabla/permisos desplegados.
5. Desplegar juntos los archivos de backend integrados, con autorización y sin
   reemplazarlos por versiones antiguas. Completar los pendientes funcionales
   documentados (canje, operaciones privilegiadas fuera de lectura). Conversaciones
   ya está implementado localmente, pero su ruta nueva todavía necesita despliegue.
6. Preparar push normal tras verificar de nuevo el remoto. No se ha hecho push
   durante esta revisión.

Cada paso distingue: conflicto resuelto, compila, tests locales, prueba en teléfono
y operación AWS real. Un merge sin conflictos no demuestra los otros cuatro.
