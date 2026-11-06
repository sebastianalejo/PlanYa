# Imágenes

La foto `app/src/main/res/drawable-nodpi/coffee_reference.jpg` es una imagen
referencial de café obtenida de Unsplash:

https://images.unsplash.com/photo-1509042239860-f550ce710b93

Se muestra con el rótulo «Imagen referencial» cuando un plan de café no tiene
foto propia o esta no carga. No representa el lugar ni al anfitrión del plan.
Las demás categorías conservan su icono hasta disponer de una imagen.

`imageUrl` acepta una URL HTTPS en los datos del plan. Coil carga la foto y,
si falla, conserva el respaldo local. La imagen de referencia está incluida
en el APK, por lo que puede mostrarse sin Internet.
