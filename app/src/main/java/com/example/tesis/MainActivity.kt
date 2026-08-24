package com.example.tesis

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.util.Size as SizeUtil
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.graphics.scale
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.tesis.ui.HistoryScreen
import com.example.tesis.ui.MapScreen
import com.example.tesis.ui.ReportsScreen
import com.example.tesis.ui.Screen
import com.example.tesis.ui.SettingsScreen
import com.example.tesis.ui.SessionsScreen
import com.example.tesis.ui.TrapsScreen
import com.example.tesis.ui.AppLogo
import com.example.tesis.ui.EditablePicker
import com.example.tesis.ui.theme.TESISTheme
import com.example.tesis.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Origen de la imagen analizada. Se guarda porque una foto de galería no pasó
 *  por el asistente de encuadre y por tanto su escala no está garantizada. */
object ImageSource {
    const val CAMERA = "CAMARA"
    const val GALLERY = "GALERIA"
}

/**
 * Datos que el operario confirma al guardar un muestreo.
 *
 * La geometría y la fecha de instalación no las digita: salen de la ficha de la
 * trampa en el registro, y se copian aquí para quedar congeladas en el registro
 * guardado.
 */
data class SampleMetadata(
    val crop: String,
    val farm: String,
    val greenhouse: String,
    val trapId: String,
    /** Vínculo estable con la ficha del registro; no depende del texto del código. */
    val trapRecordId: String,
    val face: TrapFace,
    val framingMode: FramingMode,
    val trapWidthCm: Float,
    val trapHeightCm: Float,
    val windowWidthCm: Float,
    val windowHeightCm: Float,
    val installedAt: Long?,
    val replacementCycleDays: Int,
    val manualCount: Int?,
    /** De las detecciones de la app, cuántas eran de verdad una mosca. */
    val truePositives: Int?,
    /** Sesión de muestreo activa y quién hace la lectura. */
    val sessionId: String?,
    val operator: String?,
    val notes: String?,
    /** "MANUAL" o "GPS": deja rastro de cómo se eligió la trampa. */
    val trapSelectionMode: String
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        enableEdgeToEdge()
        setContent {
            TESISTheme {
                MainApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainApp() {
    val context = LocalContext.current
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val settingsManager = remember { SettingsManager(context) }

    val view = androidx.compose.ui.platform.LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as android.app.Activity).window
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            val controller = androidx.core.view.WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = false
            controller.isAppearanceLightNavigationBars = false
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color.Black,
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                windowInsets = WindowInsets.navigationBars
            ) {
                listOf(Screen.Detection, Screen.History, Screen.Map, Screen.Reports, Screen.Settings).forEach { screen ->
                    NavigationBarItem(
                        icon = { Icon(screen.icon, contentDescription = screen.title) },
                        label = { Text(screen.title) },
                        selected = currentRoute == screen.route,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Detection.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Detection.route) { MainScreen(settingsManager) }
            composable(Screen.History.route) { HistoryScreen(settingsManager) }
            composable(Screen.Map.route) { MapScreen() }
            composable(Screen.Reports.route) { ReportsScreen(settingsManager) }
            composable(Screen.Settings.route) {
                SettingsScreen(
                    settingsManager = settingsManager,
                    onOpenTraps = { navController.navigate(Screen.Traps.route) },
                    onOpenSessions = { navController.navigate(Screen.Sessions.route) }
                )
            }
            composable(Screen.Traps.route) {
                TrapsScreen(onBack = { navController.popBackStack() })
            }
            composable(Screen.Sessions.route) {
                SessionsScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}

@Composable
fun MainScreen(settingsManager: SettingsManager) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val detector = remember {
        try { WhiteflyDetector(context) } catch (_: Exception) { null }
    }
    val framingAssistant = remember { FramingAssistant() }
    val locationHelper = remember { LocationHelper(context) }
    val historyManager = remember { HistoryManager(context) }
    val trapRegistry = remember { TrapRegistry(context) }
    val executor = remember { Executors.newSingleThreadExecutor() }

    var capturedImage by remember { mutableStateOf<Bitmap?>(null) }
    var capturedSource by remember { mutableStateOf(ImageSource.CAMERA) }
    var isLive by remember { mutableStateOf(value = true) }

    var showSaveDialog by remember { mutableStateOf(false) }
    var pendingResult by remember { mutableStateOf<Pair<Int, DetectionResult?>?>(null) }

    BackHandler(enabled = !isLive) {
        isLive = true
        capturedImage = null
    }

    DisposableEffect(Unit) {
        onDispose {
            detector?.close()
            executor.shutdown()
        }
    }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }
    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED,
        )
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted -> hasCameraPermission = granted }

    val locationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
        hasLocationPermission = permissions.values.all { it }
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) cameraLauncher.launch(Manifest.permission.CAMERA)
        if (!hasLocationPermission) {
            locationLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (detector == null) {
            Text("Error: No se encontró el modelo en assets.", modifier = Modifier.align(Alignment.Center))
        } else {
            if (isLive) {
                if (hasCameraPermission) {
                    DetectorView(
                        detector = detector,
                        framingAssistant = framingAssistant,
                        executor = executor,
                        settingsManager = settingsManager
                    ) { bitmap, source ->
                        capturedImage = bitmap
                        capturedSource = source
                        isLive = false
                    }
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Button(onClick = { cameraLauncher.launch(Manifest.permission.CAMERA) }) {
                            Text("Otorgar Permiso de Cámara")
                        }
                    }
                }
            } else {
                capturedImage?.let { bitmap ->
                    StaticImageView(
                        bitmap = bitmap,
                        detector = detector,
                        settingsManager = settingsManager,
                        trapRegistry = trapRegistry
                    ) { count, result ->
                        pendingResult = count to result
                        showSaveDialog = true
                    }
                }
            }
        }
    }

    if (showSaveDialog && pendingResult != null) {
        SaveDetectionDialog(
            settingsManager = settingsManager,
            trapRegistry = trapRegistry,
            appDetectedCount = pendingResult!!.first,
            onDismiss = { showSaveDialog = false },
            onConfirm = { meta ->
                scope.launch {
                    val (count, result) = pendingResult!!
                    val location = if (hasLocationPermission) locationHelper.getCurrentLocation() else null
                    val imagePath = capturedImage?.let { saveOriginalImage(context, it) }

                    val entity = DetectionEntity(
                        timestamp = System.currentTimeMillis(),
                        count = count,
                        latitude = location?.latitude,
                        longitude = location?.longitude,
                        gpsAccuracyM = location?.accuracy,
                        crop = meta.crop,
                        farm = meta.farm,
                        greenhouse = meta.greenhouse,
                        trapId = meta.trapId,
                        trapRecordId = meta.trapRecordId,
                        face = meta.face.code,
                        framingMode = meta.framingMode.code,
                        trapWidthCm = meta.trapWidthCm,
                        trapHeightCm = meta.trapHeightCm,
                        windowWidthCm = if (meta.framingMode == FramingMode.VENTANA) meta.windowWidthCm else null,
                        windowHeightCm = if (meta.framingMode == FramingMode.VENTANA) meta.windowHeightCm else null,
                        installTimestamp = meta.installedAt,
                        replacementCycleDays = meta.replacementCycleDays,
                        trapSelectionMode = meta.trapSelectionMode,
                        manualCount = meta.manualCount,
                        truePositives = meta.truePositives,
                        sessionId = meta.sessionId,
                        operator = meta.operator,
                        imagePath = imagePath,
                        imageSource = capturedSource,
                        detections = result?.detections,
                        imageWidth = result?.imageWidth ?: 0,
                        imageHeight = result?.imageHeight ?: 0,
                        modelVersion = ModelInfo.VERSION,
                        // Del resultado, no de la constante: desde que los umbrales
                        // son ajustables, la constante ya no describe lo que pasó.
                        confThreshold = result?.confThreshold ?: ModelInfo.CONF_THRESHOLD,
                        iouThreshold = result?.iouThreshold ?: ModelInfo.IOU_NMS,
                        detectionMode = result?.detectionMode ?: MODE_WHOLE,
                        notes = meta.notes
                    )
                    historyManager.add(entity)
                    settingsManager.updateSamplingContext(meta.farm, meta.greenhouse, meta.trapId)

                    withContext(Dispatchers.Main) {
                        val density = Metrics.densityPer100Cm2(entity)
                        val densityText = density?.let {
                            " · %.2f ind/100 cm²".format(it)
                        } ?: ""
                        Toast.makeText(
                            context,
                            "Guardado: ${meta.trapId} cara ${meta.face.code}$densityText",
                            Toast.LENGTH_SHORT
                        ).show()
                        isLive = true
                        capturedImage = null
                        showSaveDialog = false
                        pendingResult = null
                    }
                }
            }
        )
    }
}

