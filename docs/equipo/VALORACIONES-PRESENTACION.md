# Valoraciones — entrega para presentación

Acceso Android: Perfil → Confianza → Valorar encuentros.

Pulido visual: cinco estrellas vacías y «0 valoraciones · Sin nota todavía»
para perfiles sin opiniones. No se asigna 5/5 sin votos. Con promedio real se
dibujan estrellas completas/medias. El selector de nota incluye estrellas y
descripción breve de cada nivel (de «Muy mala experiencia» a «Excelente
experiencia»). Son etiquetas de la escala, no comentarios escritos por usuarios.
Comentarios libres e historial individual recibido quedan fuera de este pulido.
Verificado el pulido: build correcto, 106 pruebas JVM y 20 pruebas Android
seleccionadas (RatingsIntegrationTest/ProfileScreenTest, 65,453 s) aprobadas.
APK instalada conservando datos. Sin cambios de backend para estas estrellas.

## Alcance

- Lista de personas con asistencia acreditada al mismo plan terminado.
- Sin autovaloración; nota entera de 1 a 5, una por autor/destinatario/plan.
- Revisión explícita antes de guardar; voto inmutable.
- Clave determinista y transacción DynamoDB: dos asistencias, versión/fin/no
  cancelación del plan, voto único e incremento de promedio juntos.
- La revisión del perfil también aumenta al recibir un voto, evitando que una
  edición concurrente sobrescriba el agregado de confianza.
- Repetir la misma nota recupera el resultado sin volver a sumar.
- La ausencia de candidatos es normal si solo hay una asistencia acreditada.
- No crea verificaciones, asistencias ni opiniones de demostración.

Limitación MVP: la lista usa lectura consistente paginada de la tabla compartida,
con límite defensivo de 100 páginas de 100 registros. Al superar el límite falla
explícitamente, no presenta una lista parcial como vacía. Para producción debe
reemplazarse por índices de acceso por usuario/plan. Sin comentarios libres,
moderación ni edición de notas en esta entrega.

## Desplegar

1. Lambda PlanYaApi: subir app/build/backend/PlanYaApi.zip. Ahora incluye cinco
   módulos en la raíz: lambda_function.py, chat.py, benefit_redemption.py,
   operator_panel.py y ratings.py. Conservar variables, handler, runtime y rol.
2. API Gateway PlanYaApiHttp: crear GET /valoraciones y POST /valoraciones.
3. Asociar a ambas el autorizador JWT existente PlanYaUsuarios y la integración
   existente PlanYaApi. No IAM, no una Lambda nueva ni rutas públicas.
4. Publicar en la etapa actual si no tiene implementación automática.

No basta con subir el ZIP: sin las rutas nuevas Android mostrará que el servicio
todavía no está habilitado. Si el laboratorio deniega permisos, no eludirlos.

## Validación

164 pruebas Python aprobadas: contiene 12 regresiones de valoraciones, incluida
concurrencia y respuesta perdida con almacén simulado. No equivalen a un voto
real en AWS. No presentar la función como validada en cloud antes de desplegar
y comprobar dos personas elegibles.

Android: compilación correcta y 105 pruebas JVM sin fallos. En Motorola se
repitieron correctamente las 13 de ProfileScreenTest y las 28 de
RatingsIntegrationTest/ProfileEditingTest/OperatorIntegrationTest (174,17 s en
la última ejecución). Total: 41 pruebas Android seleccionadas, no toda la suite.
Las primeras pasadas tuvieron dos fallos de arranque sin jerarquía Compose y
un fallo al elegir la raíz de captura de un diálogo; se repitieron las clases
afectadas tras corregir el test de captura. La captura recortada no constituye
evidencia visual completa del diálogo; las aserciones funcionales sí pasaron.

El usuario confirmó subir el ZIP actualizado de cinco módulos. GET y POST
/valoraciones devolvieron 401 sin sesión en la API real; esto prueba la puerta
JWT, no el guardado de una valoración autenticada. Paquete SHA-256:
`3B256369AEE9DBD4CE6C4A225FAF22C6B0D2B947D44661725706587AF262044C`.
No hay voto real validado ni se cargaron votos ficticios. Verificación de
identidad/aprobación administrativa continúa pendiente, sin cambiar su estado.

## Beneficios: evidencia de usuario

El usuario confirmó en AWS el recorrido de prueba: asistencia de Chanchito,
emisión del cupón, recepción en su cuenta, canje desde otro operador y estado
USADO. Compartió el registro con usedAt y redemptionId. La repetición mostró
confirmación; no se terminó la comparación antes/después de esos campos.
No afirmar validado el doble canje real. Escaneo con cámara sigue pendiente.
