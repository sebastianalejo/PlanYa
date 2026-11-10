# PlanYa — pantallas, botones y flujos

Revisión: 5 de noviembre de 2026. Las pantallas de Stitch son la referencia visual.
Se mantienen la Home, la paleta y las fuentes acordadas.

CIERRE: el usuario confirmó vencimiento del plan y las tres últimas validaciones:
renovación de sesión, intereses entre dispositivos y limpieza S3. Sus seis pantallas
quedan aceptadas al 100% del alcance actual implementado. Son confirmaciones del
usuario, no inspección directa de sus credenciales, segundo teléfono o S3.
La tabla de abajo es el estado actual; los pendientes de párrafos históricos
previos al cierre se conservan como contexto, no como nueva solicitud de repetirlos.

Último avance: renovación de sesión que no borra credenciales por fallos
temporales, reintento de sesión guardada, validación/reenvío de códigos y
sincronización automática de intereses. Errores de operaciones distinguen fallos
HTTP de AWS de una conexión no confirmada. Esta entrega solo cambia Android:
usa las rutas de Perfil ya existentes, sin otra actualización de Lambda.
Compilación y 50 pruebas JVM aprobadas. Repetición completa de 39 pruebas de
instrumentación aprobada con el Motorola desbloqueado (104,72 segundos).
Incluye los casos visuales antes bloqueados por pantalla apagada y la renovación
nativa actualizada; detalles en CIERRE_SEIS_PANTALLAS.md. El usuario confirmó
registro, confirmación, ingreso por correo y recuperación de contraseña reales.
El usuario confirmó conservar intereses sin conexión y sincronizarlos mediante
Reintentar al recuperar internet. La sincronización entre dispositivos sigue
pendiente de verificación.

Revisión adicional: 49 pruebas Python y 13 pruebas Android de fotos, galería y
participación aprobadas. Quitar todas las fotos hasta 0/4 está cubierto visualmente;
la comprobación con S3 real sigue pendiente. El usuario configuró Lifecycle fotos/
a 1 día; falta observar su ejecución. Plantilla de correo preparada en
`backend/emails/codigo-planya.html`; el usuario indicó que quedó bien tras la guía.
La configuración cloud no se inspeccionó directamente; ver CORREO_COGNITO.md.

Última revisión Android: carga y acceso con logo transparente, elección Google/correo
y formularios conservados (15 pruebas seleccionadas aprobadas). Al regresar de
otra app se reconsulta Inicio o el detalle activo, sin reemplazar borradores ni
sondeo de red en segundo plano. Un detalle definitivamente no disponible se retira
también del listado; un error temporal no borra los datos. La confirmación se cierra
si vence el plan. Ver CIERRE_SEIS_PANTALLAS.md para evidencia y pendientes reales.

Confirmado por el usuario: ingreso Google, perfil con nombre/foto persistentes,
edición de planes, galería de cuatro fotos/portada y quitar una, unirse/salir
liberando cupo, y respuesta final de cancelación exitosa tras encender el laboratorio.
También habilitó USER_PASSWORD_AUTH y confirmó el flujo completo de correo:
crear cuenta, confirmar código, ingresar, recuperar contraseña e ingresar con la nueva.

## Estado real del inventario

Guías: [VERIFICAR_PLANES.md](VERIFICAR_PLANES.md),
[PERFIL_AWS.md](PERFIL_AWS.md) y [CIERRE_SEIS_PANTALLAS.md](CIERRE_SEIS_PANTALLAS.md).
Las pruebas automatizadas usan datos aislados; no envían correos ni modifican
contraseñas reales. La sincronización de preferencias es parte de la app normal
al ingresar, abrirla o guardar una selección.

Los porcentajes son estimaciones funcionales, con el mismo peso por pantalla.
Una interfaz implementada o una prueba local no acredita una operación real en AWS.
Los avisos de próxima disponibilidad no cuentan como funciones terminadas.

