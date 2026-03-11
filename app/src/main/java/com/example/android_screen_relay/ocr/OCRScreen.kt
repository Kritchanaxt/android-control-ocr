package com.example.android_screen_relay.ocr

import android.Manifest
import android.content.Context
import com.example.android_screen_relay.LogRepository
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import android.util.Size
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import android.graphics.Paint
import android.graphics.Path
import android.view.TextureView
import android.hardware.camera2.CameraManager
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.example.android_screen_relay.RelayService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.PathEffect
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.min
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OCRScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val ocr = remember { PaddleOCR() }
    
    // States
    var isInitialized by remember { mutableStateOf(false) }
    var currentImage by remember { mutableStateOf<Bitmap?>(null) }
    var cropImage by remember { mutableStateOf<Bitmap?>(null) }
    var ocrResultJson by remember { mutableStateOf("[]") }
    var ocrTimeMs by remember { mutableStateOf(0L) }
    var isProcessing by remember { mutableStateOf(false) }

    // Init OCR
    LaunchedEffect(Unit) {
        val success = ocr.initModel(context)
        isInitialized = success
        if (!success) {
            Toast.makeText(context, "OCR Init Failed.", Toast.LENGTH_LONG).show()
        }
    }

    // Permission
    var hasPermission by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted -> hasPermission = granted }
    )
    LaunchedEffect(Unit) {
        launcher.launch(Manifest.permission.CAMERA)
    }

    // Gallery Launcher
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    val stream: InputStream? = context.contentResolver.openInputStream(uri)
                    val bitmap = BitmapFactory.decodeStream(stream)
                    withContext(Dispatchers.Main) {
                        cropImage = bitmap
                        // currentImage = bitmap // Was moved to crop stage
                        ocrResultJson = "[]"
                        ocrTimeMs = 0
                    }
                } catch (e: Exception) {
                    Log.e("OCR", "Load image failed", e)
                }
            }
        }
    }

    if (cropImage != null) {
        ImageCropScreen(
            bitmap = cropImage!!,
            onCropDone = { cropped ->
                currentImage = cropped
                cropImage = null
                ocrResultJson = "[]"
                ocrTimeMs = 0
            },
            onCancel = {
                cropImage = null
                // If it was captured, it goes back to camera. If gallery, it goes back.
            }
        )
    } else if (currentImage != null) {
        // Image Analysis Mode (Screenshot 2 style)
        OCRResultScreen(
            image = currentImage!!,
            jsonResult = ocrResultJson,
            timeMs = ocrTimeMs,
            isProcessing = isProcessing,
            onClear = { 
                currentImage = null
                ocrResultJson = "[]"
                ocrTimeMs = 0
            },
            onRunModel = {
                if (!isProcessing && isInitialized) {
                    isProcessing = true
                    scope.launch(Dispatchers.IO) {
                        try {
                            val start = System.currentTimeMillis()
                            val mutableBitmap = currentImage!!.copy(Bitmap.Config.ARGB_8888, true)
                            // Run the comprehensive benchmark suite automatically upon scan
                            val benchmarkResults = OCRBenchmarkRunner.runFullBenchmarkSuite(context, ocr, mutableBitmap)
                            val end = System.currentTimeMillis()
                            withContext(Dispatchers.Main) {
                                ocrResultJson = benchmarkResults.toString()
                                ocrTimeMs = end - start
                                isProcessing = false
                            }
                        } catch (e: Exception) {
                            Log.e("OCR", "Scan/Benchmark error", e)
                            withContext(Dispatchers.Main) {
                                isProcessing = false
                                Toast.makeText(context, "OCR Error: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            },
            onGalleryClick = { galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        )
    } else {
        // Camera Preview Mode (Screenshot 1 style)
        if (hasPermission && isInitialized) {
            CameraPreviewScreen(
                onImageCaptured = { bitmap ->
                    cropImage = bitmap
                },
                onGalleryClick = {
                    galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
            )
        } else {
             Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Initializing...")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraPreviewScreen(
    onImageCaptured: (Bitmap) -> Unit,
    onGalleryClick: () -> Unit
) {
    val context = LocalContext.current
    var cameraController by remember { mutableStateOf<Camera2Controller?>(null) }
    
    // State for Settings
    val availableCameras = remember { 
        (context.getSystemService(Context.CAMERA_SERVICE) as CameraManager).cameraIdList.toList() 
    }
    var selectedCameraId by remember { mutableStateOf(availableCameras.firstOrNull() ?: "0") }
    
    // Aspect Ratio State
    var selectedAspectRatio by remember { mutableStateOf(UiAspectRatio.RATIO_3_4) } // Default to Portrait 3:4
    
    var availableResolutions by remember { mutableStateOf<List<Size>>(emptyList()) }
    var selectedResolution by remember { mutableStateOf<Size?>(null) }
    
    var showSettingsDialog by remember { mutableStateOf(false) }
    var isCapturing by remember { mutableStateOf(false) }

    // Update resolutions when camera or aspect ratio changes
    LaunchedEffect(selectedCameraId, selectedAspectRatio) {
        if (cameraController == null) {
             cameraController = Camera2Controller(context, onImageCaptured)
        }
        val items = cameraController!!.getResolutionsForAspectRatio(selectedAspectRatio)
        availableResolutions = items.mapNotNull { it.size }
        // Default to highest resolution in the list
        if (selectedResolution == null || !availableResolutions.contains(selectedResolution)) {
            selectedResolution = availableResolutions.maxByOrNull { it.width * it.height }
        }
        
        cameraController?.aspectRatio = selectedAspectRatio
    }
    
    val cameraKey = "$selectedCameraId-${selectedResolution?.width}x${selectedResolution?.height}-${selectedAspectRatio.name}"

    DisposableEffect(Unit) {
        onDispose {
            cameraController?.close()
        }
    }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                title = { Text("OCR Scanner (Camera2)", color = Color.White, fontWeight = FontWeight.Bold) },
                actions = {
                     IconButton(onClick = { showSettingsDialog = true }) {
                         Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color.White)
                     }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black.copy(alpha = 0.3f),
                    titleContentColor = Color.White
                )
            )
        },
        bottomBar = {
            // Camera Controls
            Box(
                modifier = Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.8f)).padding(vertical = 32.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Gallery
                     Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Surface(
                            shape = CircleShape,
                            color = Color.White.copy(alpha = 0.2f),
                            modifier = Modifier.size(50.dp).clickable(onClick = onGalleryClick)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.PhotoLibrary, contentDescription = "Gallery", tint = Color.White)
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text("Import", color = Color.White, fontSize = 12.sp)
                    }
                    
                    // Shutter
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(80.dp)
                            .border(4.dp, Color.White, CircleShape)
                            .clickable(enabled = !isCapturing) {
                                if (!isCapturing && cameraController != null) {
                                    isCapturing = true
                                    cameraController!!.takePhoto()
                                    // For now, simulate delay
                                    java.util.concurrent.Executors.newSingleThreadScheduledExecutor().schedule({
                                        isCapturing = false
                                    }, 1, java.util.concurrent.TimeUnit.SECONDS)
                                }
                            }
                    ) {
                        if (isCapturing) CircularProgressIndicator(color = Color.White)
                        else Box(modifier = Modifier.size(64.dp).background(Color.White, CircleShape))
                    }
                    
                    // Filler
                    Box(modifier = Modifier.size(50.dp))
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            
            // Container for Preview + Overlay that respects Aspect Ratio
            val ratioVal = selectedAspectRatio.value
            
            Box(
                 // If FULL (null), use fillMaxSize, else use aspectRatio
                modifier = if (ratioVal != null) Modifier.aspectRatio(ratioVal) else Modifier.fillMaxSize()
            ) {
                // TextureView for Camera2
                AndroidView(
                    factory = { ctx ->
                        TextureView(ctx).apply {
                            // Keep reference? Controller needs it.
                        }
                    },
                    modifier = Modifier.fillMaxSize(), // Fill the AspectRatio Box
                    update = { tv ->
                         if (cameraController != null) {
                            try {
                               // Make sure we pass the correct ratio/resolution
                               cameraController?.aspectRatio = selectedAspectRatio
                               cameraController?.openCamera(tv, selectedCameraId, selectedResolution)
                            } catch (e: Exception) {
                                Log.e("OCRScreen", "Error opening camera in update", e)
                            }
                        }
                    }
                )

                // Overlay - Drawn inside the aspect ratio box
                Canvas(modifier = Modifier.fillMaxSize()) {
                    // Draw Frame (Same logic as before)
                    val frameColor = android.graphics.Color.WHITE
                    val maskColor = android.graphics.Color.parseColor("#99000000") // Semi-transparent black
                    val cornerSize = 60f
                    val strokeW = 8f
                    val frameW = size.width * 0.85f
                    val frameH = size.height * 0.85f  // Adjusted for crop focus inside ratio
                    
                    // If square, maybe frame is square? 
                    // Let's keep existing logic but fit within this box.
                    
                    val left = (size.width - frameW) / 2
                    val top = (size.height - frameH) / 2
                    val right = left + frameW
                    val bottom = top + frameH

                    // Draw Mask (Darken outside frame)
                    drawRect(Color(maskColor), size = androidx.compose.ui.geometry.Size(size.width, top))
                    drawRect(Color(maskColor), topLeft = androidx.compose.ui.geometry.Offset(0f, bottom), size = androidx.compose.ui.geometry.Size(size.width, size.height - bottom))
                    drawRect(Color(maskColor), topLeft = androidx.compose.ui.geometry.Offset(0f, top), size = androidx.compose.ui.geometry.Size(left, frameH))
                    drawRect(Color(maskColor), topLeft = androidx.compose.ui.geometry.Offset(right, top), size = androidx.compose.ui.geometry.Size(size.width - right, frameH))

                   val path = Path()
                   path.moveTo(left, top + cornerSize); path.lineTo(left, top); path.lineTo(left + cornerSize, top)
                   path.moveTo(right - cornerSize, top); path.lineTo(right, top); path.lineTo(right, top + cornerSize)
                   path.moveTo(right, bottom - cornerSize); path.lineTo(right, bottom); path.lineTo(right - cornerSize, bottom)
                   path.moveTo(left + cornerSize, bottom); path.lineTo(left, bottom); path.lineTo(left, bottom - cornerSize)
                   
                   val paint = androidx.compose.ui.graphics.Paint().asFrameworkPaint().apply {
                       style = Paint.Style.STROKE; strokeWidth = strokeW; color = frameColor; strokeCap = Paint.Cap.ROUND
                   }
                   drawContext.canvas.nativeCanvas.drawPath(path, paint)
                 }
            }
        }
    }
    
    if (showSettingsDialog) {
        ModalBottomSheet(
            onDismissRequest = { showSettingsDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Column(
                Modifier
                    .padding(horizontal = 24.dp, vertical = 16.dp)
                    .verticalScroll(rememberScrollState())
                    .fillMaxWidth()
            ) {
                // Header
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 16.dp)
                ) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        "Camera Settings",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(16.dp))

                // Camera Selection Section
                Text(
                    "Select Camera",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    availableCameras.forEachIndexed { index, id ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { selectedCameraId = id }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = (id == selectedCameraId),
                                onClick = { selectedCameraId = id }
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = if (id == "0") "Back Camera" else if (id == "1") "Front Camera" else "Camera ID: $id",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "ID: $id",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (index < availableCameras.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 52.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Aspect Ratio Selection
                Text(
                    "Aspect Ratio",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
                
                val supportedRatios = listOf(UiAspectRatio.RATIO_3_4, UiAspectRatio.RATIO_9_16, UiAspectRatio.RATIO_1_1)
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    supportedRatios.forEach { ratio ->
                        FilterChip(
                            selected = (ratio == selectedAspectRatio),
                            onClick = { selectedAspectRatio = ratio },
                            label = { 
                                Text(
                                    ratio.displayName, 
                                    style = MaterialTheme.typography.labelLarge,
                                    modifier = Modifier.padding(vertical = 4.dp)
                                ) 
                            },
                            leadingIcon = if (ratio == selectedAspectRatio) {
                                { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                            } else null,
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                            ),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Resolution Selection
                Text(
                    "Resolution (JPEG)",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
                
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 250.dp) // Limit height
                ) {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 250.dp)
                    ) {
                        items(availableResolutions) { size ->
                             Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedResolution = size }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = (size == selectedResolution),
                                    onClick = { selectedResolution = size }
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                     Text(
                                        "${size.width} x ${size.height}",
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium
                                    )
                                    val mp = String.format(Locale.US, "%.1f MP", (size.width * size.height) / 1_000_000f)
                                     Text(
                                        mp,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 52.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}


fun rotateBitmap(bitmap: Bitmap, degrees: Int): Bitmap {
    if (degrees == 0) return bitmap
    val matrix = Matrix()
    matrix.postRotate(degrees.toFloat())
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OCRResultScreen(
    image: Bitmap,
    jsonResult: String,
    timeMs: Long,
    isProcessing: Boolean,
    onClear: () -> Unit,
    onRunModel: () -> Unit,
    onGalleryClick: () -> Unit // Add gallery option here too as per screenshot?
) {
    var showJsonDialog by remember { mutableStateOf(false) }
    var fullJsonOutput by remember { mutableStateOf("") }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Column {
                        Text("OCR Result", style = MaterialTheme.typography.titleMedium)
                        if (timeMs > 0) {
                            Text("Process time: ${timeMs}ms", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onClear) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onGalleryClick) {
                        Icon(Icons.Default.PhotoLibrary, contentDescription = "Import")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                tonalElevation = 3.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Start OCR Button
                        FilledTonalButton(
                            onClick = onRunModel,
                            enabled = !isProcessing,
                            modifier = Modifier.weight(1f).height(48.dp),
                             colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        ) {
                            if (isProcessing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    strokeWidth = 2.dp
                                )
                                Spacer(Modifier.width(8.dp))
                            } else {
                                Icon(Icons.Default.Scanner, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                            }
                            Text("Scan", maxLines = 1, style = MaterialTheme.typography.labelMedium)
                        }

                        // Preview JSON Button
                        OutlinedButton(
                            onClick = {
                                if (jsonResult == "[]" || jsonResult.isEmpty()) {
                                    Toast.makeText(context, "Please run OCR first", Toast.LENGTH_SHORT).show()
                                    return@OutlinedButton
                                }

                                scope.launch(Dispatchers.IO) {
                                    val payload = generateOCRPayload(context, image, jsonResult, timeMs)
                                    val jsonString = payload.toString(2)
                                    withContext(Dispatchers.Main) {
                                        fullJsonOutput = jsonString
                                        showJsonDialog = true
                                    }
                                }
                            },
                            enabled = !isProcessing && jsonResult != "[]",
                            modifier = Modifier.weight(1f).height(48.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp)
                        ) {
                            Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Preview", maxLines = 1, style = MaterialTheme.typography.labelMedium)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Send JSON Button
                        Button(
                            onClick = {
                                if (jsonResult == "[]" || jsonResult.isEmpty()) {
                                    Toast.makeText(context, "Please run OCR first", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                                
                                scope.launch(Dispatchers.IO) {
                                    val payload = generateOCRPayload(context, image, jsonResult, timeMs)
                                    val jsonString = payload.toString(2)
                                    
                                    withContext(Dispatchers.Main) {
                                        fullJsonOutput = jsonString
                                        
                                        val service = RelayService.getInstance()
                                        if (service != null) {
                                            service.broadcastMessage(jsonString)
                                            
                                            com.example.android_screen_relay.LogRepository.addLog(
                                                component = "OCR",
                                                event = "send_json",
                                                data = mapOf("payload_size" to jsonString.length, "blocks" to try { JSONArray(jsonResult).length() } catch(e:Exception){0}),
                                                type = com.example.android_screen_relay.LogRepository.LogType.OUTGOING
                                            )
                                            
                                            Toast.makeText(context, "Data Sent!", Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(context, "Service not running", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            },
                            enabled = !isProcessing && jsonResult != "[]",  
                            modifier = Modifier.weight(1f).height(48.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp)
                        ) {
                            Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Send", maxLines = 1, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color(0xFFF5F5F5))
        ) {
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = "Target Image",
                modifier = Modifier.fillMaxSize().padding(16.dp),
                contentScale = ContentScale.Fit
            )
            
            // Overlay
            val density = LocalContext.current.resources.displayMetrics.density
            Canvas(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                val scaleX = size.width / image.width.toFloat()
                val scaleY = size.height / image.height.toFloat()
                
                // ContentScale.Fit aligns to center. Calculate the actual scale used.
                val scale = min(scaleX, scaleY)
                
                // Calculate offset to center
                val offsetX = (size.width - image.width * scale) / 2
                val offsetY = (size.height - image.height * scale) / 2
                
                try {
                // Draw Boxes
                    val boxes = JSONArray(jsonResult)
                    val paint = Paint().apply {
                         color = android.graphics.Color.RED
                         style = Paint.Style.STROKE
                         strokeWidth = 2f / scale
                    }
                    val fillPaint = Paint().apply {
                        color = android.graphics.Color.parseColor("#33FF0000") // Red with alpha
                        style = Paint.Style.FILL
                    }

                    drawContext.canvas.nativeCanvas.save()
                    drawContext.canvas.nativeCanvas.translate(offsetX, offsetY)
                    drawContext.canvas.nativeCanvas.scale(scale, scale)

                    val textPaint = Paint().apply {
                         color = android.graphics.Color.WHITE
                         textSize = 12f
                         textAlign = Paint.Align.LEFT
                         setShadowLayer(3f, 0f, 0f, android.graphics.Color.BLACK)
                    }

                    for (i in 0 until boxes.length()) {
                        val boxObj = boxes.getJSONObject(i)
                        
                        // Parse "box" [[x,y], [x,y]...]
                        if (boxObj.has("box")) {
                            val boxArr = boxObj.getJSONArray("box")
                            if (boxArr.length() > 0) {
                                val path = Path()
                                val p0 = boxArr.getJSONArray(0)
                                path.moveTo(p0.getInt(0).toFloat(), p0.getInt(1).toFloat())
                                for(j in 1 until boxArr.length()){
                                    val p = boxArr.getJSONArray(j)
                                    path.lineTo(p.getInt(0).toFloat(), p.getInt(1).toFloat())
                                }
                                path.close()
                                drawContext.canvas.nativeCanvas.drawPath(path, fillPaint)
                                drawContext.canvas.nativeCanvas.drawPath(path, paint)

                                // Draw label
                                if (boxObj.has("label")) {
                                    val label = boxObj.getString("label")
                                    val x = p0.getInt(0).toFloat()
                                    val y = p0.getInt(1).toFloat()
                                    drawContext.canvas.nativeCanvas.drawText(label, x, y - 5f, textPaint)
                                }
                            }
                        }
                    }
                    drawContext.canvas.nativeCanvas.restore()
                } catch (e: Exception) {
                    // Log.e("OCR", "Draw error", e)
                }
            }

            // Model Info Overlay (Subtle)
            if (isProcessing || jsonResult != "[]") {
                Surface(
                    modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                    shape = RoundedCornerShape(8.dp),
                    tonalElevation = 2.dp
                ) {
                    Column(Modifier.padding(8.dp)) {
                         Text(
                             text = "Model: PP-OCRv5",
                             style = MaterialTheme.typography.labelSmall,
                             fontWeight = FontWeight.Bold
                         )
                         if (jsonResult != "[]") {
                             val count = try { JSONArray(jsonResult).length() } catch(e:Exception){0}
                             Text(text = "$count items found", style = MaterialTheme.typography.labelSmall)
                         }
                    }
                }
            }
        }
    }
    
        if (showJsonDialog) {
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            
            ModalBottomSheet(
                onDismissRequest = { showJsonDialog = false },
                sheetState = sheetState,
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Header
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Description, 
                            contentDescription = "JSON", 
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    
                    Spacer(Modifier.height(16.dp))
                    
                    Text(
                        "JSON Payload", 
                        style = MaterialTheme.typography.headlineSmall, 
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Review the generated data structure", 
                        style = MaterialTheme.typography.bodyMedium, 
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(Modifier.height(24.dp))

                    // JSON Content Area
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE0E0E0)),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false) // Allow it to take available space but not force it
                            .heightIn(min = 200.dp, max = 450.dp)
                    ) {
                        val scrollState = rememberScrollState()
                        Box(modifier = Modifier.padding(16.dp).verticalScroll(scrollState)) {
                             Text(
                                 text = fullJsonOutput,
                                 fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                 fontSize = 13.sp,
                                 lineHeight = 18.sp,
                                 color = Color(0xFF333333) // Dark gray for better contrast on white
                             )
                        }
                    }

                    Spacer(Modifier.height(24.dp))
                    
                    // Action Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showJsonDialog = false },
                            shape = RoundedCornerShape(8.dp),
                             modifier = Modifier.weight(1f).height(50.dp)
                        ) {
                            Text("Close")
                        }

                        Button(
                            onClick = {
                                clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(fullJsonOutput))
                                Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                            },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f).height(50.dp)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Copy")
                        }
                    }
                    Spacer(Modifier.height(32.dp))
                }
            }
        }
}

fun minWith(a: Float, b: Float): Float = kotlin.math.min(a, b)

private fun generateOCRPayload(
    context: Context,
    image: Bitmap,
    jsonResult: String,
    timeMs: Long
): JSONObject {
    val device = SystemMonitor.getDeviceInfo(context)
    val resource = SystemMonitor.getCurrentResourceUsage(context)
    
    val payload = JSONObject()
    payload.put("timestamp", System.currentTimeMillis())
    
    // engine_info (new format)
    val engineInfo = JSONObject().apply {
        put("engine", "paddleocr")
        put("version", "v5")
        put("runtime", "ncnn")
        put("model", "PP-OCRv5_mobile_rec")
    }
    payload.put("engine_info", engineInfo)
    payload.put("pipeline", "on-device")
    
    payload.put("device_info", device.toJson())
    
    // image_info: measure simulated jpeg size from bitmap
    val stream = java.io.ByteArrayOutputStream()
    image.compress(Bitmap.CompressFormat.JPEG, 90, stream)
    val sizeBytes = stream.size().toLong()

    payload.put("image_info", JSONObject().apply {
        put("width", image.width)
        put("height", image.height)
        put("file_size_bytes", sizeBytes)
        put("format", "jpeg")
    })
    
    try {
        val benchmarkArr = JSONArray(jsonResult)
        
        // Handle empty array case cleanly
        if (benchmarkArr.length() == 0) {
            payload.put("result", JSONObject().apply {
                put("full_text", "")
                put("lines", JSONArray())
            })
            payload.put("benchmark", JSONArray())
            payload.put("summary", JSONObject().apply {
                put("text_object_count", 0)
                put("average_confidence", 0.0)
            })
            return payload
        }
        
        // 1. Result (full_text & lines)
        val primaryRun = benchmarkArr.getJSONObject(0)
        val primaryResultBox = primaryRun.getJSONArray("result")
        
        val sb = java.lang.StringBuilder()
        var totalConfidence = 0.0
        val linesArray = JSONArray()
        
        for (i in 0 until primaryResultBox.length()) {
            val item = primaryResultBox.getJSONObject(i)
            if (!item.has("label")) continue 

            val text = item.getString("label")
            val conf = item.getDouble("prob")
            val box = item.getJSONArray("box")
            
            val xs = mutableListOf<Double>()
            val ys = mutableListOf<Double>()
            for (j in 0 until box.length()) {
                val p = box.getJSONArray(j)
                xs.add(p.getDouble(0))
                ys.add(p.getDouble(1))
            }
            
            val minX = xs.minOrNull() ?: 0.0
            val minY = ys.minOrNull() ?: 0.0
            val maxX = xs.maxOrNull() ?: 0.0
            val maxY = ys.maxOrNull() ?: 0.0
            
            val resultObj = JSONObject().apply {
                put("text", text)
                put("confidence", conf)
                
                // Using [x1, y1, x2, y2]
                val bboxArray = JSONArray()
                bboxArray.put(minX) // x1
                bboxArray.put(minY) // y1
                bboxArray.put(maxX) // x2
                bboxArray.put(maxY) // y2
                
                put("bbox", bboxArray)
                put("polygon", box) 
            }
            
            linesArray.put(resultObj)
            
            if (sb.isNotEmpty()) sb.append("\n")
            sb.append(text)
            totalConfidence += conf
        }
        
        val finalObjCount = linesArray.length()
        val avgConf = if (finalObjCount > 0) totalConfidence / finalObjCount else 0.0
        
        payload.put("result", JSONObject().apply {
            put("full_text", sb.toString())
            put("lines", linesArray)
        })
        
        // 2. Benchmark points
        val benchmarkStats = JSONArray()
        var fullImageTotalMs = 0L // Store total latency for summary

        for (i in 0 until benchmarkArr.length()) {
            val runInfo = benchmarkArr.getJSONObject(i)
            val totalMs = runInfo.optLong("latency_ms", 0L)
            
            // Normalize title to match standard test_case names
            val title = runInfo.optString("title", "Unknown")
            val testCase = when {
                title.contains("Full Image") -> "full_image"
                title.contains("720p") -> "downscaled_720p"
                title.contains("480p") -> "downscaled_480p"
                title.contains("Cropped") -> "center_cropped"
                else -> title
            }

            if (testCase == "full_image") {
                fullImageTotalMs = totalMs
            }
            
            val statObj = JSONObject().apply {
                put("test_case", testCase)
                
                put("latency", JSONObject().apply {
                    put("preprocess_ms", JSONObject.NULL)  // Using null as requested
                    put("detection_ms", JSONObject.NULL)   // Using null as requested
                    put("recognition_ms", JSONObject.NULL) // Using null as requested
                    put("total_ms", totalMs)
                })
                
                put("resource_usage", runInfo.optJSONObject("resource_usage") ?: JSONObject())
            }
            benchmarkStats.put(statObj)
        }
        
        payload.put("benchmark", benchmarkStats)
        
        // 3. Summary
        payload.put("summary", JSONObject().apply {
            put("text_object_count", finalObjCount)
            put("average_confidence", avgConf)
            put("total_latency_ms", fullImageTotalMs)
        })

    } catch (e: Exception) {
        e.printStackTrace()
        payload.put("type", "error")
        payload.put("error", e.message)
    }
    
    return payload
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageCropScreen(
    bitmap: Bitmap,
    onCropDone: (Bitmap) -> Unit,
    onCancel: () -> Unit
) {
    var cropRectNormalized by remember { mutableStateOf(Rect(0.1f, 0.1f, 0.9f, 0.9f)) }
    
    // Helper enum for drag Logic
    var activeHandle by remember { mutableStateOf("NONE") } 

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Crop Image") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val width = bitmap.width
                        val height = bitmap.height
                        
                        val left = cropRectNormalized.left.coerceIn(0f, 1f)
                        val top = cropRectNormalized.top.coerceIn(0f, 1f)
                        val right = cropRectNormalized.right.coerceIn(0f, 1f)
                        val bottom = cropRectNormalized.bottom.coerceIn(0f, 1f)

                        val x = (left * width).toInt()
                        val y = (top * height).toInt()
                        val w = ((right - left) * width).toInt().coerceAtLeast(1)
                        val h = ((bottom - top) * height).toInt().coerceAtLeast(1)
                        
                        // Final safety check
                        val safeX = x.coerceIn(0, width - 1)
                        val safeY = y.coerceIn(0, height - 1)
                        val safeW = w.coerceAtMost(width - safeX)
                        val safeH = h.coerceAtMost(height - safeY)

                        val cropped = Bitmap.createBitmap(bitmap, safeX, safeY, safeW, safeH)
                        onCropDone(cropped)
                    }) {
                        Icon(Icons.Default.Check, contentDescription = "Done")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            // Measure container
            BoxWithConstraints(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                 val density = LocalDensity.current.density
                 val containerWidth = maxWidth.value * density
                 val containerHeight = maxHeight.value * density
                 
                 // Calculate displayed image size (Fit Center)
                 val imageRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
                 val containerRatio = containerWidth / containerHeight
                 
                 val displayWidth: Float
                 val displayHeight: Float
                 
                 if (imageRatio > containerRatio) {
                     displayWidth = containerWidth
                     displayHeight = containerWidth / imageRatio
                 } else {
                     displayHeight = containerHeight
                     displayWidth = displayHeight * imageRatio
                 }
                 
                 val displayWidthDp = (displayWidth / density).dp
                 val displayHeightDp = (displayHeight / density).dp

                 Box(
                     modifier = Modifier
                         .size(displayWidthDp, displayHeightDp)
                 ) {
                     Image(
                         bitmap = bitmap.asImageBitmap(),
                         contentDescription = null,
                         contentScale = ContentScale.FillBounds,
                         modifier = Modifier.fillMaxSize()
                     )
                     
                     // Overlay & Gestures
                     Canvas(
                         modifier = Modifier
                             .fillMaxSize()
                             .pointerInput(Unit) {
                                 detectDragGestures(
                                     onDragStart = { offset ->
                                         val w = size.width
                                         val h = size.height
                                         val rect = cropRectNormalized
                                         val l = rect.left * w
                                         val t = rect.top * h
                                         val r = rect.right * w
                                         val b = rect.bottom * h
                                         
                                         // Hit test radius
                                         val rad = 40f 

                                         activeHandle = when {
                                             (offset - Offset(l, t)).getDistance() < rad -> "TL"
                                             (offset - Offset(r, t)).getDistance() < rad -> "TR"
                                             (offset - Offset(l, b)).getDistance() < rad -> "BL"
                                             (offset - Offset(r, b)).getDistance() < rad -> "BR"
                                             offset.x in l..r && offset.y in t..b -> "CENTER"
                                             else -> "NONE"
                                         }
                                     },
                                     onDrag = { change, dragAmount ->
                                         change.consume()
                                         val dx = dragAmount.x / size.width
                                         val dy = dragAmount.y / size.height
                                         
                                         var (l, t, r, b) = cropRectNormalized
                                         
                                         when (activeHandle) {
                                             "TL" -> { l += dx; t += dy }
                                             "TR" -> { r += dx; t += dy }
                                             "BL" -> { l += dx; b += dy }
                                             "BR" -> { r += dx; b += dy }
                                             "CENTER" -> {
                                                 l += dx; r += dx
                                                 t += dy; b += dy
                                             }
                                         }
                                         
                                         // Constraint logic could be improved but this prevents flipping
                                         if (l > r - 0.05f) l = r - 0.05f
                                         if (t > b - 0.05f) t = b - 0.05f
                                         
                                         // Keep Center inside bounds
                                         if (activeHandle == "CENTER") {
                                              val w = r - l
                                              val h = b - t
                                              if (l < 0) { l = 0f; r = w }
                                              if (t < 0) { t = 0f; b = h }
                                              if (r > 1) { r = 1f; l = 1f - w }
                                              if (b > 1) { b = 1f; t = 1f - h }
                                         }

                                         cropRectNormalized = Rect(
                                             l.coerceIn(0f, 1f),
                                             t.coerceIn(0f, 1f),
                                             r.coerceIn(0f, 1f),
                                             b.coerceIn(0f, 1f)
                                         )
                                     },
                                     onDragEnd = { activeHandle = "NONE" }
                                 )
                             }
                     ) {
                         val w = size.width
                         val h = size.height
                         
                         val l = cropRectNormalized.left * w
                         val t = cropRectNormalized.top * h
                         val r = cropRectNormalized.right * w
                         val b = cropRectNormalized.bottom * h
                         
                         // Dim background (Draw 4 rectangles around crop area)
                         val dimColor = Color(0x99000000)
                         
                         // Top
                         drawRect(
                             color = dimColor,
                             topLeft = Offset(0f, 0f),
                             size = androidx.compose.ui.geometry.Size(w, t)
                         )
                         // Bottom
                         drawRect(
                             color = dimColor,
                             topLeft = Offset(0f, b),
                             size = androidx.compose.ui.geometry.Size(w, h - b)
                         )
                         // Left
                         drawRect(
                             color = dimColor,
                             topLeft = Offset(0f, t),
                             size = androidx.compose.ui.geometry.Size(l, b - t)
                         )
                         // Right
                         drawRect(
                             color = dimColor,
                             topLeft = Offset(r, t),
                             size = androidx.compose.ui.geometry.Size(w - r, b - t)
                         )
                         
                         // UI: Border
                         drawRect(
                             color = Color.White,
                             topLeft = Offset(l, t),
                             size = androidx.compose.ui.geometry.Size(r - l, b - t),
                             style = Stroke(2.dp.toPx())
                         )
                         
                         // UI: Handles
                         val handleRadius = 8.dp.toPx()
                         drawCircle(Color.White, handleRadius, Offset(l, t))
                         drawCircle(Color.White, handleRadius, Offset(r, t))
                         drawCircle(Color.White, handleRadius, Offset(l, b))
                         drawCircle(Color.White, handleRadius, Offset(r, b))
                     }
                 }
            }
        }
    }
}
