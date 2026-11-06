# Cierre de las seis pantallas de Steve

Las seis son Splash, Acceso/Registro, Intereses, Inicio, Crear/Editar y Detalle.
Perfil es un módulo adicional compartido. Mapa de planes, Chat y Beneficios
siguen separados para el equipo. No se cuentan como terminados por tener botones.

## Revisión de cierre más reciente

Estado de aceptación del alcance implementado: cerrado según confirmación del
usuario. Confirmó vencimiento natural del plan y luego indicó que funcionan las
tres últimas validaciones: renovación de sesión, intereses en otro dispositivo
y limpieza S3 sin afectar avatares. Es evidencia reportada por el usuario, no una
inspección directa del asistente sobre sesiones, otro teléfono ni objetos de S3.
El 100% se refiere a estas seis pantallas y sus flujos actuales; no al inventario
completo, Apple, mapa de planes, chat, beneficios ni certificación de producción.
Los apartados de comprobación siguientes quedan como guía de regresión e histórico.

- Implementado y probado refresco al volver a primer plano: Inicio vuelve a leer
  la lista, y el detalle vuelve a consultar cupos, cancelación y enlaces temporales.
  No hay sondeo de red continuo; cámara/galería no recargan ni pisan el editor.
- Si GET detalle devuelve PlanUnavailableException, se retira de Inicio incluso
  si termina después una lista antigua. Si falla la conexión se conserva el plan
  con aviso de datos sin actualizar. Un HTTP genérico no se trata como cancelación.
- Si vence el plan con la confirmación de cancelar/salir abierta, el diálogo
  desaparece; no envía una acción obsoleta. Servidor mantiene su propia validación.
- Compilación y 50 pruebas JVM correctas; 15 pruebas Android seleccionadas
  aprobadas en Motorola (68,05 segundos), incluidos los 5 nuevos casos de ciclo
  de vida, edición, participación y navegación. Datos/API aislados.
- Lectura real no mutante de GET /planes: success=true, 1 elemento, sin página
  siguiente ni elementos vencidos/cancelados devueltos. No equivale a observar
  un encuentro caducar ni a una auditoría del contenido completo de DynamoDB/S3.

La aceptación de renovación natural de sesión, caducidad de un plan,
intereses en otro dispositivo y Lifecycle fue confirmada posteriormente por el
usuario. No se basa solo en pruebas aisladas. No requiere Deploy nuevo de Lambda.

Verificación local de esta entrega: compilación correcta y 50 pruebas JVM
aprobadas. Con el Motorola desbloqueado, la repetición completa de 39 pruebas
de instrumentación terminó correctamente (104,72 segundos, 5 de noviembre).
Incluye sesión, acceso, Google, navegación, perfil, galería e intereses; resuelve
la repetición visual que antes quedó bloqueada por Dozing. Estas pruebas usan
datos aislados y no sustituyen la comprobación real de correo en Cognito.

Comprobación adicional: compilación correcta, 49 pruebas Python aprobadas y
13 pruebas de galería/fotos/participación aprobadas en Motorola (43,98 segundos).
Se amplió el caso visual para quitar las cuatro fotos hasta 0/4, eliminar los
campos de portada y volver a habilitar cámara/galería. Backend y payload ya tienen
casos de lista vacía; esto no sustituye probar la eliminación física en S3 real.

## Implementado en esta revisión Android

Actualización visual posterior: se sustituyó la marca de carga por el logo
«PlanYa» en cápsula aportado por el usuario, preparado con transparencia. Acceso
separado en bienvenida Google/correo y formulario nativo. Suite de 15 pruebas
seleccionadas aprobada; detalles y capturas en IDENTIDAD_ACCESO.md.

