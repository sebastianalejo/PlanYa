# Operador: mensaje de horario

Durante el piloto, Ver encuentro devolvió HTTP 410 antes del inicio del plan.
El backend usa ese estado tanto antes del inicio como después de la ventana,
o cuando la oferta no está vigente. Android lo interpretaba siempre como vencido.

Corrección solo Android:

- Consulta de participantes y confirmación: se explica que el encuentro puede
  no haber empezado, haber terminado o la oferta no estar vigente.
- Canje: conserva el aviso específico de beneficio/código vencido.
- Catálogo: aviso de oferta no vigente.

No se infiere una causa exacta que la API no distingue. No se utiliza el reloj
del teléfono para autorizar, no se modifica la ventana ni se habilita asistencia
anticipada. No necesita despliegue de Lambda ni cambios en DynamoDB.

Se añadieron dos regresiones JVM para cubrir consulta/confirmación y
catálogo/canje con HTTP 410.

Verificación: assembleDebug correcto y 100 pruebas JVM aprobadas, cero fallos
y errores. No se instaló en Motorola: adb no detectó dispositivos conectados.
No hubo push ni actualización de Lambda.