| Responsable | Pantalla | Android implementado | Comprobado / pendiente | Avance orientativo |
| --- | --- | --- | --- | --- |
| Steve | 1. Splash | Logo transparente, redirección, restauración y renovación de sesión. | Diseño probado; reapertura y renovación confirmadas por usuario. | 100% del alcance actual |
| Steve | 2. Acceso / Registro | Google y correo, registro, código, recuperación, reenvío y manejo de errores. | Flujo real y renovación confirmados por usuario. Apple no forma parte de esta entrega. | 100% del alcance actual |
| Steve | 3. Intereses | Selección, persistencia por cuenta, sincronización y reintento sin conexión. | Usuario confirmó reintento y misma cuenta entre dispositivos. | 100% del alcance actual |
| Steve | 4. Inicio | Listado, búsqueda/filtros sobre páginas cargadas, ubicación, orden y recarga al regresar. | Usuario confirmó caducidad del plan. La búsqueda global del servidor no está implementada. | 100% del alcance actual |
| Equipo | 5. Mapa | Solo acceso en la barra, con aviso de no disponibilidad. | Mapa, pines reales, selección, ficha y abrir detalle; no cuenta como pantalla implementada. | 0% |
| Steve | 6. Crear plan | Crear/editar, cámara/galería hasta 4 fotos, portada, retirar, revisar y publicar. | Usuario dio conformidad a fotos y limpieza S3. Usa selector nativo/mapa; contrato Places por placeId para publicación no incluido. | 100% del alcance actual |
| Steve | 7. Detalle | Galería, edición, participación/salida, cancelar, cupos, Cómo llegar y Compartir. | Usuario confirmó los flujos y vencimiento; regresiones automatizadas aprobadas. | 100% del alcance actual |
| Equipo | 8. Chat temporal | Sin pantalla implementada. | Acceso de participantes, mensajes y expiración efectiva. | 0% |
| Equipo | 9. Beneficios | Sin pantalla implementada. | Cupones, condiciones, QR, canje y vencimiento. | 0% |
| Base aquí / completar equipo | 10. Perfil | Pantalla propia y editor: nombre/avatar privados, guardado, borrador y conflictos. Opciones de planes, ubicación, intereses con sincronización/reintento, sesión. Actividad y confianza de solo lectura desde servidor; estado de verificación autorizado. | Nombre/foto y reintento de intereses confirmados. Faltan quitar avatar real, valoraciones reales/asistencia acreditada, aprobación de verificación y accesos Chat/Beneficios. | 85% (lectura local; pendientes de política/AWS) |

Promedio orientativo de las diez pantallas: **69%** (seis de Steve: **100% del
alcance actual aceptado**). No equivale a certificar producción, todos los casos
posibles o el producto original completo. Otros módulos conservan su estado real.
Las seis tienen interfaz y lógica; Google está confirmado por el usuario y
las capturas y confirmaciones del usuario acreditan creación/listado/edición,
galería, perfil, inscripción y salida. No equivalen a probar todos los casos
de concurrencia, caducidad o limpieza física en AWS.
No hay ninguna inscripción, publicación ni cuenta simulada que se presente como real.

Actualización Perfil US-10 (9 de noviembre de 2026): se agregaron
`GET /perfil/actividad` y `GET /perfil/confianza` con JWT, y `GET /perfil` informa
un estado de verificación que solo puede escribir el servidor/admin. Android muestra
tres contadores (organizados, me uní, asistencias acreditadas), «Sin valoraciones»
cuando no hay opiniones, promedio/cantidad cuando existen y check azul solo con
`VERIFIED`. Quitar avatar conserva nombre e intereses; Google no otorga verificación.
No se implementó la creación de valoraciones ni la acreditación de asistencia: quedan
pendientes de la política conjunta con Beneficios/integrador. Chat y Beneficios
permanecen ocultos hasta que existan sus rutas reales. Las rutas nuevas requieren
Deploy manual en AWS y todavía no se verificaron contra la cuenta real.