@Composable
fun DetectorView(
    detector: WhiteflyDetector,
    framingAssistant: FramingAssistant,
    executor: ExecutorService,
    settingsManager: SettingsManager,
    onCapture: (Bitmap, String) -> Unit,
) {
    val context = LocalContext.current
    val settings by settingsManager.settings.collectAsState()
    val (low, medium) = settingsManager.getThresholdsFor(settings.selectedCropName)

    // El analizador de CameraX se construye una sola vez, así que no puede
    // capturar los umbrales por valor: quedarían congelados en lo que hubiera al
    // abrir la cámara. Con rememberUpdatedState lee siempre el ajuste vigente.
    val liveThresholds = rememberUpdatedState(
        settings.confThresholdOrDefault to settings.iouNmsOrDefault
    )

    // Mismo motivo: la devolución de la captura se ejecuta mucho después de
    // construirse, así que el tope de resolución se lee en el momento, no se
    // captura por valor. Con mosaico hay que conservar más píxeles, porque no se
    // puede trocear un detalle que ya se descartó.
    val captureLimits = rememberUpdatedState(settings.captureMaxDim to settings.galleryMaxDim)

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var detectionResult by remember { mutableStateOf<DetectionResult?>(null) }
    var framingResult by remember { mutableStateOf<FramingResult?>(null) }

    // Indicador de enfoque
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var showFocusRing by remember { mutableStateOf(value = false) }
    val focusAlpha = remember { Animatable(0f) }
    val focusScale = remember { Animatable(1.5f) }

    // Guardas contra capturas duplicadas.
    //
    // El bug que corrigen: al abrir la galería la cámara sigue ligada al
    // lifecycle y el analizador sigue corriendo, así que el asistente de
    // encuadre llegaba a READY y el disparo automático tomaba una foto real
    // mientras el usuario estaba en el selector. Al volver de galería se
    // entregaban DOS imágenes.
    var isPickerOpen by remember { mutableStateOf(false) }
    var captureInFlight by remember { mutableStateOf(false) }
    var deliveredCapture by remember { mutableStateOf(false) }

    // Una sola entrega por sesión de captura: la primera imagen gana y las
    // posteriores se descartan (y se reciclan) en vez de pisar el estado.
    val deliver: (Bitmap, String) -> Unit = deliver@{ bitmap, source ->
        if (deliveredCapture) {
            bitmap.recycle()
            return@deliver
        }
        deliveredCapture = true
        onCapture(bitmap, source)
    }

    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
    }

    val triggerCapture: () -> Unit = trigger@{
        if (captureInFlight || isPickerOpen || deliveredCapture) return@trigger
        captureInFlight = true
        try {
            imageCapture.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        val rotation = image.imageInfo.rotationDegrees
                        val fullBitmap = image.toBitmap()

                        // Reducir para evitar OOM. El tope depende del mosaico.
                        val maxDim = captureLimits.value.first
                        val scale = maxDim / maxOf(fullBitmap.width, fullBitmap.height)
                        val smallBitmap = if (scale < 1f) {
                            fullBitmap.scale((fullBitmap.width * scale).toInt(), (fullBitmap.height * scale).toInt(), filter = true)
                        } else fullBitmap

                        if (smallBitmap != fullBitmap) fullBitmap.recycle()

                        val finalBitmap = if (rotation != 0) {
                            val matrix = android.graphics.Matrix().apply { postRotate(rotation.toFloat()) }
                            val rotated = Bitmap.createBitmap(smallBitmap, 0, 0, smallBitmap.width, smallBitmap.height, matrix, true)
                            smallBitmap.recycle()
                            rotated
                        } else smallBitmap

                        ContextCompat.getMainExecutor(context).execute {
                            captureInFlight = false
                            deliver(finalBitmap, ImageSource.CAMERA)
                        }
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Processing capture failed", e)
                        ContextCompat.getMainExecutor(context).execute { captureInFlight = false }
                    } finally {
                        image.close()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e("MainActivity", "Capture failed", exception)
                    ContextCompat.getMainExecutor(context).execute { captureInFlight = false }
                }
            })
        } catch (e: Exception) {
            Log.e("MainActivity", "Capture failed", e)
            captureInFlight = false
        }
    }

    // Disparo automático.
    //
    // La condición de ajustes va DENTRO del efecto, no envolviéndolo: envolver un
    // LaunchedEffect en un `if` cambia la estructura del árbol de composición y
    // hace que el efecto se cancele y relance de forma difícil de razonar.
    LaunchedEffect(framingResult?.status, settings.autoShotEnabled, isPickerOpen) {
        if (!settings.autoShotEnabled) return@LaunchedEffect
        if (isPickerOpen || deliveredCapture) return@LaunchedEffect
        if (framingResult?.status != FramingStatus.READY) return@LaunchedEffect

        kotlinx.coroutines.delay(1200) // estabilidad
        if (!isPickerOpen && !deliveredCapture && framingResult?.status == FramingStatus.READY) {
            triggerCapture()
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        isPickerOpen = false
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.isMutableRequired = true
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE // Anti-OOM
                }
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
            }

            val maxDim = captureLimits.value.second
            val scale = maxDim / maxOf(bitmap.width, bitmap.height)
            val processedBitmap = if (scale < 1f) {
                bitmap.scale((bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), filter = true)
            } else bitmap

            deliver(processedBitmap, ImageSource.GALLERY)
        } catch (e: Exception) {
            Log.e("MainActivity", "Gallery failed", e)
            Toast.makeText(context, "Error al cargar imagen", Toast.LENGTH_SHORT).show()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FIT_CENTER
                }
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

                cameraProviderFuture.addListener(
                    {
                        try {
                            val cameraProvider = cameraProviderFuture.get()
                            val preview = Preview.Builder().build().also {
                                it.surfaceProvider = previewView.surfaceProvider
                            }

                            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                            val resolutionSelector = ResolutionSelector.Builder()
                                .setResolutionStrategy(
                                    ResolutionStrategy(
                                        SizeUtil(1280, 720),
                                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                                    )
                                )
                                .build()

                            val imageAnalysis = ImageAnalysis.Builder()
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                                .setResolutionSelector(resolutionSelector)
                                .build()

                            imageAnalysis.setAnalyzer(executor) { imageProxy ->
                                try {
                                    val rotation = imageProxy.imageInfo.rotationDegrees
                                    val bitmap = imageProxy.toBitmap()

                                    val (liveConf, liveIou) = liveThresholds.value
                                    val result = detector.detect(
                                        bitmap, rotation, isLive = true, liveConf, liveIou
                                    )

                                    if ((result.modelOutputInfo != "Busy") && (result.modelOutputInfo != "Closed")) {
                                        val fResult = framingAssistant.analyzeFraming(imageProxy, bitmap)

                                        ContextCompat.getMainExecutor(ctx).execute {
                                            detectionResult = result
                                            framingResult = fResult
                                        }
                                    }

                                    bitmap.recycle()
                                } catch (e: Exception) {
                                    Log.e("MainActivity", "Analyzer failed", e)
                                } finally {
                                    imageProxy.close()
                                }
                            }

                            cameraProvider.unbindAll()
                            val camera = cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageAnalysis, imageCapture)

                            previewView.setOnTouchListener { v, event ->
                                if (event.action == android.view.MotionEvent.ACTION_UP) {
                                    val factory = previewView.meteringPointFactory
                                    val point = factory.createPoint(event.x, event.y)
                                    val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
                                        .setAutoCancelDuration(3, java.util.concurrent.TimeUnit.SECONDS)
                                        .build()
                                    camera.cameraControl.startFocusAndMetering(action)

                                    focusPoint = Offset(event.x, event.y)
                                    showFocusRing = true
                                    v.performClick()
                                    true
                                } else true
                            }
                        } catch (e: Exception) {
                            Log.e("MainActivity", "Camera binding failed", e)
                        }
                    },
                    ContextCompat.getMainExecutor(ctx)
                )
                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        detectionResult?.let { res ->
            DetectionOverlay(result = res)
        }

        FramingGuideOverlay(result = framingResult)

        if (showFocusRing && focusPoint != null) {
            LaunchedEffect(focusPoint) {
                focusAlpha.snapTo(1f)
                focusScale.snapTo(1.5f)
                launch { focusScale.animateTo(1f, tween(300, easing = FastOutSlowInEasing)) }
                focusAlpha.animateTo(0f, tween(800, easing = LinearEasing))
                showFocusRing = false
            }
        }

        if (focusPoint != null && focusAlpha.value > 0f) {
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(
                    color = Color.Yellow,
                    radius = 40.dp.toPx() * focusScale.value,
                    center = focusPoint!!,
                    style = Stroke(width = 2.dp.toPx()),
                    alpha = focusAlpha.value
                )
            }
        }

        // Controles de captura
        Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.BottomCenter) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = {
                        if (!isPickerOpen && !captureInFlight) {
                            isPickerOpen = true
                            galleryLauncher.launch("image/*")
                        }
                    },
                    modifier = Modifier.size(48.dp).background(Color.Black.copy(0.4f), CircleShape)
                ) {
                    Icon(Icons.Default.PhotoLibrary, contentDescription = "Cargar de galería", tint = Color.White)
                }
                Spacer(modifier = Modifier.width(32.dp))
                IconButton(
                    onClick = { triggerCapture() },
                    modifier = Modifier
                        .size(72.dp)
                        .background(
                            if (framingResult?.status == FramingStatus.READY) Color.Green.copy(alpha = 0.6f)
                            else Color.White.copy(alpha = 0.5f),
                            CircleShape
                        )
                ) {
                    Icon(Icons.Default.Camera, contentDescription = "Capturar", modifier = Modifier.size(48.dp))
                }
                Spacer(modifier = Modifier.width(80.dp))
            }
        }

        // Logo y panel informativo. El logo va FUERA de la tarjeta oscura para que
        // quede a la misma altura y posición que en el resto de pantallas.
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 16.dp, start = 16.dp, end = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
        AppLogo(height = 65.dp)
        Spacer(modifier = Modifier.height(8.dp))
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black.copy(alpha = 0.6f))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val count = detectionResult?.detections?.size ?: 0
            Text(
                text = stringResource(id = R.string.whiteflies_detected, count),
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "${settings.selectedGreenhouseName} · ${settings.selectedTrapId}",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 12.sp
            )

            detectionResult?.let { ModelCertaintyBadge(it.detections) }
            MipTrafficLight(count = count, lowThreshold = low, mediumThreshold = medium)
        }
        }
    }
}

