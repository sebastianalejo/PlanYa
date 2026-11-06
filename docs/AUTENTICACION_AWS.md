# Activar las cuentas de PlanYa en AWS

Cognito es el servicio que registra usuarios, verifica el correo y comprueba
contraseñas. DynamoDB guarda planes; no se guardan contraseñas en esa tabla.
El usuario creó el grupo real y agregó Google como proveedor. Los IDs públicos
ya están en AuthConfig.kt. El usuario confirmó ingreso Google y reapertura de
sesión/perfil. El recorrido real de correo/código/recuperación sigue pendiente.
La lista vigente de comprobaciones está en CIERRE_SEIS_PANTALLAS.md.

## Recursos confirmados y paso pendiente del cliente

- Grupo: User pool - kc8lzz, ID `us-east-1_G3sywIYqT`.
- Cliente móvil público: PlanYaAndroid, ID `2tvt69ic49crhu93ank9gkklv0`, sin secreto.
- Dominio: `https://us-east-1g3sywiyqt.auth.us-east-1.amazoncognito.com`.
- Retorno Android: `com.example.planya://auth/callback`.
- Google OAuth web: PlanYaGoogle, en el proyecto Novatech. Su secreto pertenece
  únicamente a la configuración del proveedor Google en Cognito, nunca al APK.
- Se comprobó por GET el documento público OpenID Connect: issuer y endpoints
  coinciden con los configurados. No se inició sesión ni se crearon usuarios.

En Clientes de aplicación > PlanYaAndroid > Páginas de inicio de sesión > Editar:
habilitar Google (y directorio Cognito si se usará correo/contraseña), Código de
autorización y ámbitos openid, email, profile; conservar el retorno exacto.
No habilitar flujo implícito ni cliente máquina a máquina.

En Google Auth Platform, si el acceso indica usuarios de prueba, añadir el correo
con el que se hará la prueba. Las opciones de público/marca son compartidas por
los clientes de Novatech: no cambiar otras configuraciones sin coordinarlo.

## Cómo funciona Continuar con Google

AppAuth Android 0.11.1 abre el navegador externo con identity_provider=Google,
response_type=code, state, nonce y PKCE S256 nuevos por intento. La actividad de
retorno acepta solo el esquema/host/ruta acordados. El intento se conserva en
SavedStateHandle para recreación de Android y se elimina al consumir/cancelar.
Un código debe coincidir con el intento pendiente y tener menos de 10 minutos.
No se usa WebView, un secreto móvil ni la clave de Maps.

AppAuth intercambia el código; el repositorio comprueba nonce y claims de
Cognito (issuer, audiencia, token_use y vencimiento) antes de guardar la sesión
cifrada. La API sigue siendo responsable de verificar criptográficamente el
JWT mediante su autorizador. La sesión Google renueva desde /oauth2/token;
la sesión nativa usa GetTokensFromRefreshToken. Cerrar sesión borra datos locales
e intenta revocar el refresh token; no cierra globalmente la cuenta de Google
del navegador. La próxima solicitud pide selección de cuenta.

## Primera comprobación en la cuenta estudiante

En la búsqueda de la consola AWS, escribir Cognito y abrir Amazon Cognito.
Trabajar en us-east-1, la misma región de PlanYaApi. Si aparece Acceso denegado,
el laboratorio no permite crear ese recurso: se necesita habilitación del
docente/laboratorio antes de usar esta integración. No desproteger las rutas
como alternativa a ese permiso.

## Configuración del grupo y cliente

- Grupo de usuarios existente: User pool - kc8lzz. No crear otro.
- Aplicación móvil / cliente público: PlanYaAndroid, sin secreto de cliente.
- Acceso con correo electrónico; verificación automática del correo.
- Autorregistro habilitado. Nombre como atributo editable; email verificado.
- Recuperación por correo verificado.
- Contraseña: mínimo 8, mayúscula, minúscula, número y símbolo.
- Flujo del formulario nativo: ALLOW_USER_PASSWORD_AUTH. La renovación utiliza
  GetTokensFromRefreshToken y conserva el refresh anterior si no hay rotación,
  o guarda el nuevo si la hay. No hace falta desactivar la rotación para esta versión.