Histórico previo: 34 pruebas unitarias y 37 de instrumentación aprobadas.
Para esta mejora se recompiló la app: pasaron las 34 unitarias y 15 pruebas
distintas de instrumentación seleccionadas en el Motorola (incluyen 6 de Perfil).
Se verifican
opciones de Perfil, confirmación/cancelación de cierre, invitado, bloqueo durante
cierre, filtro solicitado desde Perfil y navegación de regreso desde Intereses.
Las pruebas anteriores cubren ubicación sin red real, borrador/restauración,
revisión previa, filtros y autenticación aislada.
No demuestran escrituras reales de AWS ni todos los casos del flujo de correo.

Refinamiento visual: confirmación sustituida por panel inferior blanco con foto,
datos agrupados y acción naranja; texto grande probado sin ocultar confirmación.
Los filtros de cuenta van después de la cabecera. El anfitrión tiene acción Editar
activa, no un botón deshabilitado. Distancia sin origen solicita ubicación, sin
confundirla con el punto del encuentro. Barra del sistema con iconos oscuros.

Precaución al verificar: en este entorno la tarea Gradle connectedDebugAndroidTest
retira la instalación de pruebas al finalizar. Puede perder estado local de la
app debug; no usarla sobre borradores del usuario sin respaldo/aviso. Para pruebas
puntuales de UI se puede instalar con adb install -r y ejecutar am instrument,
con preferencias/datos de prueba aislados, evitando desinstalar la app principal.

## Botones y destinos de las seis pantallas