@Composable
fun FramingGuideOverlay(result: FramingResult?) {
    val status = result?.status ?: FramingStatus.SEARCHING
    val infiniteTransition = rememberInfiniteTransition(label = "blink")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.2f, targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(600, easing = LinearEasing), repeatMode = RepeatMode.Reverse),
        label = "alpha"
    )

    val color = when (status) {
        FramingStatus.READY -> Color.Green
        else -> Color.Red
    }

    val statusText = when (status) {
        FramingStatus.SEARCHING -> stringResource(R.string.status_searching)
        FramingStatus.NOT_CENTERED -> stringResource(R.string.status_not_centered)
        FramingStatus.FOCUSING -> stringResource(R.string.status_focusing)
        FramingStatus.READY -> stringResource(R.string.status_ready)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = 8.dp.toPx()
            val effectiveAlpha = if (status == FramingStatus.READY) 1f else alpha

            drawRect(
                color = color,
                topLeft = Offset(strokeWidth / 2, strokeWidth / 2),
                size = Size(size.width - strokeWidth, size.height - strokeWidth),
                style = Stroke(width = strokeWidth),
                alpha = effectiveAlpha
            )
        }

        Text(
            text = statusText,
            color = color,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(top = 280.dp)
                .alpha(if (status == FramingStatus.READY) 1f else alpha)
                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                .padding(horizontal = 16.dp, vertical = 6.dp)
        )
    }
}