- Revocación de tokens habilitada. Si ya hay MFA/desafíos/dispositivos recordados,
  no desactivarlos para evitar implementar sus pantallas; requieren soporte adicional.
- El código cubre correo/contraseña y Google mediante navegador/Cognito. Apple
  sigue pendiente. No configurar MFA obligatorio ni desafíos personalizados sin
  implementar sus pantallas.
- La captura inicial del cliente mostraba SRP pero no USER_PASSWORD_AUTH. Para
  el formulario nativo, habilitar ALLOW_USER_PASSWORD_AUTH: solo habilitar Google
  en las páginas de inicio no activa ese flujo nativo.

Los identificadores públicos y dominio ya se copiaron a AuthConfig.kt. No son
claves IAM ni contraseñas. No hace falta un Identity Pool.

## API Gateway

Crear autorizador JWT PlanYaAcceso en PlanYaApiHttp:

- Fuente de identidad: $request.header.Authorization.
- Emisor: https://cognito-idp.us-east-1.amazonaws.com/USER_POOL_ID.
- Audiencia: APP_CLIENT_ID.
- Este cliente envía el ID token. No añadir authorizationScopes de OAuth a
  estas rutas; Lambda exige token_use=id y obtiene la identidad de los claims
  validados por Gateway. No se acepta ownerId recibido en el cuerpo.

Crear estas rutas con integración PlanYaApi, payload 2.0 y autorizador JWT:

| Método | Ruta | Acción |
| --- | --- | --- |
| POST | /planes | Publicar con la identidad verificada. |
| PUT | /planes/{id} | Editar, solo anfitrión. |
| DELETE | /planes/{id} | Cancelar, solo anfitrión. |
| POST | /planes/{id}/participantes | Unirse, respetando cupos y vigencia. |
| DELETE | /planes/{id}/participantes/me | Salir y liberar cupo. |

GET /planes y GET /planes/{id} se mantienen públicos para explorar sin cuenta.
Confirmar que API Gateway tiene permiso para invocar Lambda en las nuevas rutas.
El rol de Lambda usa Scan, GetItem y PutItem en PlanYaPlanes; las escrituras
usan condiciones para evitar pisar cambios concurrentes.

## Despliegue y prueba real

1. Copiar completo backend/lambda_function.py en PlanYaApi y pulsar Deploy.
2. Crear autorizador y rutas; comprobar despliegue automático de $default.
3. Completar AuthConfig.kt y compilar/instalar Android.
4. Registrar una cuenta propia, confirmar correo e ingresar.
5. Publicar un plan con dirección/cupo/horario reales y comprobarlo en DynamoDB.
6. Probar inscripción con una segunda cuenta; probar permisos, salir y cancelar.

No se han ejecutado esos pasos de creación de cuentas ni escrituras en la
cuenta AWS desde el agente. Las pruebas locales y del teléfono usan datos
aislados para los flujos; no acreditan aún este recorrido en la nube.

Verificación de la implementación Google: assembleDebug, 27 pruebas unitarias
y 18 pruebas instrumentadas pasaron en el Motorola conectado (45 en total).
GoogleAuthFlowTest cubre PKCE/state/nonce, rechazo de retornos ajenos o vencidos,
callback Android exacto, botón sin correo/contraseña y cancelación sin sesión ni
reutilización del regreso. No abre Google ni intercambia códigos. El resto
comprueba navegación y ubicaciones con fuentes de prueba. Se instaló el APK
debug; no se ha acreditado aún un inicio de sesión Google real.

Los registros antiguos sin ownerId/expiresAt no se atribuyen automáticamente
a ninguna cuenta. Crear uno nuevo desde una cuenta es el recorrido normal para
probar gestión e inscripciones. Un anfitrión ya está incluido en los cupos.

Referencias oficiales:

- https://docs.aws.amazon.com/cognito/latest/developerguide/authentication-flows-public-server-side.html
- https://docs.aws.amazon.com/apigateway/latest/developerguide/http-api-jwt-authorizer.html
- https://docs.aws.amazon.com/cognito-user-identity-pools/latest/APIReference/API_InitiateAuth.html