| Pantalla / control | Acción y destino | Validación / estado |
| --- | --- | --- |
| Splash | Recupera sesión; abre Acceso, Intereses o Inicio. | No espera un tiempo artificial. Si no recupera sesión, ofrece Acceso. |
| Acceso: Ingresar | Autentica en Cognito; abre Intereses si falta completarlos y luego retoma el destino solicitado. | Requiere correo y contraseña. Pendiente de configurar Cognito. |
| Acceso / Registro: Continuar con Google | Abre navegador mediante Cognito y retoma Intereses/destino pendiente. | PKCE, state y nonce. Ingreso y nombre confirmados por el usuario; pruebas automatizadas aisladas sin cuentas reales. |
| Acceso: Cancelar acceso con Google | Descarta intento pendiente sin crear sesión. | Un regreso sin intento válido o repetido no inicia sesión. |
| Acceso: ojo de contraseña | Mostrar/ocultar texto. | No guarda la contraseña en preferencias ni en el estado persistido de Android. |
| Acceso: Quiero crear una cuenta | Cambia al formulario con nombre, correo y repetición de contraseña. | No crea una cuenta hasta enviar el formulario. |
| Registro: Crear cuenta | Solicita SignUp; pasa a Confirmar correo o vuelve a Ingresar si ya está confirmada. | Contraseñas coincidentes y requisitos indicados. |
| Confirmación: Confirmar correo | Envía el código y vuelve a Ingresar. | Rechaza código vacío; muestra errores/vencimiento. |
| Confirmación: Reenviar código | Solicita otro código al correo. | Evita doble envío mientras carga; el proveedor aplica sus límites. |
| Acceso: Olvidé mi contraseña | Abre recuperación por correo. | Solicitud explícita del usuario. |
| Recuperación: Enviar código | Abre el formulario de nueva contraseña. | Requiere correo válido. |
| Restablecimiento: Guardar contraseña | Confirma código y contraseña; vuelve a Ingresar. | Código, política de contraseña y coincidencia de repetición. |
| Acceso: Volver a ingresar / Atrás | Regresa al modo Ingresar desde un subformulario. | Durante una solicitud se evita repetirla. |
| Acceso: Explorar planes sin cuenta | Abre Intereses o retoma Inicio/borrador. | Puede leer planes. Publicar y unirse requieren cuenta. |
| Intereses: cada categoría | Alterna selección. | No cambia categorías del plan ni registros de AWS. |
| Intereses: Continuar | Guarda selección local y continúa al destino pendiente. | Requiere al menos un interés. |
| Intereses: Elegir más adelante | Guarda la decisión de omitir y continúa. | Puede cambiarse desde Mi cuenta. |
| Inicio: ubicación | Abre selector de dirección. | GPS opcional, búsqueda regional, selección y confirmación explícita. |
| Ubicación: Usar mi ubicación | Pide permiso de primer plano y obtiene posición. | No solicita segundo plano. Si falla, conserva búsqueda manual. |
| Ubicación: escribir / lupa del teclado | Sugiere calles, números, zonas o lugares. | Espera 500 ms; cancela búsquedas anteriores y filtra resultados locales. |
| Ubicación: borrar / cerrar | Limpia el texto o cierra sin cambiar la ubicación. | No selecciona automáticamente el primer resultado. |
| Ubicación: buscador único | Prioriza la zona sin selector de proveedor ni botones de búsqueda cercana/global. | Google utiliza sesgo de proximidad; alternativa nativa interna en caso de fallo. |
| Ubicación: Elegir en mapa | Abre selector vertical con mapa grande, búsqueda y ubicación actual. | No sustituye el mapa de planes del equipo. Pin consulta calle con Geocoder; no garantiza número exacto. |
| Mapa: Mi ubicación | Solicita permiso y centra en posición actual. | Al abrir solo usa GPS automáticamente si ya hay permiso y no hay punto previo. Sin ubicación muestra contexto Lima explícito. |
| Mapa: elegir punto | Confirma dirección del pin o referencia corregida, conserva coordenadas y actualiza Inicio o borrador. | Si no se resuelve la calle permite escribir; jamás inventa número. No confirma con un mero toque. |
| Inicio: Mis planes / Me uní | Separa autoría de participación según IDs del usuario. | Anfitrión no se duplica en Me uní. Solo registros cargados; requiere sesión para mostrar estos filtros. |
| Inicio: Para ti / Más cercanos / Por vencer | Prioriza intereses, distancia geográfica o vencimiento. | Cercanía requiere ubicación; valores desconocidos se ordenan al final. |
| Crear/Editar: revisar | Presenta resumen y exige Confirmar publicación/Confirmar cambios. | Seguir editando no envía nada a AWS. Borrador recuperable tras recreación del estado Android. |
| Detalle: Cómo llegar | Abre URL oficial de Maps con destino en coordenadas o dirección. | No usa Routes API ni clave adicional. Maneja ausencia de aplicación. |
| Detalle: Compartir plan | Abre menú Android con título, descripción, punto y ID. | Sin inventar URL pública o deep link de PlanYa. |
| Ubicación: elegir sugerencia | Abre Confirmar dirección con la dirección completa disponible. | Si falta número, se indica que solo se obtuvo calle/zona. |
| Confirmar dirección: usar | Guarda dirección/posición seleccionada y referencia opcional. | No inventa números ni mueve coordenadas para aparentar precisión. |
| Confirmar dirección: cambiar/completar | Vuelve al buscador con la dirección encontrada. | Permite escribir la calle y número completos. |
| Inicio: buscar / categorías / Todos | Filtra título, descripción y categoría de los registros cargados. | No es una búsqueda global del servidor. |
| Inicio: Actualizar / Reintentar | Consulta AWS de nuevo. | Muestra carga y error; no duplica solicitudes activas. |
| Inicio: Cargar más | Obtiene la página indicada por nextKey. | Solo aparece si existe otra página; elimina IDs repetidos. |
| Inicio: tarjeta / Ver detalles | Abre Detalle por ID. | Consulta independiente, conserva respaldo del listado ante fallo de red. |
| Barra: Inicio / Crear | Vuelve a Inicio o abre Crear plan. | Un invitado puede preparar el borrador; Publicar solicita acceso. |
| Barra: Mapa | Informa que aún no está disponible. | Pendiente del compañero responsable. |
| Barra / avatar: Perfil | Abre Mi perfil, con pestaña Perfil seleccionada. | Nombre/correo de la sesión real; sin estrellas, verificación ni estadísticas inventadas. |
| Perfil: Ingresar o crear cuenta | Invitado: abre Acceso y vuelve al perfil al terminar el recorrido de acceso. | Sin crear una sesión ficticia. |
| Perfil: Mis planes / Me uní | Vuelve a Inicio con el filtro propio o de participación. Invitado: pide ingreso. | Solo páginas cargadas; no se presentan como estadísticas totales de AWS. |
| Perfil: Mis intereses | Abre selección y regresa al perfil al guardar. | Guardado local separado por usuario. |
| Perfil: Mi ubicación | Abre el selector compartido; al confirmar actualiza la ubicación usada en Inicio/Detalle. | No publica esa dirección en un perfil remoto. |
| Perfil: Editar perfil | Abre pantalla propia con nombre, correo de solo lectura y foto. | Debe cargarse el perfil remoto; no modifica la cuenta de Google. |
| Editar perfil: Guardar perfil | Valida nombre, sube foto si cambió y PUT /perfil; vuelve al perfil con confirmación de guardado. | Solo usuario verificado, revisión optimista, errores conservan borrador. Requiere activar rutas AWS. |
| Editar perfil: Quitar foto / Volver | Marca retirada para próximo guardado; Volver pide confirmar descarte si hay cambios. | No borra remotamente hasta guardar. Nunca borra la galería original del teléfono. |
| Editar perfil: Recargar perfil | Confirma sustitución del borrador después de conflicto. | Si la carga falla conserva cambios; sin datos cargados no permite guardar. |
| Perfil: Cerrar sesión | Solicita confirmación explícita; Quedarme no hace cambios. Al confirmar borra sesión local cifrada e intenta revocar el refresh token; espera el resultado y abre Acceso. | No elimina la cuenta ni planes. Durante el proceso bloquea opciones para evitar acciones simultáneas. |
| Crear: categoría / inicio / duración | Elige valores del formulario. | Inicio anterior al vencimiento; duración 30–120 min. |
| Crear: dirección | Abre el selector y confirma el punto del encuentro. | Se avisa que aparecerá en el plan. |
| Crear: Revisar plan → Confirmar publicación | Valida; abre resumen; la confirmación pide acceso si falta, sube la foto elegida y envía POST. Abre Detalle con aviso de publicación exitosa. | Mantiene borrador durante acceso y ante fallos. Idempotencia para reintento. Publicación con foto observada en capturas del usuario. |
| Crear: cancelar / volver / Atrás | Vuelve; si hay cambios, confirma descartarlos. | Seguir editando conserva el borrador. |
| Editar: Revisar cambios → Confirmar cambios | Muestra resumen antes de PUT; actualiza Detalle con aviso de guardado. | Solo propietario en servidor; conserva horario y vencimiento; no reduce cupos por debajo de asistentes. Falta prueba real de edición. |
| Detalle: Volver / Inicio | Regresa al listado. | Búsqueda y categoría se conservan durante navegación. |
| Detalle: Reintentar | Repite la consulta por ID. | Un 404 de plan vencido/eliminado no se presenta como una reserva válida. |
| Detalle: Unirme ahora | Solicita acceso si falta; POST de inscripción y actualiza participantes. | Sin cupos, vencido o sin horario: deshabilitado/rechazado. Pendiente de despliegue real. |
| Detalle: Salir del plan | Confirma y libera el cupo mediante DELETE de participación. | El anfitrión no abandona su propio plan: debe cancelarlo. |
| Detalle: Editar mi plan | Abre editor con datos actuales. | Visible para anfitrión; el servidor vuelve a comprobar propiedad. |
| Detalle: Cancelar este plan | Confirma y cancela lógicamente el encuentro. | Desaparece de lecturas; no es borrado físico inmediato de DynamoDB. |

