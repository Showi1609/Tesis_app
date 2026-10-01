# Changelog

## [1.1] - 2026-10-01
Ningún cambio altera la detección ni el conteo: WhiteflyDetector.kt y la lógica de inferencia no se modificaron (comparación de código contra la versión de campo) y el modelo assets/whitefly.tflite conserva el SHA-256 a4be7a54d71861a2a48e83630c0516dfa96c2b29927b7e42280587e20e388fa4.

Nota: la prueba instrumentada RegressionTest se retiró. Al ejecutarse desde Android Studio, el sistema de pruebas desinstala la app al terminar y con ella sus datos internos (fotos e historial). No volver a crear pruebas instrumentadas sobre el teléfono de trabajo sin respaldar antes los datos de la app.

### Añadido
- **Semáforo por trampa (T1)**: El estado MIP ahora se calcula sobre la suma de las capturas de la cara A y B de una misma trampa (agrupadas por jornada). Las capturas de una sola cara se reportan como "TRAMPA INCOMPLETA". Exportación actualizada.
- **Registro de trampa con fecha (T4)**: Al capturar sobre una trampa no registrada en Ajustes, la app permite introducir su fecha de instalación para calcular exposición.
- **Conteo semanal (T7)**: Nuevo dato informativo en reportes (exportación): "adultos/trampa/semana", escalado a 7 días.

### Modificado
- **Detección por defecto (T2)**: El modo de inferencia por defecto pasa a ser `MOSAICO_2x2` para priorizar la precisión.
- **Etiqueta del Modelo (T3)**: La etiqueta (no el archivo) se actualiza a `whitefly_yolov8n_combinado-6_v2_int8-dinamica`.
- **Bloqueo de Parámetros (T8)**: Los controles de Confianza y NMS (IoU) en Ajustes se han bloqueado y no pueden ser alterados, asegurando la consistencia de los conteos.
- **Dimensiones de trampa (T6)**: Consolidada la dependencia en `FarmProfile` para todos los cálculos de área. (Ya estaba implementado vía `effectiveSize(trap)` en `MainActivity.kt`).
- **Ciclo de reposición (T5)**: Visibilidad y comportamiento confirmado en perfil de finca (por defecto: 7 días).