@Composable
fun StaticImageView(
    bitmap: Bitmap,
    detector: WhiteflyDetector,
    settingsManager: SettingsManager,
    trapRegistry: TrapRegistry,
    onSave: (Int, DetectionResult?) -> Unit
) {
    val settings by settingsManager.settings.collectAsState()
    val (low, medium) = settingsManager.getThresholdsFor(settings.selectedCropName)

    var result by remember { mutableStateOf<DetectionResult?>(null) }
    var isLoading by remember { mutableStateOf(value = true) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    // Los umbrales entran como clave: si se cambian en Ajustes, la foto que está
    // en pantalla se vuelve a analizar en vez de quedarse con el conteo viejo.
    val staticConf = settings.confThresholdOrDefault
    val staticIou = settings.iouNmsOrDefault
    val tiled = settings.tiledDetectionOrDefault
    val tileGrid = settings.tileGridOrDefault
    LaunchedEffect(bitmap, staticConf, staticIou, tiled, tileGrid) {
        isLoading = true
        withContext(Dispatchers.Default) {
            result = if (tiled) {
                detector.detectTiled(
                    bitmap = bitmap,
                    rotation = 0,
                    grid = tileGrid,
                    confThreshold = staticConf,
                    iouThreshold = staticIou
                )
            } else {
                detector.detect(bitmap, 0, isLive = false, staticConf, staticIou)
            }
        }
        isLoading = false
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RectangleShape)
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val oldScale = scale
                    scale = (scale * zoom).coerceIn(1f, 10f)
                    val zoomChange = scale / oldScale
                    offset = ((offset + pan) * zoomChange) - (centroid * (zoomChange - 1f))
                }
            }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y,
                    transformOrigin = TransformOrigin(0f, 0f)
                )
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
            result?.let {
                DetectionOverlay(result = it)
            }
        }

        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black.copy(alpha = 0.6f))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AppLogo(
                modifier = Modifier.padding(bottom = 4.dp),
                height = 65.dp
            )
            val count = result?.detections?.size ?: 0
            Text(
                text = stringResource(id = R.string.whiteflies_detected, count),
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium
            )

            // Densidad previa al guardado, con la geometría de la finca en curso.
            val profile = trapRegistry.farmProfile(settings.selectedFarmName)
            val areaCm2 = when (profile.framingMode) {
                FramingMode.CARA_COMPLETA -> profile.widthCm * profile.heightCm
                FramingMode.VENTANA -> profile.windowWidth * profile.windowHeight
            }
            if (areaCm2 > 0f) {
                Text(
                    text = "%.2f ind/100 cm²".format(count / areaCm2 * 100f),
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 14.sp
                )
            }

            result?.let { res ->
                Button(
                    onClick = { onSave(count, res) },
                    modifier = Modifier.padding(top = 8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("Guardar Muestreo")
                }
            }

            result?.let { ModelCertaintyBadge(it.detections) }
            MipTrafficLight(count = count, lowThreshold = low, mediumThreshold = medium)
        }
    }
}

