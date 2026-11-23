# AlAl Rosa Náutica Optimizador — proyecto Android nativo

Nombre e icono de aplicación actualizados a **AlAl Rosa Náutica Optimizador**. Incluye la base Android recuperada del proyecto previo: selector múltiple de fotografías, OCR local ML Kit, candidatos de domicilios con revisión, persistencia Room y vista de mapa Google Maps.

## Estado real de esta entrega

Este ZIP es un **paquete de código fuente base con la nueva marca**, no un APK ni una versión funcional final. No se ha compilado ni probado. Las funciones acordadas recientemente —deslizar paradas para entregado/no entregado, símbolos ● / ●$ / 📦, cierre de jornada, historial y reporte de tiempos/paquetes— todavía requieren implementación e integración en la app. No se deben considerar disponibles por el solo hecho de estar descritas.

## Configuración

1. Abrir en Android Studio.
2. Copiar `local.properties.example` como `local.properties` y agregar una clave restringida `MAPS_API_KEY`.
3. Habilitar Maps SDK for Android y Routes API en Google Cloud y configurar facturación/cuotas.
4. Sincronizar Gradle y compilar.

La clave de Google no está incluida. Incluir una clave en una APK la expone; debe restringirse por paquete, SHA-1 y APIs.

## Limitaciones conocidas de esta base

La vista de mapa solo dibuja marcadores que ya tengan coordenadas. El cliente Routes API existe como clase, pero todavía no está conectado al flujo de pantalla. La geocodificación existe como servicio, pero no está integrada automáticamente en la interfaz. Esta base tampoco contiene todavía el flujo completo de parques, cierre de ruta ni métricas.

## Pantalla de inicio animada
La aplicación inicia con el GIF `app/src/main/assets/splash_rosa_nautica.gif` proporcionado para AlAl Rosa Náutica Optimizador. Se muestra centrado sobre fondo azul marino durante aproximadamente 3.2 segundos y después abre `MainActivity`.
