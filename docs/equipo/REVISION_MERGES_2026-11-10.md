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

## Secuencia prevista

1. Resolver y verificar el conjunto José + Sebastián; guardar un merge local
   solo cuando compile y pasen los tests locales. No publicar ni desplegar todavía.
2. Revisar `origin/rodrigo`, luego integrar Chat en un merge separado y repetir
   pruebas. Su commit ya es ancestro de origin/steve: coordinar con ese historial.
3. Revisar e integrar `origin/feature/mapa` por separado y repetir pruebas.
4. Validar pantallas en teléfono, después rutas/configuración AWS con autorización.
5. Preparar push normal solo tras revisar que el historial remoto quede integrado.

Cada paso distingue: conflicto resuelto, compila, tests locales, prueba en teléfono
y operación AWS real. Un merge sin conflictos no demuestra los otros cuatro.