@Composable
fun DetectionOverlay(result: DetectionResult) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val screenWidth = size.width
        val screenHeight = size.height

        val isRotated = (result.rotation == 90) || (result.rotation == 270)
        val imgW = if (isRotated) result.imageHeight.toFloat() else result.imageWidth.toFloat()
        val imgH = if (isRotated) result.imageWidth.toFloat() else result.imageHeight.toFloat()

        val scale = minOf(screenWidth / imgW, screenHeight / imgH)
        val drawW = imgW * scale
        val drawH = imgH * scale
        val offsetX = (screenWidth - drawW) / 2f
        val offsetY = (screenHeight - drawH) / 2f

        result.detections.forEach { d ->
            val rect = d.rect
            val left: Float; val top: Float; val right: Float; val bottom: Float

            when (result.rotation) {
                90 -> {
                    left = ((result.imageHeight - rect.bottom) * scale) + offsetX
                    top = (rect.left * scale) + offsetY
                    right = ((result.imageHeight - rect.top) * scale) + offsetX
                    bottom = (rect.right * scale) + offsetY
                }
                270 -> {
                    left = (rect.top * scale) + offsetX
                    top = ((result.imageWidth - rect.right) * scale) + offsetY
                    right = (rect.bottom * scale) + offsetX
                    bottom = ((result.imageWidth - rect.left) * scale) + offsetY
                }
                else -> {
                    left = (rect.left * scale) + offsetX
                    top = (rect.top * scale) + offsetY
                    right = (rect.right * scale) + offsetX
                    bottom = (rect.bottom * scale) + offsetY
                }
            }

            drawRect(
                color = Color.Cyan,
                topLeft = Offset(left, top),
                size = Size(right - left, bottom - top),
                style = Stroke(width = 2.dp.toPx())
            )
        }
    }
}

/**
 * Certeza del modelo sobre lo que detectó, no acierto: son cosas distintas (ver
 * la bitácora, apartado 4.1). Reutiliza [util.Metrics.confidenceLabel] — la misma
 * función y los mismos umbrales que usa el historial ([ui.HistoryScreen]), para
 * que "certeza alta" signifique lo mismo en las dos pantallas. Solo se muestra
 * la etiqueta cualitativa, sin el decimal crudo: ese fue justo el problema de la
 * versión anterior (se veía bajo en amarillo casi siempre y no reflejaba la
 * calidad real de la lectura).
 */
@Composable
fun ModelCertaintyBadge(detections: List<BoxedDeteccion>) {
    val label = com.example.tesis.util.Metrics.confidenceLabel(
        com.example.tesis.util.Metrics.meanScore(detections)
    ) ?: return
    val color = when (label) {
        "Alta" -> Color(0xFF57C785)
        "Media" -> Color(0xFFFFD166)
        else -> Color(0xFFFF6B6B)
    }
    Text(text = "Certeza del modelo: ${label.lowercase()}", color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium)
}

@Composable
fun MipTrafficLight(count: Int, lowThreshold: Int = 5, mediumThreshold: Int = 15) {
    val (statusResId, color) = when {
        count < lowThreshold -> R.string.mip_status_low to Color.Green
        count < mediumThreshold -> R.string.mip_status_medium to Color.Yellow
        else -> R.string.mip_status_high to Color.Red
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = stringResource(id = R.string.mip_title) + ": ", color = Color.White, fontWeight = FontWeight.Bold)
        Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(color))
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = stringResource(id = statusResId), color = color, fontWeight = FontWeight.Bold)
    }
}

