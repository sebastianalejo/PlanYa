# US-10 — Completar mi perfil, actividad y confianza

Responsable: Compañero 4. Rama propuesta: `feature/perfil`. Prioridad: media/alta.

**Como** usuario registrado de PlanYa,
**quiero** administrar mi perfil y consultar mi actividad y reputación real,
**para** controlar mi información y conocer la confianza que genero en la comunidad.

## Lo que YA existe: no rehacer

`presentation/screens/profile/ProfileScreen.kt`, `EditProfileScreen.kt` y
`ProfileViewModel.kt`; `data/profile/ProfileRepository.kt`; GET/PUT `/perfil` y
POST `/perfil/foto`. Nombre/avatar, correo de cuenta, guardado con revisión,
conflictos, borrador, intereses sincronizados, Mis planes/Me uní, ubicación y
cierre de sesión con confirmación. El avatar persiste en `perfiles/`; NO aplicar
la regla temporal `fotos/` a esa carpeta. La foto de Google no prueba identidad.

Trabajo pendiente: pulir/quitar avatar con prueba real, actividad y confianza
respaldadas por servidor, e incorporar accesos a Chat/Beneficios cuando estén listos.
No agregar contraseña editable ni cambiar email Cognito mediante PUT `/perfil`.

## Criterios de aceptación

1. **Dado** mi usuario autenticado, **cuando** abro Perfil, **entonces** se cargan
   mis datos reales y nunca los de la cuenta anterior o del invitado.
2. **Dado** un nombre válido o foto nueva, **cuando** guardo y reabro,
   **entonces** persisten y no se modifican mis intereses sin que yo lo pida.
3. **Dado** mi avatar guardado, **cuando** lo quito y guardo, **entonces** reaparece
   el avatar alternativo al reabrir, conservando nombre/intereses. Quitar la imagen
   no borra la foto original de la galería del teléfono.
4. **Dado** un borrador, **cuando** intento salir, **entonces** puedo conservarlo
   o descartarlo explícitamente. Un fallo de red mantiene los cambios para reintentar.
5. **Dado** un perfil editado desde otro dispositivo, **cuando** guardo una revisión
   antigua, **entonces** veo conflicto y puedo recargar con confirmación, sin pisarlo.
6. **Dado** mi actividad, **cuando** veo organizados/asistidos, **entonces** los
   números provienen del servidor; estar inscrito no se cuenta como asistencia.
7. **Dado** que aún no tengo evaluaciones, **cuando** veo reputación,
   **entonces** aparece «Sin valoraciones», no cinco estrellas por defecto.
8. **Dado** que hay valoraciones válidas, **cuando** se muestra confianza,
   **entonces** aparece promedio 1–5 y cantidad de opiniones calculados por servidor.
9. **Dado** un usuario sin verificación válida, **cuando** abre su perfil,
   **entonces** no obtiene check azul por iniciar sesión con Google o pulsar un botón.
10. **Dado** un participante autorizado de un encuentro terminado y con asistencia
    acreditada, **cuando** valora a otro, **entonces** solo envía 1–5 estrellas una
    vez por persona/plan y no puede valorarse a sí mismo. Se valida en servidor.
11. **Dado** Cerrar sesión, **cuando** cancelo, **entonces** permanezco conectado;
    si confirmo, se limpian los datos privados de la UI y no se borran mis planes.
12. **Dado** los módulos Chat/Beneficios integrados, **cuando** pulso sus opciones,
    **entonces** abren pantallas reales y Volver regresa al perfil, sin duplicar barras.
    Antes de integrarlos, no mostrar botones que prometen una función inexistente.

## Decisiones pendientes que deben cerrarse antes de implementar confianza

- Definir «asistencia acreditada» junto al compañero de Beneficios y al integrador.
- Definir qué prueba habilita el check y qué rol puede aprobarlo. Hasta entonces,
  mostrar estado no verificado sin inventar un proceso. La carga/revisión de DNI
  y documentos sensibles NO forma parte de esta historia.
- Reputación: promedio de votos válidos + cantidad, sin algoritmo secreto ni
  porcentajes de confiabilidad inventados. Evaluaciones en la app posterior al plan;
  no añadir comentarios públicos ni perfil público completo en esta primera versión.

## Tareas y contrato

Extender las clases actuales, no crear otro ProfileRepository o tabla de perfiles
paralela. Extraer casos de uso de estadísticas/reputación si se requieren. Preservar
`expectedRevision`, upload privado, borradores y aislamiento expectedUserId.

PROPUESTO: GET `/perfil/actividad`, GET `/perfil/confianza` y POST
`/planes/{id}/valoraciones` con `{targetUserId,stars,requestId}`; todas JWT.
Solo las dos últimas incorporan datos de confianza: el propietario no puede mandar
`verified=true`, contadores o su puntuación en PUT `/perfil`. El servidor rechaza
campos privilegiados. Respuestas siguen `{success,data}` y errores comunes.

El chat puede necesitar membresía después de que el plan desaparezca del feed;
las estadísticas de asistencias pueden requerir agregados persistentes. No
calcular actividad histórica escaneando solo planes activos ni romper la limpieza
de datos para conservar estadísticas. Acordar evento/asistencia y retención mínima.

## Pruebas y entrega

Regresión de editar nombre/foto, quitar avatar, conflictos, sin red, invitado,
cambio de cuenta y cerrar/cancelar cierre. Para ampliación: estadísticas con cero
actividad, sin opiniones, media correcta, voto fuera de rango, doble voto, auto-voto,
usuario ajeno y modificación fraudulenta de verificación. Entregar criterios de
verificación/asistencia aprobados y prueba real de las nuevas rutas; no screenshots
con contadores fijos. Si falta la política o backend, marcarlo pendiente, no 100%.