- Identidad de arranque: carga Compose usa la marca vectorial PlanYa en lugar del
  icono genérico de ubicación. Icono de aplicación adaptativo, monocromo y legado
  reutilizan la marca; Android 12+ tiene la variante dentro de su zona segura.
  No se añade una espera artificial ni se alteran sesión, permisos o rutas.
  Prueba SplashBrandingTest aprobada en Motorola desbloqueado; captura revisada
  en app/build/splash-branding.png: marca, nombre, frase y progreso visibles sin
  recortes. Esto verifica la carga Compose, no la renovación real de Cognito.

- Restaurar sesión cifrada y reintentar tras un fallo temporal sin borrar tokens.
- Renovación nativa con GetTokensFromRefreshToken; Google con oauth2/token.
  Se conserva o sustituye el refresh token según lo que devuelva Cognito, y se
  exige la misma cuenta. Mutex evita renovaciones simultáneas.
- Solo se limpia la sesión ante rechazo definitivo (NotAuthorized/UserNotFound,
  reutilización de refresh nativo o invalid_grant de OAuth), no por límite de
  solicitudes, error de configuración, desconexión o error temporal del servicio.
- Registro, confirmar código, recuperar, restablecer y reenviar código también
  desde Nueva contraseña. Espera de 60 segundos tras un envío exitoso. Es una
  ayuda del cliente, no sustituye los límites del servidor.
- Contraseñas fuera de SavedState; teclado de contraseña sin autocorrección.
- Intereses locales por usuario y sincronización automática con GET/PUT /perfil,
  sin alterar nombre ni avatar. Se recuperan al ingresar/abrir. Los cambios sin
  red quedan pendientes y Perfil permite reintentar. Invitado conserva solo local.
  En la primera actualización, las preferencias que ya estaban en este teléfono
  se consideran pendientes y se envían para no perderlas durante la migración.
- Las peticiones de Perfil están ligadas al usuario que las inició: cambiar de
  cuenta no puede enviar preferencias o foto del usuario anterior con otro token.
- Errores HTTP de planes muestran código/operación y sugieren comprobar el lab;
  si no se conoce el resultado de una escritura se pide actualizar antes de repetir.

No se añaden rutas ni se modifica Lambda en esta revisión. Utiliza las rutas de
Perfil que el usuario ya activó. La app normal sincroniza preferencias; las pruebas
automatizadas usan almacenamiento/API aislados, no contraseñas ni correos reales.

## Correo y contraseña: flujo principal confirmado

El 5 de noviembre el usuario habilitó USER_PASSWORD_AUTH y confirmó que funcionaron
registro, código de confirmación, ingreso, recuperación y acceso con contraseña nueva.
La lista siguiente queda como guía de regresión; códigos inválidos/reenvío reales y
renovación natural al vencer no se consideran confirmados por esa prueba principal.

1. Cognito → grupo existente → Clientes de aplicación → PlanYaAndroid. Comprobar
   que USER_PASSWORD_AUTH esté habilitado; no crear otro cliente ni agregar secreto.
   Google funciona independientemente de esa opción del formulario nativo.
2. Usar un correo de prueba propio, preferiblemente distinto del usado con Google.
   En la app Crear cuenta → nombre/correo/contraseña → recibir código → confirmar.
3. Ingresar por correo y contraseña. Cerrar/abrir app: conservar sesión y preferencias.
4. Cerrar sesión manualmente → Olvidé mi contraseña → recibir código → nueva
   contraseña repetida → ingresar con la nueva. El asistente no recibe contraseña
   ni código; no compartirlos por chat ni guardarlos en archivos del proyecto.
5. Probar código inválido y reenvío. No repetir solicitudes para provocar límites.
6. Cuando venza la sesión durante uso normal, la renovación debe ser transparente
   si el refresh sigue válido; si no, pedir acceso conservando borrador. No cambiar
   el reloj del teléfono ni reducir la duración de tokens para esta prueba.

