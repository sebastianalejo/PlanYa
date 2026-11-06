# Cierre de Crear/Editar y participación

No se considera 100% hasta repetir estos pasos contra AWS. Las pruebas automáticas usan datos aislados y no modifican planes reales.

## Actualización necesaria en AWS

1. Abre Lambda → PlanYaApi → Código.
2. Reemplaza el contenido de `lambda_function.py` por el archivo completo `backend/lambda_function.py` de este proyecto.
3. Pulsa Deploy. No cambies el rol, el bucket, las variables ni las rutas existentes.

Esta revisión añade `expectedRevision` opcional a PUT /planes/{id}. Android envía la versión que abrió el editor; si otro dispositivo cambió el encuentro o sus participantes, Lambda responde 409 en lugar de pisar cambios. Clientes anteriores siguen siendo compatibles, pero no obtienen esta protección adicional si no envían la versión.

La revisión más reciente incorpora `photos` (máximo cuatro). Antes de probar
varias fotos hay que volver a desplegar el archivo completo: una Lambda anterior
solo guardaría la portada. Se conservan las mismas rutas y el mismo bucket.
En Configuración → Configuración general confirma tiempo máximo de 30 segundos
para validar varias fotos, sin activar servicios nuevos.

El editor conserva el borrador ante conflicto. «Recargar versión actual» pide confirmación antes de sustituirlo. Si la consulta falla, el borrador y su foto local se conservan. Solo una recarga exitosa descarta esos cambios. Editar nunca alarga la vigencia.

## Prueba con dos cuentas

Usa un plan de prueba y fotos sin información privada. No hace falta compartir contraseñas ni tokens.

| Paso | Acción | Resultado esperado |
| --- | --- | --- |
| 1 | Cuenta A publica plan con cupo 2 y una foto. | Detalle muestra anfitrión A, un asistente y un cupo libre. |
| 2 | A edita título/descripción/dirección, conserva foto y guarda. | Inicio y Detalle muestran cambios. Horario/vencimiento no cambian. Al reabrir se conservan. |
| 3 | A cambia la foto y guarda; después la quita y vuelve a guardar. | La nueva foto sustituye la anterior; quitarla deja el plan sin foto. Verificar objetos antiguos en S3 y política Lifecycle por separado. |
| 3b | A añade hasta cuatro fotos, elige otra portada, guarda y reabre. | Se conservan cuatro y su orden. Deslizar en Detalle cambia contador; una quinta se bloquea. |
| 3c | A quita una foto y guarda; luego quita las restantes y guarda. | Solo desaparecen las retiradas; portada pasa a la primera restante. Sin fotos no quedan URLs viejas visibles. |
| 4 | Cuenta B abre el plan y pulsa Unirme ahora. | Dos participantes, sin duplicados. A no aparece también en «Me uní». |
| 5 | B cierra y vuelve a abrir el detalle. | Sigue inscrita; acción Salir del plan, no Unirme. |
| 6 | B elige salir y luego Volver en la confirmación. | Continúa inscrita. |
| 7 | B confirma salir. | Se libera el cupo; vuelve la acción Unirme. |
| 8 | A abre editor en un dispositivo. B se une desde otro. A intenta guardar. | Con Lambda actualizada: conflicto; conserva borrador y solicita recargar. No desaparece B de asistentes. |
| 9 | Con B inscrita, A intenta reducir cupo por debajo de asistentes. | Validación en Android y servidor; no guarda un cupo imposible. |
| 10 | B consulta el detalle de un plan ajeno. | No ofrece editar/cancelar. El servidor rechaza intentos sin propiedad. |
| 11 | A cancela y confirma. | Desaparece de Inicio. Otro dispositivo, al actualizar, ya no puede consultar/unirse. |
| 12 | Deja vencer otro plan de prueba. | Desaparece del listado; inscripción/edición rechazada. No confundir ocultación inmediata con borrado físico TTL/S3. |

Sin una prueba real no se da por verificada la configuración de IAM/JWT, S3 ni el funcionamiento entre dos cuentas. Los usuarios de prueba Google deben estar permitidos en el proyecto OAuth cuando siga en modo de prueba.