/**
 * Diálogo de guardado.
 *
 * La finca, el invernadero y la trampa se eligen del registro en vez de
 * escribirse: digitar el identificador a mano en cada visita es lo que hace que
 * "T-01" y "T-1" acaben contando como dos trampas distintas en los reportes.
 *
 * La fecha de instalación y las medidas salen de la ficha de la trampa, no de un
 * valor global, porque cada trampa tiene su propio ciclo de reposición.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SaveDetectionDialog(
    settingsManager: SettingsManager,
    trapRegistry: TrapRegistry,
    appDetectedCount: Int,
    onDismiss: () -> Unit,
    onConfirm: (SampleMetadata) -> Unit
) {
    val context = LocalContext.current
    val settings by settingsManager.settings.collectAsState()
    val registryState by trapRegistry.state.collectAsState()
    val dateFormat = remember { java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.getDefault()) }

    var selectedCrop by remember { mutableStateOf(settings.selectedCropName) }
    var farmName by remember { mutableStateOf(settings.selectedFarmName) }
    var greenhouseName by remember { mutableStateOf(settings.selectedGreenhouseName) }
    var trapCode by remember { mutableStateOf(settings.selectedTrapId) }
    // El historial del día se lee antes de proponer la cara, porque es de donde
    // sale la propuesta.
    val historyManager = remember { HistoryManager(context) }
    val history by historyManager.detections.collectAsState()
    val todayStart = remember {
        java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
    val historyToday = remember(history, todayStart) { history.filter { it.timestamp >= todayStart } }

    // La cara se propone sola: si esa trampa ya tiene la A registrada hoy, toca la
    // B. Muestrear las dos caras es el flujo normal, y encadenar A y B a mano en
    // cada visita es justo donde se cuela el error de registrar dos veces la misma.
    val suggestedFace = remember(historyToday, trapCode, greenhouseName, farmName) {
        val doneToday = historyToday
            .filter {
                it.trapIdOrDefault == trapCode &&
                    it.greenhouseOrDefault == greenhouseName
            }
            .map { it.faceEnum }
            .toSet()
        when {
            TrapFace.A in doneToday && TrapFace.B !in doneToday -> TrapFace.B
            TrapFace.B in doneToday && TrapFace.A !in doneToday -> TrapFace.A
            else -> TrapFace.A
        }
    }
    var face by remember { mutableStateOf(suggestedFace) }
    var faceTouched by remember { mutableStateOf(false) }
    LaunchedEffect(suggestedFace) { if (!faceTouched) face = suggestedFace }
    var manualCountText by remember { mutableStateOf("") }
    var truePositivesText by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var cropExpanded by remember { mutableStateOf(false) }

    val farmMapStore = remember { FarmMapStore(context) }
    val farmMap by farmMapStore.map.collectAsState()

    // La sesión activa se garantiza al abrir el diálogo. Crear una por defecto es
    // preferible a bloquear el guardado en pleno invernadero: el dato queda
    // etiquetado y el operario se completa después desde Sesiones.
    val sessionManager = remember { SessionManager(context) }
    val sessionState by sessionManager.state.collectAsState()
    LaunchedEffect(Unit) { sessionManager.ensureActive() }
    val activeSession = sessionState.active
    var showSessionPicker by remember { mutableStateOf(false) }

    val locationHelper = remember { LocationHelper(context) }
    val scope = rememberCoroutineScope()
    var locating by remember { mutableStateOf(false) }
    var locationMatch by remember { mutableStateOf<LocationMatch?>(null) }
    var selectionMode by remember { mutableStateOf("MANUAL") }

    // Van atadas a registryState para que el diálogo se recomponga cuando el
    // registro cambia, por ejemplo al marcar una trampa como repuesta desde aquí.
    val farmOptions = remember(registryState) { trapRegistry.farmNames() }
    val greenhouseOptions = remember(registryState, farmName) {
        trapRegistry.greenhouseNames(farmName)
    }
    val trapOptions = remember(registryState, farmName, greenhouseName) {
        trapRegistry.trapsIn(farmName, greenhouseName).map { it.code }
    }
    val profile = remember(registryState, farmName) { trapRegistry.farmProfile(farmName) }
    val existingTrap = remember(registryState, farmName, greenhouseName, trapCode) {
        trapRegistry.findTrap(farmName, greenhouseName, trapCode)
    }
    val installedAt = existingTrap?.installedAt

    var framingMode by remember(profile.defaultFramingMode) { mutableStateOf(profile.framingMode) }

    val size = existingTrap?.let { trapRegistry.effectiveSize(it) } ?: (profile.widthCm to profile.heightCm)
    val areaCm2 = when (framingMode) {
        FramingMode.CARA_COMPLETA -> size.first * size.second
        FramingMode.VENTANA -> profile.windowWidth * profile.windowHeight
    }
    val density = if (areaCm2 > 0f) appDetectedCount / areaCm2 * 100f else null
    val exposureDays = existingTrap?.let { trapRegistry.exposureDays(it) }
    val overdue = existingTrap?.let { trapRegistry.isOverdue(it) } == true

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Guardar muestreo") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {

                EditablePicker("Finca", farmOptions, farmName) { farmName = it }
                Spacer(Modifier.height(8.dp))
                EditablePicker("Invernadero", greenhouseOptions, greenhouseName) { greenhouseName = it }
                Spacer(Modifier.height(8.dp))
                EditablePicker("Trampa", trapOptions, trapCode) {
                    trapCode = it
                    selectionMode = "MANUAL"
                }

                Spacer(Modifier.height(8.dp))
                LocationAssist(
                    locating = locating,
                    match = locationMatch,
                    onLocate = {
                        locating = true
                        scope.launch {
                            val loc = locationHelper.getCurrentLocation()
                            if (loc == null) {
                                Toast.makeText(
                                    context,
                                    "No se pudo obtener la ubicación",
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                val match = trapRegistry.resolveLocation(
                                    point = GeoPoint(loc.latitude, loc.longitude),
                                    accuracyM = loc.accuracy,
                                    farmMap = farmMap
                                )
                                locationMatch = match
                                match.area?.let { area ->
                                    // El invernadero es el polígono; su capa en My Maps
                                    // es el bloque, y la finca es el nombre del mapa.
                                    greenhouseName = area.name
                                    farmMap.documentName?.takeIf { it.isNotBlank() }
                                        ?.let { farmName = it }
                                }
                                if (match.confident) {
                                    match.best?.let {
                                        trapCode = it.code
                                        selectionMode = "GPS"
                                    }
                                }
                            }
                            locating = false
                        }
                    },
                    onPick = { candidate ->
                        trapCode = candidate.trap.code
                        greenhouseName = candidate.trap.greenhouse
                        farmName = candidate.trap.farm
                        selectionMode = "GPS"
                    }
                )

                // Estado de la trampa elegida
                Spacer(Modifier.height(8.dp))
                when {
                    existingTrap == null && trapCode.isNotBlank() -> Text(
                        "Trampa nueva: se dará de alta al guardar, sin fecha de instalación. " +
                            "Regístrala en Ajustes › Trampas para tener capturas/trampa/día.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                    installedAt == null -> Text(
                        "Esta trampa no tiene fecha de instalación registrada.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                    else -> Column {
                        Text(
                            "Instalada el ${dateFormat.format(java.util.Date(installedAt))}" +
                                (exposureDays?.let { " · ${"%.0f".format(it)} día(s) de exposición" } ?: ""),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (overdue) {
                            Text(
                                "Supera el ciclo de ${profile.cycleDays} d: el adhesivo puede " +
                                    "estar saturado y subestimar la población.",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.error
                            )
                            existingTrap?.let { trap ->
                                TextButton(onClick = { trapRegistry.replaceTrap(trap.id) }) {
                                    Text("Marcar como repuesta hoy", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text("Cara de la trampa", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(
                    "Ambas caras capturan de forma independiente; se registran por separado.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!faceTouched && suggestedFace == TrapFace.B) {
                    Text(
                        "Propuesta B: hoy ya se registró la cara A de esta trampa.",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    TrapFace.entries.forEachIndexed { index, item ->
                        SegmentedButton(
                            selected = face == item,
                            onClick = { face = item; faceTouched = true },
                            shape = SegmentedButtonDefaults.itemShape(index, TrapFace.entries.size)
                        ) { Text(item.label) }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text("Encuadre", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    FramingMode.entries.forEachIndexed { index, item ->
                        SegmentedButton(
                            selected = framingMode == item,
                            onClick = { framingMode = item },
                            shape = SegmentedButtonDefaults.itemShape(index, FramingMode.entries.size)
                        ) { Text(item.label, fontSize = 12.sp) }
                    }
                }
                Text(
                    when (framingMode) {
                        FramingMode.CARA_COMPLETA ->
                            "Área: ${"%.0f".format(size.first)} × ${"%.0f".format(size.second)} cm " +
                                "= ${"%.0f".format(areaCm2)} cm² (cara completa)"
                        FramingMode.VENTANA ->
                            "Área: ${"%.0f".format(profile.windowWidth)} × ${"%.0f".format(profile.windowHeight)} cm " +
                                "= ${"%.0f".format(areaCm2)} cm² (ventana)"
                    },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )

                Spacer(Modifier.height(12.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Sesión", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Text(
                                activeSession?.nameOrDefault ?: "Sin sesión",
                                fontSize = 13.sp
                            )
                            Text(
                                activeSession?.operatorOrDefault ?: "Sin operario",
                                fontSize = 11.sp,
                                color = if (activeSession?.needsOperator != false) {
                                    MaterialTheme.colorScheme.error
                                } else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(onClick = { showSessionPicker = true }) {
                            Text("Cambiar", fontSize = 12.sp)
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text("Cultivo", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Box {
                    OutlinedButton(
                        onClick = { cropExpanded = true },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(selectedCrop) }
                    DropdownMenu(expanded = cropExpanded, onDismissRequest = { cropExpanded = false }) {
                        settings.crops.forEach { crop ->
                            DropdownMenuItem(
                                text = { Text(crop.name) },
                                onClick = {
                                    selectedCrop = crop.name
                                    cropExpanded = false
                                }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = manualCountText,
                    onValueChange = { new -> manualCountText = new.filter { it.isDigit() } },
                    label = { Text("Conteo manual del experto (opcional)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "Déjalo vacío si no hay conteo de referencia: sin él, el error y el " +
                        "acierto no se pueden calcular y quedarán en blanco.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )

                // Solo tiene sentido preguntarlo si hay conteo de referencia.
                if (manualCountText.toIntOrNull() != null) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = truePositivesText,
                        onValueChange = { new -> truePositivesText = new.filter { it.isDigit() } },
                        label = { Text("De las $appDetectedCount detecciones, cuántas eran mosca") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "Con este dato salen precisión, sensibilidad y F1. Sin él solo se " +
                            "comparan totales, y un muestreo que se salta moscas y marca " +
                            "otras cosas puede cuadrar en el total y parecer perfecto.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                Spacer(Modifier.height(12.dp))
                LabeledField("Observaciones", notes) { notes = it }

                Spacer(Modifier.height(16.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Detectado por la app: $appDetectedCount moscas", fontSize = 13.sp)
                        density?.let {
                            Text("Densidad: ${"%.2f".format(it)} ind/100 cm²", fontSize = 13.sp)
                        }
                        val manual = manualCountText.toIntOrNull()
                        if (manual != null && manual > 0) {
                            val relative = (appDetectedCount - manual).toDouble() / manual * 100.0
                            Text(
                                "Error relativo: ${"%.1f".format(relative)} % · " +
                                    "acierto ${"%.1f".format((100 - kotlin.math.abs(relative)).coerceIn(0.0, 100.0))} %",
                                fontSize = 13.sp
                            )

                            val vp = truePositivesText.toIntOrNull()
                                ?.coerceIn(0, minOf(appDetectedCount, manual))
                            if (vp != null) {
                                val precision = if (appDetectedCount > 0) {
                                    vp.toDouble() / appDetectedCount * 100.0
                                } else null
                                val recall = vp.toDouble() / manual * 100.0
                                Text(
                                    "Perdidas: ${manual - vp} · falsos positivos: ${appDetectedCount - vp}",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    "Sensibilidad ${"%.1f".format(recall)} %" +
                                        (precision?.let { " · precisión ${"%.1f".format(it)} %" } ?: ""),
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = farmName.isNotBlank() && greenhouseName.isNotBlank() && trapCode.isNotBlank(),
                onClick = {
                    // Alta implícita: si la trampa no existe en el registro se crea,
                    // para que no quede un muestreo apuntando a una trampa fantasma.
                    val trap = existingTrap ?: TrapRecord(
                        farm = farmName.trim(),
                        greenhouse = greenhouseName.trim(),
                        code = trapCode.trim()
                    ).also { trapRegistry.upsertTrap(it) }


                    val effective = trapRegistry.effectiveSize(trap)

                    settingsManager.updateSelectedCrop(selectedCrop)
                                        onConfirm(
                        SampleMetadata(
                            crop = selectedCrop,
                            farm = trap.farm,
                            greenhouse = trap.greenhouse,
                            trapId = trap.code,
                            trapRecordId = trap.id,
                            face = face,
                            framingMode = framingMode,
                            trapWidthCm = effective.first,
                            trapHeightCm = effective.second,
                            windowWidthCm = profile.windowWidth,
                            windowHeightCm = profile.windowHeight,
                            installedAt = trap.installedAt,
                            replacementCycleDays = profile.cycleDays,
                            sessionId = activeSession?.id,
                            operator = activeSession?.operator,
                            manualCount = manualCountText.toIntOrNull(),
                            // Sin conteo manual el dato no significa nada, así que
                            // no se guarda aunque haya quedado texto en el campo.
                            truePositives = manualCountText.toIntOrNull()?.let { manual ->
                                truePositivesText.toIntOrNull()
                                    ?.coerceIn(0, minOf(appDetectedCount, manual))
                            },
                            notes = notes.ifBlank { null },
                            trapSelectionMode = selectionMode
                        )
                    )
                }
            ) {
                Text("Confirmar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
    )

    if (showSessionPicker) {
        var newName by remember { mutableStateOf(SessionManager.defaultName()) }
        var newOperator by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showSessionPicker = false },
            title = { Text("Sesión de muestreo") },
            text = {
                Column {
                    val open = sessionState.openSessions
                        .sortedByDescending { it.startedAt ?: 0L }
                    if (open.isNotEmpty()) {
                        Text("Sesiones abiertas", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Spacer(Modifier.height(4.dp))
                        open.forEach { option ->
                            TextButton(
                                onClick = {
                                    sessionManager.setActive(option.id)
                                    showSessionPicker = false
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    option.label +
                                        if (option.id == activeSession?.id) "  (activa)" else "",
                                    fontSize = 12.sp,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }

                    Text("Nueva sesión", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("Nombre") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = newOperator,
                        onValueChange = { newOperator = it },
                        label = { Text("Operario") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "En Ajustes › Sesiones puedes cerrarlas y ver cuántos muestreos " +
                            "lleva cada una.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = newName.isNotBlank(),
                    onClick = {
                        sessionManager.create(newName, newOperator, farmName)
                        showSessionPicker = false
                    }
                ) { Text("Crear y activar") }
            },
            dismissButton = {
                TextButton(onClick = { showSessionPicker = false }) { Text("Cerrar") }
            }
        )
    }
}

/**
 * Asistente de ubicación del diálogo de guardado.
 *
 * El invernadero se deduce del polígono que te contiene, que es fiable porque un
 * invernadero mide decenas de metros y el error del GPS cabe dentro con holgura.
 * La trampa se deduce por cercanía, que es mucho más frágil: por eso solo se
 * preselecciona cuando la segunda candidata está más lejos que el propio margen
 * de error del GPS. En caso contrario se muestran las candidatas y decide la
 * persona, porque una trampa mal asignada contamina dos series a la vez sin
 * dejar rastro.
 */