## Flujos que deben verificarse con AWS antes de cerrar

La lista paso a paso con dos cuentas está en VERIFICAR_PLANES.md. Los porcentajes
no suben al 100% por aprobar pruebas con repositorios aislados.

1. Registrarse → recibir código → confirmar → ingresar → elegir intereses → Inicio.
2. Ingresar → cerrar app → abrir → restaurar sesión; renovar al vencer o pedir acceso.
3. Recuperar contraseña → código → nueva contraseña → ingresar con la nueva.
4. Invitado → Crear → completar borrador → Revisar plan → Confirmar publicación → Acceso → volver al mismo borrador → revisar/confirmar → Detalle → Inicio actualizado.
5. Cuenta A crea → edita → conserva vencimiento. Cuenta B no puede editar ni cancelar ese plan.
6. Cuenta B se une → aparece una sola vez → repetir no duplica → salir libera un cupo.
7. Dos cuentas disputan último cupo → solo una lo obtiene; conflicto actualiza datos.
8. Anfitrión cancela → deja de listarse y de admitir inscripciones.
9. Vence expiresAt → deja de listarse y se rechazan inscripciones; TTL no controla la vigencia instantánea.

## Reparto para los compañeros

| Módulo | Botones / contrato que deberá completar |
| --- | --- |
| Mapa | Centrar ubicación, elegir pin, abrir ficha, abrir el mismo Detalle por ID. Usar coordenadas del contrato común. |
| Chat | Abrir sala de un plan confirmado, enviar/recibir mensajes, errores, volver y cerrar por expiración. Validar membresía en servidor. |
| Beneficios | Listar cupones, ver condiciones, abrir QR, canjear, evitar doble uso y mostrar vencimiento. Definir acceso desde Perfil. |
| Perfil | Datos reales, foto, estadísticas, confianza/verificación, preferencias remotas y ajustes. Integrar la sesión Cognito común. |

