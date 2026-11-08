# US-08 — Coordinar un plan mediante chat temporal

Responsable: Compañero 2. Rama propuesta: `feature/chat`. Prioridad: alta.

> Entrega en la rama `rodrigo`: ver [US-08-ENTREGA.md](US-08-ENTREGA.md)
> (qué incluye, contrato final, pasos en AWS y pruebas).

**Como** participante confirmado de un plan,
**quiero** intercambiar mensajes con su grupo durante el tiempo permitido,
**para** coordinar el encuentro sin mantener una conversación permanente.

## Alcance y decisiones previas

No existe chat ni API de mensajes. Primera versión: texto, historial paginado,
enviar/reintentar, expiración y autorización. Adjuntos, audios, llamadas, notificaciones
push, confirmación de lectura y WebSocket no están incluidos en esta historia.

Antes de implementar acordar con el integrador: usar `expiresAt` del plan como
final del encuentro en esta versión y cerrar el chat en `chatExpiresAt = expiresAt
+ 7200` (segundos). Si el producto diferencia fin del evento de visibilidad del
plan, añadir `endsAt` explícito antes de programar. No sumar dos horas a startsAt.
Un plan cancelado bloquea nuevas operaciones de chat inmediatamente. Un registro
antiguo sin horario no obtiene una caducidad ficticia: mostrar chat no disponible.

Entrada: Detalle → Abrir chat, solo tras inscripción confirmada, incluido anfitrión.
Acceso secundario: Perfil → Mis conversaciones. El integrador agrega ambas rutas.

## Criterios de aceptación

1. **Dado** un usuario autenticado que participa, **cuando** abre chat,
   **entonces** ve mensajes reales y el plazo exacto de cierre, con banner temporal.
2. **Dado** un invitado o usuario ajeno, **cuando** llama directamente a la API,
   **entonces** recibe 401/403; ocultar el botón en Android no es la protección.
3. **Dado** un mensaje de texto válido de 1 a 1000 caracteres tras recortar,
   **cuando** pulso Enviar, **entonces** se guarda con autor y hora del servidor.
4. **Dado** texto vacío, **cuando** intento enviarlo, **entonces** no se envía.
   El teclado y botón siguen visibles; al enviarse correctamente se limpia el campo.
5. **Dado** que se pierde la respuesta de envío, **cuando** reintento con el mismo
   `clientMessageId`, **entonces** no aparece un duplicado. La UI distingue pendiente,
   enviado y fallido; no anuncia éxito antes de la confirmación.
6. **Dado** historial con varias páginas, **cuando** cargo anteriores, **entonces**
   no pierdo el texto escrito ni se duplican mensajes; el orden es estable.
7. **Dado** que salí del plan, **cuando** vuelvo al chat o hago una petición directa,
   **entonces** el servidor deniega lectura y escritura y la app cierra ese acceso.
8. **Dado** que se cumple chatExpiresAt, **cuando** consulto o intento enviar,
   **entonces** la API lo rechaza aunque DynamoDB aún conserve físicamente mensajes.
9. **Dado** un plan que terminó normalmente, **cuando** un participante abre su
   conversación antes del cierre, **entonces** la autorización usa la membresía
   retenida, no depende del GET público que oculta planes vencidos.
10. **Dado** un plan cancelado, **cuando** el chat se revalida, **entonces** se bloquea;
    no se calcula otra ventana de dos horas para prolongarlo.
11. **Dado** dos participantes en teléfonos distintos, **cuando** envían y actualizan,
    **entonces** ven los mismos mensajes, autores y orden, sin poder cambiar el autor.

## Diseño y tareas

Crear `ChatScreen`, `ChatViewModel`, `ChatUiState` en `presentation/screens/chat`;
modelos Message/ChatSummary, contrato ChatRepository, DTO/API/mapper e implementación
en las capas correspondientes. Burbujas propias/ajenas, nombre/hora, banner con
contador, campo multilínea, enviar y reintentar por mensaje; soportar texto grande.
No mostrar perfiles verificados que el servidor no haya verificado.

Para empezar: actualizar al abrir/regresar y botón Actualizar. Si se acuerda
consulta periódica, solo mientras el chat sea visible, con intervalo/límites
aprobados y cancelación al salir. No anunciar tiempo real si es actualización manual.

## Contrato PROPUESTO — no existe todavía

- GET `/conversaciones`: conversaciones autorizadas de la cuenta, paginadas.
- GET `/planes/{id}/mensajes?nextKey=...`: `{success,data:{items,nextKey,chatExpiresAt}}`.
- POST `/planes/{id}/mensajes`: cuerpo `{text,clientMessageId}`; devuelve mensaje
  confirmado `{id,planId,senderId,senderName,text,createdAt,expiresAt}`.
- Todas JWT; tomar sub del contexto validado. Nunca aceptar senderId del cuerpo.
- 400 inválido; 401 sin sesión; 403 ajeno/salió; 404 inexistente; 410 chat cerrado.
- Horario, cancelación y membresía se verifican en lectura y escritura del servidor.

Persistencia propuesta: tabla de mensajes separada (`PlanYaMensajes`) para no
mezclar mensajes con planes/perfiles, clave planId + identificador ordenable con
hora/UUID, idempotencia de clientMessageId por remitente/plan y TTL expiresAt numérico.
Su creación/permisos dependen del laboratorio y se aprueban antes de desplegar.
No habilitar TTL sobre todos los planes sin revisar dependencia de membresía:
el chat necesita consultar autorización hasta su propio cierre.

El cierre de acceso a las dos horas debe ser exacto por validación de servidor.
El borrado físico por TTL es asíncrono, no una garantía de autodestrucción puntual:
[documentación de AWS](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/TTL.html).
Si el requisito exige borrado físico exacto, acordar tarea de limpieza adicional,
permisos y presupuesto; no prometerlo solo por configurar TTL.

## Pruebas y entrega

Dos cuentas participantes y una ajena, autor falsificado, salir, cancelar, plan
terminado con chat aún abierto, chat cerrado, envío repetido, paginación, sin red
y reintento. Reloj inyectable en pruebas: no cambiar la hora del teléfono.
No escribir mensajes/tokens reales en logs ni persistir historial privado sin
política de limpieza al salir/cambiar de usuario. Entregar backend, rutas JWT,
configuración de TTL y evidencia; no basta con dibujar burbujas.
