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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.example.tesis.ui.theme.TESISTheme
import com.example.tesis.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

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

    // Efecto para asegurar barra de estado transparente y estilo de iconos
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
        containerColor = Color.Black, // Forzar fondo negro para evitar franjas blancas
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
            composable(Screen.History.route) { HistoryScreen() }
            composable(Screen.Map.route) { MapScreen() }
            composable(Screen.Reports.route) { ReportsScreen() }
            composable(Screen.Settings.route) { SettingsScreen(settingsManager) }
        }
    }
}

@Composable
fun MainScreen(settingsManager: SettingsManager) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by settingsManager.settings.collectAsState()
    val detector = remember { 
        try { WhiteflyDetector(context) } catch(_: Exception) { null } 
    }
    val framingAssistant = remember { FramingAssistant() }
    val locationHelper = remember { LocationHelper(context) }
    val historyManager = remember { HistoryManager(context) }
    val executor = remember { Executors.newSingleThreadExecutor() }

    var capturedImage by remember { mutableStateOf<Bitmap?>(null) }
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
                    ) { bitmap ->
                        capturedImage = bitmap
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
                        settingsManager = settingsManager
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
            onDismiss = { showSaveDialog = false },
            onConfirm = { crop, lot ->
                scope.launch {
                    val (count, result) = pendingResult!!
                    val location = if (hasLocationPermission) locationHelper.getCurrentLocation() else null
                    val imagePath = saveOriginalImage(context, capturedImage!!)

                    val entity = DetectionEntity(
                        timestamp = System.currentTimeMillis(),
                        count = count,
                        latitude = location?.latitude,
                        longitude = location?.longitude,
                        crop = crop,
                        lot = lot,
                        imagePath = imagePath,
                        detections = result?.detections,
                        imageWidth = result?.imageWidth ?: 0,
                        imageHeight = result?.imageHeight ?: 0
                    )
                    historyManager.add(entity)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Detección guardada en $lot ($crop)", Toast.LENGTH_SHORT).show()
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
    onCapture: (Bitmap) -> Unit,
) {
    val context = LocalContext.current
    val settings by settingsManager.settings.collectAsState()
    val (low, medium) = settingsManager.getThresholdsFor(settings.selectedCropName)
    
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var detectionResult by remember { mutableStateOf<DetectionResult?>(null) }
    var framingResult by remember { mutableStateOf<FramingResult?>(null) }
    
    // Estados para el indicador de enfoque
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var showFocusRing by remember { mutableStateOf(value = false) }
    val focusAlpha = remember { Animatable(0f) }
    val focusScale = remember { Animatable(1.5f) }
    
    val imageCapture = remember { ImageCapture.Builder()
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
        .build() 
    }

    val triggerCapture = {
        try {
            imageCapture.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        val rotation = image.imageInfo.rotationDegrees
                        val fullBitmap = image.toBitmap()
                        
                        // Reducir tamaño agresivamente para evitar OOM
                        val maxDim = 1280f
                        val scale = maxDim / maxOf(fullBitmap.width, fullBitmap.height)
                        val smallBitmap = if (scale < 1f) {
                            fullBitmap.scale((fullBitmap.width * scale).toInt(), (fullBitmap.height * scale).toInt(), filter = true)
                        } else fullBitmap
                        
                        // Si creamos uno nuevo, reciclamos el anterior si es diferente
                        if (smallBitmap != fullBitmap) {
                            fullBitmap.recycle()
                        }

                        val finalBitmap = if (rotation != 0) {
                            val matrix = android.graphics.Matrix().apply { postRotate(rotation.toFloat()) }
                            val rotated = Bitmap.createBitmap(smallBitmap, 0, 0, smallBitmap.width, smallBitmap.height, matrix, true)
                            smallBitmap.recycle()
                            rotated
                        } else smallBitmap
                        
                        ContextCompat.getMainExecutor(context).execute { onCapture(finalBitmap) }
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Processing capture failed", e)
                    } finally {
                        image.close()
                    }
                }
            })
        } catch (e: Exception) {
            Log.e("MainActivity", "Capture failed", e)
        }
    }

    // Lógica de Auto-Shot
    if (settings.autoShotEnabled) {
        LaunchedEffect(framingResult?.status) {
            if (framingResult?.status == FramingStatus.READY) {
                kotlinx.coroutines.delay(1200) // Esperar 1.2 segundos de estabilidad
                if (framingResult?.status == FramingStatus.READY) {
                    triggerCapture()
                }
            }
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            try {
                val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val source = ImageDecoder.createSource(context.contentResolver, it)
                    ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                        decoder.isMutableRequired = true
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE // Anti-OOM
                    }
                } else {
                    @Suppress("DEPRECATION")
                    MediaStore.Images.Media.getBitmap(context.contentResolver, it)
                }
                
                // RESIZE LARGE IMAGES TO PREVENT OOM AND SYNC ROTATION
                val maxDim = 1600f
                val scale = maxDim / maxOf(bitmap.width, bitmap.height)
                val processedBitmap = if (scale < 1f) {
                    bitmap.scale((bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), filter = true)
                } else bitmap
                
                onCapture(processedBitmap)
            } catch (e: Exception) {
                Log.e("MainActivity", "Gallery failed", e)
                Toast.makeText(context, "Error al cargar imagen", Toast.LENGTH_SHORT).show()
            }
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
                                
                                val result = detector.detect(bitmap, rotation, isLive = true)
                                
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
                        
                        // Implementar Tap-to-Focus
                        previewView.setOnTouchListener { v, event ->
                            if (event.action == android.view.MotionEvent.ACTION_UP) {
                                val factory = previewView.meteringPointFactory
                                val point = factory.createPoint(event.x, event.y)
                                val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
                                    .setAutoCancelDuration(3, java.util.concurrent.TimeUnit.SECONDS)
                                    .build()
                                camera.cameraControl.startFocusAndMetering(action)
                                
                                // Mostrar indicador visual
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

        // Dibujar indicador de enfoque (Samsung Style)
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

        // Capture UI
        Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.BottomCenter) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { galleryLauncher.launch("image/*") },
                    modifier = Modifier.size(48.dp).background(Color.Black.copy(0.4f), CircleShape)
                ) {
                    Icon(Icons.Default.PhotoLibrary, contentDescription = null, tint = Color.White)
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
                    Icon(Icons.Default.Camera, contentDescription = null, modifier = Modifier.size(48.dp))
                }
                Spacer(modifier = Modifier.width(80.dp))
            }
        }

        // Info Overlay
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(16.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black.copy(alpha = 0.6f))
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val count = detectionResult?.detections?.size ?: 0
            Text(text = stringResource(id = R.string.whiteflies_detected, count), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            
            detectionResult?.let {
                if (count == 0) {
                    Text(text = "Max Score: ${"%.4f".format(it.maxScore)}", color = Color.Yellow, fontSize = 12.sp)
                    Text(text = it.modelOutputInfo, color = Color.Gray, fontSize = 10.sp)
                }
            }
            
            MipTrafficLight(count = count, lowThreshold = low, mediumThreshold = medium)
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
            
            // Draw perimeter border
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
    onSave: (Int, DetectionResult?) -> Unit
) {
    val settings by settingsManager.settings.collectAsState()
    val (low, medium) = settingsManager.getThresholdsFor(settings.selectedCropName)
    
    var result by remember { mutableStateOf<DetectionResult?>(null) }
    var isLoading by remember { mutableStateOf(value = true) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    LaunchedEffect(bitmap) {
        isLoading = true
        // Ejecutar detección en un hilo secundario para no bloquear la UI
        withContext(Dispatchers.Default) {
            result = detector.detect(bitmap, 0, isLive = false)
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
        // Contenedor con zoom y paneo
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

        // Overlay de info (Fijo, fuera del graphicsLayer)
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black.copy(alpha = 0.6f))
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val count = result?.detections?.size ?: 0
            Text(text = stringResource(id = R.string.whiteflies_detected, count), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            
            result?.let { res ->
                Text(text = "Max Conf: ${"%.4f".format(res.maxScore)}", color = Color.Yellow, fontSize = 14.sp)
                Text(text = "Debug: ${res.modelOutputInfo}", color = Color.Gray, fontSize = 10.sp)
                
                Button(
                    onClick = { onSave(count, res) },
                    modifier = Modifier.padding(top = 8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("Guardar Muestreo")
                }
            }
            
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

@Composable
fun SaveDetectionDialog(
    settingsManager: SettingsManager,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit
) {
    val settings by settingsManager.settings.collectAsState()
    var selectedCrop by remember { mutableStateOf(settings.selectedCropName) }
    var lotName by remember { mutableStateOf(settings.selectedLotName) }
    
    var expanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Guardar Muestreo") },
        text = {
            Column {
                Text("Cultivo:", fontWeight = FontWeight.Bold)
                Box {
                    OutlinedButton(
                        onClick = { expanded = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(selectedCrop)
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        settings.crops.forEach { crop ->
                            DropdownMenuItem(
                                text = { Text(crop.name) },
                                onClick = {
                                    selectedCrop = crop.name
                                    expanded = false
                                }
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Text("Lote / Sección:", fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = lotName,
                    onValueChange = { lotName = it },
                    placeholder = { Text("Ej: Lote A, Sección 4") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = { 
                settingsManager.updateSelectedCrop(selectedCrop)
                settingsManager.updateSelectedLot(lotName)
                onConfirm(selectedCrop, lotName) 
            }) {
                Text("Confirmar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
    )
}
