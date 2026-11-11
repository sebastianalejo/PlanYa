# US-09 — Consultar y canjear beneficios reales

## Estado de integración local — 11 de noviembre de 2026

La especificación original de abajo describe el encargo inicial, no un inventario
actual de archivos faltantes. Ya existen consulta/detalle/código de usuario y un
panel separado de operador, además del backend transaccional de asistencia,
emisión desde oferta y canje. No se ha desplegado el conjunto ni se han creado
operadores u ofertas en AWS. No hay descuentos comerciales reales.

El piloto exige oferta/plan autorizados por administración, comprobación humana
de presencia y máximo una asistencia/cupón por persona y plan. La entrada de
código de canje es manual; no hay escáner de cámara todavía. Estado, contrato y
pendientes: [Paso 4: Panel del operador](PASO-4-PANEL-OPERADOR.md).

Sigue pendiente corregir cursores antiguos de planes/beneficios antes de cerrar
privacidad, probar el despliegue y recorrer los casos con cuentas reales. No
considerar cerrada la US solo por compilar o mostrar un QR.

Responsable: Compañero 3. Rama propuesta: `feature/beneficios`. Prioridad: media.

**Como** participante de encuentros en locales aliados,
**quiero** consultar mis cupones, sus condiciones y un código de canje,
**para** aprovechar descuentos válidos de forma clara y segura.

## Alcance y dependencia de negocio

No existe esta pantalla ni backend. Acceso desde Perfil → Mis beneficios; tarjetas
de disponibles/usados/vencidos y detalle con QR/código. No crear saldo monetario,
tarjetas bancarias, pagos o una falsa billetera de dinero.

Antes de emitir cupones acordar cómo acreditar asistencia. Unirse a un plan NO
demuestra haber asistido. Propuesta MVP: personal autorizado del local confirma
asistencia con un mecanismo definido y auditado. Si no existe esa parte del flujo,
entregar consulta/canje con cupones de prueba separados, sin declararlos beneficios
reales ni considerar la historia completamente terminada. No mostrar porcentajes
o comercios inventados en la entrega de producción.

## Criterios de aceptación

1. **Dado** que estoy autenticado, **cuando** abro Mis beneficios, **entonces**
   veo solo los míos, separados por disponibles, usados y vencidos.
2. **Dado** que no tengo cupones, **cuando** se completa la consulta,
   **entonces** veo un vacío claro, no un error ni cupones de demostración.
3. **Dado** un beneficio, **cuando** abro su detalle, **entonces** veo local,
   descuento, condiciones, vigencia, límites y estado antes de usarlo.
4. **Dado** un cupón disponible propio, **cuando** solicito mostrar el QR,
   **entonces** se obtiene un código opaco de corta duración y no se incrusta
   correo, contraseña, JWT ni datos sensibles dentro de la imagen.
5. **Dado** un cupón de otro usuario, **cuando** intento consultarlo o generar
   su código por API, **entonces** recibo rechazo aunque conozca su ID.
6. **Dado** un cupón válido, **cuando** un operador de local autorizado lo canjea,
   **entonces** el servidor lo marca usado una sola vez y devuelve confirmación.
7. **Dado** el mismo QR presentado dos veces o en paralelo, **cuando** se solicita
   canje, **entonces** solo una operación cambia el estado; el resto informa ya usado.
8. **Dado** un código vencido, cupón vencido/revocado o local incorrecto,
   **cuando** intentan canjearlo, **entonces** el servidor lo rechaza sin consumirlo.
9. **Dado** que se pierde la respuesta del canje, **cuando** se reintenta con el
   mismo identificador de solicitud, **entonces** se recupera el resultado sin cobrar
   ni consumir dos veces. La UI consulta el estado antes de anunciar fallo definitivo.
10. **Dado** el usuario propietario, **cuando** presiona una acción local,
    **entonces** no puede autovalidar la asistencia ni autoconcederse un descuento.
11. **Dado** red caída, **cuando** abro un cupón, **entonces** no aparece un QR
    inventado ni una confirmación local de canje: se informa y permite reintentar.

## Botones, UI y tareas

Disponibles/Usados/Vencidos → tarjeta → Ver condiciones → Mostrar código.
El dueño no tiene un botón «Marcar usado» que sustituya al servidor. Cuando vuelve
del canje se actualiza el estado. QR legible, código alternativo y texto accesible;
botones de mínimo 48 dp, estados de carga/error, sin dependencias visuales de color.

Crear `BenefitsScreen`, `BenefitDetailScreen`, `BenefitsViewModel`, `BenefitsUiState`
en `presentation/screens/benefits`. Modelos Benefit/Coupon/Redemption, casos de uso,
repositorio, DTO/mapper/API e implementación. Elegir biblioteca QR mantenida solo
después de revisar licencia y necesidad; no inventar un patrón que parezca QR.

## Contrato PROPUESTO — no existe todavía

- GET `/beneficios?estado=DISPONIBLE&nextKey=...`: cupones propios paginados.
- GET `/beneficios/{id}`: cupón propio y condiciones del beneficio.
- POST `/beneficios/{id}/codigo`: código temporal para ese cupón.
- POST `/canjes`: `{code,requestId}`; SOLO operador autorizado del local.
- La emisión de cupón por asistencia es una operación de servidor/operador,
  nunca un POST abierto al usuario para elegir descuento y propietario.
- Mantener respuesta `{success,data}`; errores 401,403,404,409 para conflicto/
  ya usado y410 para expirado. Fecha/estado/autorización se comprueban en servidor.

Datos propuestos: catálogo de beneficios de locales y cupones asociados a sub del
usuario; canje con actualización condicional/transacción e idempotencia. Revisar
índices para listar por usuario sin exponer el catálogo privado completo.
Roles/grupos del operador y asociación a local requieren acuerdo y configuración
real en Cognito/backend. Ocultar el botón no es control de permisos.
No reutilizar el ID token como QR ni emitir JWT propios dentro de Android.

## Pruebas y entrega

Dos cuentas, cupón ajeno, token inválido, doble canje simultáneo, local incorrecto,
código vencido, pérdida de red y resultado incierto. Probar lectura de QR con
un segundo dispositivo. Datos de prueba claramente separados y sin canje real
en comercios hasta tener acuerdo del equipo. Entregar contrato de acreditación
de asistencia/operador, backend, rutas protegidas y evidencia de no doble canje.
