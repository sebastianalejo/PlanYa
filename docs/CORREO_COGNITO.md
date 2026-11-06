# Correo PlanYa: plantilla lista, aplicación pendiente en AWS

Archivo: `backend/emails/codigo-planya.html`.
Asunto propuesto: `Tu código de seguridad de PlanYa`.

Usa índigo, fondo perla y acento naranja del proyecto. No usa fuentes, imágenes,
enlaces ni servicios externos: se ve también sin descargar imágenes. Los estilos
van dentro de cada elemento para facilitar compatibilidad con clientes de correo.
El diseño es sencillo y fluido; su aspecto exacto depende del cliente de correo.

El texto es deliberadamente neutral: la plantilla de código también se usa en
recuperación de contraseña. No dice siempre «bienvenido», no promete una duración
del código que no se haya verificado y no incluye nombre ni datos personales.

## Aplicar sin cambiar cómo se envían los mensajes

1. Cognito → Grupos de usuarios → `User pool - kc8lzz`.
2. Busca «Plantillas de mensajes» (Message templates) en el menú del grupo.
3. En el mensaje de verificación, pulsa Editar. Antes de modificarlo, copia el
   asunto y contenido actuales a un respaldo privado para poder volver atrás.
4. Mantén verificación por **Código**. No cambiar a enlace: Android espera un código.
5. Asunto: `Tu código de seguridad de PlanYa`.
6. Copia completo el contenido de `backend/emails/codigo-planya.html` en el cuerpo
   del mensaje. Si el editor ofrece modo HTML/fuente, utiliza ese modo.
7. Conserva exactamente `{####}`: Cognito lo sustituye por el código real.
8. Guarda. No cambies remitente, proveedor de envío, región, cliente OAuth, rutas
   ni Lambda. No crear SES, dominio, suscripción ni otros recursos para esta plantilla.
9. Prueba un reenvío de confirmación en una cuenta propia sin confirmar y una
   recuperación en una cuenta propia existente. No publiques contraseña/código.
   Verifica que el código sustituye al marcador y que funciona dentro de la app.
10. Si el correo muestra etiquetas HTML literalmente, revisa el modo del editor;
    si no admite el contenido, restaura el respaldo y comparte solo captura de los
    campos de configuración (sin códigos ni credenciales).

El archivo local no actualiza Cognito automáticamente. No se ha aplicado esta
plantilla a AWS ni se ha verificado su recepción en Gmail/Outlook. Cambiar solo
la plantilla no crea otro servicio de envío; el consumo habitual de Cognito y
las restricciones del laboratorio continúan vigentes, sin garantía de coste cero.

Fuente oficial: https://docs.aws.amazon.com/cognito/latest/developerguide/cognito-user-pool-settings-message-customizations.html
