# BioCount MIP — aplicación Android

Aplicación de campo para el conteo automático de mosca blanca en trampas cromáticas amarillas,
desarrollada como prototipo del trabajo de grado *Diseño y validación de un sistema de
cuantificación automatizada de mosca blanca en trampas cromáticas, como herramienta de
diagnóstico para la toma de decisiones en el Manejo Integrado de Plagas (MIP), evaluado en
cultivos de rosa bajo invernadero* (Programa de Bioingeniería, Universidad El Bosque, 2026).

Todo el procesamiento ocurre en el teléfono: la aplicación funciona sin conexión, no envía
imágenes a ningún servidor y no sincroniza datos de forma automática.

- Modelo, entrenamiento y análisis estadísticos: [Tesis_modelo](https://github.com/Showi1609/Tesis_modelo)
- Material complementario (APK, videos de campo, registros anonimizados): Anexo I del documento

## Qué hace

1. **Captura** la cara de una trampa con la cámara (CameraX, con asistente de encuadre) o la
   carga desde la galería, y la asocia a la sesión, el invernadero, la trampa, la cara y la
   ubicación GPS.
2. **Detecta y cuenta** los individuos con un modelo YOLOv8n cuantizado, ejecutado con
   TensorFlow Lite.
3. **Calcula** la densidad (individuos por cm² y por 100 cm²), el semáforo MIP
   (Bajo · Medio · Alto, por trampa, sumando sus dos caras) y la certeza del conteo.
4. **Guarda** cada registro localmente y muestra el historial, el mapa de trampas con mapa de
   calor por invernadero y los reportes de evolución poblacional.
5. **Exporta** los registros en XLSX y CSV, reportes en PDF e imágenes con las detecciones.

## Flujo de inferencia (`WhiteflyDetector.kt`)

| Paso | Detalle |
|---|---|
| Redimensionado | lado largo ≤ 2048 px (cámara) o ≤ 2560 px (galería) |
| Mosaico | 2 × 2 teselas con 20 % de traslape (modo por defecto) |
| Entrada del modelo | letterbox a 1280 × 1280 px, relleno gris (114), píxeles / 255 |
| Inferencia | TensorFlow Lite con delegado de GPU y respaldo en CPU (4 hilos) |
| Filtrado por tesela | confianza ≥ 0,25 · NMS con IoU 0,45 |
| Fusión de teselas | elimina duplicados por IoU > 0,45 o contención > 0,65 |

Los umbrales están fijos en el código y bloqueados en Ajustes, para que todos los conteos sean
comparables entre sí.

**Modelo:** `app/src/main/assets/whitefly.tflite` — YOLOv8n `combinado-6_v2`, cuantización de
rango dinámico (pesos INT8), 3,8 MB.
SHA-256: `a4be7a54d71861a2a48e83630c0516dfa96c2b29927b7e42280587e20e388fa4`

## Arquitectura

- **Interfaz:** Kotlin + Jetpack Compose (`ui/`: Historial, Mapa, Reportes, Sesiones, Trampas, Ajustes)
- **Inferencia:** `WhiteflyDetector.kt` (decodificación de cajas, NMS y fusión implementados en Kotlin)
- **Lógica MIP y metadatos del modelo:** `util/Metrics.kt`
- **Persistencia:** archivos JSON en el almacenamiento privado de la app, con Gson
  (`history.json`, `sessions.json`, `traps.json`, `farm_map.json`, `settings.json`)
- **Mapa:** Google Maps (Maps Compose) con mapa de calor IDW confinado a cada invernadero (`util/Heatmap.kt`)
- **Exportación:** `util/ExportHelper.kt`, `util/XlsxWriter.kt` (XLSX sin dependencias externas),
  `util/PdfReportManager.kt`, `util/ImageExport.kt`

## Versiones

| Etiqueta | Descripción |
|---|---|
| `v1.0-validada` | Versión con la que se integró el modelo final. |
| `v1.0-campo` | Versión usada en las jornadas de validación en producción (16 y 23 de septiembre de 2026). |
| `v1.1` | Cambios posteriores a la validación (semáforo por trampa, parámetros bloqueados, mosaico por defecto). No modifica la detección ni el conteo; ver `CHANGELOG.md`. |

## Compilar

1. Abrir el proyecto en Android Studio (minSdk 24, targetSdk 35).
2. El mapa usa una clave de Google Maps declarada en `AndroidManifest.xml`. La clave incluida está
   restringida al paquete y al certificado de depuración del autor; para compilar con otro
   certificado, reemplázala por una clave propia de **Maps SDK for Android**.
3. Ejecutar la configuración `app` en un teléfono con Android 7.0 o superior.

> **Advertencia:** no ejecutar pruebas instrumentadas (`connectedAndroidTest`) en un teléfono con
> datos de campo. Al terminar, el sistema de pruebas desinstala la app y con ella su
> almacenamiento interno (fotos e historial). Exportar los registros antes de cualquier prueba.

## Privacidad

El repositorio no contiene datos de campo, coordenadas ni información de la empresa ni de los
participantes de la validación.