@Composable
private fun LocationAssist(
    locating: Boolean,
    match: LocationMatch?,
    onLocate: () -> Unit,
    onPick: (TrapCandidate) -> Unit
) {
    Column {
        OutlinedButton(
            enabled = !locating,
            onClick = onLocate,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (locating) "Ubicando..." else "Usar mi ubicación")
        }

        if (match == null) return@Column

        val accuracy = match.accuracyM
        Text(
            buildString {
                append(match.area?.let { "Estás en ${it.name}" } ?: "Fuera de los invernaderos del mapa")
                accuracy?.let { append(" · GPS ±%.0f m".format(it)) }
            },
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp)
        )

        when {
            match.candidates.isEmpty() -> Text(
                "Ninguna trampa cercana tiene coordenada registrada. Captúrala en su " +
                    "ficha, en Ajustes › Trampas.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.error
            )

            match.confident -> Text(
                "Trampa asignada: %s, a %.0f m".format(
                    match.candidates[0].trap.code,
                    match.candidates[0].distanceM
                ),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary
            )

            else -> Column {
                Text(
                    "La precisión del GPS no alcanza para distinguirlas. Elige la correcta:",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.error
                )
                match.candidates.forEach { candidate ->
                    TextButton(
                        onClick = { onPick(candidate) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "%s · a %.0f m%s".format(
                                candidate.trap.code,
                                candidate.distanceM,
                                candidate.trap.positionLabel?.let { " · $it" } ?: ""
                            ),
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LabeledField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
    )
}