La política de contraseña y recuperación por correo debe coincidir con el grupo.
Si Cognito exige MFA, desafíos o dispositivo recordado, se necesitan sus pantallas
y su DeviceKey; no desactivar medidas de seguridad para ocultar ese requisito.
GetTokensFromRefreshToken admite rotación de refresh: no es necesario apagarla
para esta versión, a diferencia del antiguo REFRESH_TOKEN_AUTH. Fuentes:
[API de AWS](https://docs.aws.amazon.com/cognito-user-identity-pools/latest/APIReference/API_GetTokensFromRefreshToken.html),
[guía de renovación](https://docs.aws.amazon.com/cognito/latest/developerguide/amazon-cognito-user-pools-using-the-refresh-token.html).

## Comprobación pendiente: intereses

El usuario confirmó conservar su selección sin internet y sincronizar correctamente
mediante Reintentar al recuperar conexión. Falta la misma cuenta en otro dispositivo.

1. Cuenta A → Perfil → Mis intereses → elegir y Continuar. Abrir Perfil: no debe
   quedar un aviso de sincronización pendiente.
2. Ingresar con la misma cuenta en otro dispositivo/instalación de prueba: recuperar
   las preferencias remotas. No desinstalar la app principal para hacer la prueba.
3. Sin red, cambiar una selección: debe mantenerse localmente y avisar que falta
   sincronizar. Volver a tener red → Perfil → Reintentar intereses, o reabrir app.
4. Cuenta B conserva sus propias preferencias; no hereda las de A ni del invitado.

Si dos dispositivos cambian intereses sin conexión, se envía la última selección
local sincronizada; los conjuntos no se fusionan automáticamente. El control de
revisión reintenta con el perfil actual sin pisar su nombre/avatar. Las actualizaciones
rápidas del mismo teléfono no pueden ser reemplazadas por una respuesta antigua.

## Últimas comprobaciones de planes

Confirmaciones posteriores del usuario: Cómo llegar y Compartir funcionaron;
creó la regla Lifecycle con prefijo fotos/ y vencimiento de versiones actuales
a 1 día. La creación de la regla no acredita todavía su ejecución ni borrado S3.

Plantilla de correo preparada: `backend/emails/codigo-planya.html`; pasos en
`CORREO_COGNITO.md`. No está aplicada todavía al grupo real de Cognito.
No requiere otro Deploy de Lambda ni cambiar el proveedor de envío.

El usuario confirmó edición, galería, participación/salida y respuesta de cancelación,
y dio conformidad tras la guía de cámara y quitar fotos. Falta inspeccionar el
borrado físico, caducidad y ejecución de S3 Lifecycle del prefijo fotos/. No aplicar esa
regla al prefijo perfiles/ de avatares persistentes. Guías FOTOS_AWS.md y PERFIL_AWS.md.

Orden de comprobación manual, sin borrar datos ajenos:

1. En un plan de prueba propio: Tomar foto → aceptar en la cámara → revisar que
   aparezca la miniatura → publicar → reabrir el detalle. No fotografiar documentos.
2. Editar ese mismo plan → quitar cada foto hasta 0/4 → guardar → reabrir. Debe
   verse sin galería y sin volver a mostrar la portada anterior. Las fotos del
   teléfono no deben borrarse. La prueba aislada no acredita el borrado físico S3.
3. Cancelarlo → actualizar Inicio con las dos cuentas de prueba. No debe aparecer
   entre planes activos; un detalle antiguo debe rechazar nuevas inscripciones.
4. Para vencimiento usar un plan de prueba de 30 minutos y dejar pasar su plazo
   natural; no cambiar el reloj del teléfono ni tocar la fecha en DynamoDB.
5. Verificar Lifecycle desde la consola S3 según FOTOS_AWS.md. No aplicar una
   configuración completa sobre reglas existentes: conservarlas y limitar fotos/.

Inicio filtra los registros paginados cargados: no se presenta como búsqueda global
de todos los registros del servidor. Cómo llegar/Compartir fueron confirmados por
el usuario con aplicaciones externas. No se declara 100% con pruebas aisladas.