Mapa, Chat y Beneficios no se cuentan como terminados por tener un acceso en la barra.
Perfil ya dispone de una base funcional; el equipo completa datos remotos y funciones restantes.

## Bloqueo externo actual

Corrección de ubicación: Inicio muestra la dirección completa confirmada, con
dos líneas disponibles, en lugar de otro nombre de zona. Confirmar actualiza la
misma posición usada para distancias y conserva la elección cifrada en este
teléfono al volver a abrir la app; no actualiza un perfil remoto. La dirección no
se incluye en copias de seguridad. Cerrar el selector sin confirmar no la cambia.
Google Places tiene integración de interfaz en Inicio/Detalle, con buscador único
y alternativa nativa interna. El usuario muestra el mapa funcionando. Las pruebas
automatizadas usan fuentes sin red. Guarda solo el placeId y recupera bajo petición al reabrir,
sin precarga que consuma cuotas. Crear/Editar conserva Geocoder hasta adaptar el
contrato de publicación. Detalles y restricciones en GOOGLE_PLACES.md.
Estos cambios no elevan los porcentajes de las operaciones de AWS sin verificar.

El User Pool, cliente móvil y proveedor Google ya existen según las capturas del
usuario; los IDs están en AuthConfig.kt. El usuario confirma ingreso Google y
muestra las seis rutas de escritura con autorizador JWT. No equivale a comprobar
todos los casos de permisos ni el recorrido entre dos cuentas.
La app no contiene claves IAM ni un secreto de cliente. GET /planes y GET
/planes/{id} estaban desplegados; el usuario también desplegó Lambda y las rutas
de escritura. Creación con foto observada; edición, participación y cancelación
reales siguen pendientes de comprobación. Guía de configuración: AUTENTICACION_AWS.md.
