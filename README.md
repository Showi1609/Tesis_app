# BioCount MIPE — App Android

Herramienta de campo para conteo automático de mosca blanca (*whitefly*, WF) sobre trampas
amarillas pegajosas, usando el modelo YOLOv8n del repo de entrenamiento (`biocount-mipe-modelo`).
Corre 100% on-device (TFLite, sin conexión a internet).

Bitácora completa del proceso (10 etapas de modelo + 7 fases de esta app): **[link al artifact]**

## Stack

- Kotlin + Jetpack Compose
- CameraX (captura en vivo) + TensorFlow Lite (inferencia on-device)
- Decodificación de cajas y NMS manuales (esta versión de Ultralytics no soporta NMS incrustado
  en TFLite) — ver `WhiteflyDetector.kt`
- Historial en JSON (conteo, fecha, GPS, cajas de detección) + gráficas semanales (Vico)
- Google Maps / ubicación (Play Services)

## Modelo

`app/src/main/assets/whitefly.tflite` — cuantización dynamic-range (INT8 solo en pesos), 4MB,
exportada de `combinado-6/best.pt` (F1 test = 0.727, ver Etapa 8 de la bitácora). Umbral de
confianza `conf=0.25`, NMS `iou=0.45` (`WhiteflyDetector.kt`, confirmado óptimo para el caso de
uso de monitoreo en la Etapa 10).

## Compilar

Abrir en Android Studio (min SDK 24, target/compile SDK 35) y correr. Requiere
`google-services.json` propio si se usa un proyecto de Firebase/Maps distinto al de desarrollo
(revisar `local.properties` / claves de API de Maps).
