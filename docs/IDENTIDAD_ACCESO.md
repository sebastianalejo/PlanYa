# Identidad y acceso — 5 de noviembre de 2026

## Entrega

- `app/src/main/res/drawable-nodpi/planya_wordmark.png`: logo del usuario con
  transparencia real. Preparado con la herramienta integrada image_gen,
  no con CLI ni una clave API externa. Copiado al proyecto, no depende de la
  carpeta de imágenes generadas. Se comprobó canal alfa y esquinas transparentes.
- Carga Compose usa el wordmark, sin repetir otro título PlanYa debajo.
- Acceso: tarjeta blanca, título «Conéctate al instante», opciones Google y correo.
  El formulario nativo solo aparece al elegir correo, conserva registro,
  confirmación, recuperación, reenvío y acceso invitado. Permite volver a elegir
  método y elimina la contraseña al volver; no persiste contraseñas al recrear.
- Icono de Google descargado sin modificaciones de su fuente oficial:
  https://developers.google.com/static/identity/images/g-logo.png
  Se muestra a color, sin tintado, sobre botón blanco. Referencia de marca:
  https://developers.google.com/identity/branding-guidelines
- No se añadieron enlaces de términos/privacidad sin documentos publicados.
- No se cambian flujos Cognito, secretos, backend ni recursos de AWS.

## Verificación

Compilación correcta; 50 casos JVM existentes aprobados. Suite seleccionada de
15 pruebas Android aprobada en Motorola (37,46 segundos): diseño normal y texto
ampliado a 1,6, selección de método, navegación, registro, recuperación, reenvío,
contraseñas no restauradas y Google con API aislada. No inicia sesión ni cambia
contraseñas reales. Capturas revisadas en `app/build/access-welcome.png`,
`app/build/access-email.png` y `app/build/splash-branding.png`.
El teclado puede reducir la vista; el formulario es desplazable con imePadding.

## Prompt final de edición del logo

Herramienta integrada, fondo transparente, dos imágenes recientes como entrada:
primera = logo a recortar, segunda = referencia de interfaz, no parte del logo.

```text
Use case: background-extraction. Edit target: the FIRST of the two user images, the small PlanYa logo (not the login screen reference). Extract only the indigo outlined rounded capsule containing the exact word PlanYa, preserving its rounded lettering, spelling, border, proportions and indigo color. Remove the entire white background including white inside the capsule and all outer canvas/border artifacts: genuinely transparent alpha everywhere except indigo logo strokes and letters. Clean antialiased edges, high resolution, no shadow, no new text, no redesign. Crop with small even transparent padding around logo. This is a transparent Android app wordmark asset. The SECOND user image is only context, do not include it.
```
