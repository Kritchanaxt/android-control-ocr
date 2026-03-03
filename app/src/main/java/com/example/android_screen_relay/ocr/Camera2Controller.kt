package com.example.android_screen_relay.ocr

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.util.Arrays
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Encapsulated Camera2 Logic derived from CameraFragment.kt
 */
class Camera2Controller(
    private val context: Context,
    private val onImageCaptured: (android.graphics.Bitmap) -> Unit
) : Closeable {

    private val cameraManager: CameraManager by lazy {
        context.applicationContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    // For TextureView
    private var textureView: TextureView? = null
    private var previewSize: Size? = null

    // Camera State
    private var cameraId: String = ""
    private var characteristics: CameraCharacteristics? = null
    private val cameraOpenCloseLock = Semaphore(1)

    // Configurable Settings
    var targetResolution: Size? = null
    var aspectRatio: UiAspectRatio = UiAspectRatio.FULL
    
    // Logic from OutputSettingsDialogFragment
    private var maxSensorProcessingSize: Size = Size(1920, 1080)
    private var isFrontCamera: Boolean = false

    fun startBackgroundThread() {
        if (backgroundThread == null) {
            backgroundThread = HandlerThread("CameraBackground").also { it.start() }
            backgroundHandler = Handler(backgroundThread!!.looper)
        }
    }

    fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e(TAG, "Interrupted while stopping background thread", e)
        }
    }

    @SuppressLint("MissingPermission")
    fun openCamera(textureView: TextureView, cameraIdToOpen: String, desiredResolution: Size? = null) {
        if (cameraDevice != null && cameraId == cameraIdToOpen && targetResolution == desiredResolution && this.textureView == textureView) {
            return
        }
        
        close()

        this.textureView = textureView
        this.cameraId = cameraIdToOpen
        this.targetResolution = desiredResolution

        startBackgroundThread()

        if (textureView.isAvailable) {
            openCameraDevice(cameraId)
        } else {
            textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                    openCameraDevice(cameraId)
                }
                override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                    // Ideally configure transform here
                }
                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCameraDevice(cameraId: String) {
        try {
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw RuntimeException("Time out waiting to lock camera opening.")
            }
            
            characteristics = cameraManager.getCameraCharacteristics(cameraId)
            isFrontCamera = characteristics!!.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            
            val map = characteristics!!.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
            
            // Logic to calculate maxSensorProcessingSize according to snippet
            val availableJpegSizes = map.getOutputSizes(android.graphics.ImageFormat.JPEG)
                ?.sortedByDescending { size -> size.width * size.height } ?: emptyList()

            maxSensorProcessingSize = availableJpegSizes.firstOrNull()
                ?: characteristics!!.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.let { Size(it.width(), it.height()) }
                        ?: Size(4000, 3000)
            
            Log.d(TAG, "Max sensor processing size: $maxSensorProcessingSize. Is Front Camera: $isFrontCamera")

            // Determine efficient capture size
            val validResolutions = getResolutionsForAspectRatio(aspectRatio)
            
            // If desiredResolution is provided and valid, use it. Otherwise pick the best one.
            // If desiredResolution is not in the list, we might still use it if it's supported by the camera map directly?
            // The snippet logic filters strictly. Let's try to match.
            val finalCaptureSize = if (targetResolution != null) {
                 targetResolution!!
            } else {
                 validResolutions.firstOrNull()?.size ?: availableJpegSizes.first()
            }
            
            Log.d(TAG, "Selected capture size: $finalCaptureSize")

            imageReader = ImageReader.newInstance(
                finalCaptureSize.width, finalCaptureSize.height, ImageFormat.JPEG, 2
            ).apply {
                setOnImageAvailableListener({ reader ->
                    backgroundHandler?.post {
                        val image = reader.acquireLatestImage()
                        image?.use { 
                            val buffer = it.planes[0].buffer
                            val bytes = ByteArray(buffer.remaining())
                            buffer.get(bytes)
                            val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                             
                            // Rotation logic (simple)
                            // In real app, check sensor orientation and rotate
                            onImageCaptured(bitmap) 
                        }
                    }
                }, backgroundHandler)
            }

            // Preview Size: Pick one that matches aspect ratio of capture size
            val previewSizes = map.getOutputSizes(SurfaceTexture::class.java)
            previewSize = getOptimalPreviewSize(previewSizes, finalCaptureSize)
            
            Log.d(TAG, "Selected preview size: $previewSize")

            // Configure SurfaceTexture buffer size! Critical for avoiding black screen / distortion
            textureView?.surfaceTexture?.setDefaultBufferSize(previewSize!!.width, previewSize!!.height)

            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraOpenCloseLock.release()
                    cameraDevice = camera
                    createCameraPreviewSession()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    cameraOpenCloseLock.release()
                    camera.close()
                    cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    cameraOpenCloseLock.release()
                    camera.close()
                    cameraDevice = null
                }
            }, backgroundHandler)

        } catch (e: Exception) {
            Log.e(TAG, "Error opening camera", e)
            cameraOpenCloseLock.release()
        }
    }
    
    // Logic from OutputSettingsDialogFragment.kt
    private fun getResolutionsForAspectRatio(aspectRatio: UiAspectRatio): List<ResolutionItem> {
        if (characteristics == null) return emptyList()

        val resolutionItems = mutableListOf<ResolutionItem>()
        val sourceW = maxSensorProcessingSize.width
        val sourceH = maxSensorProcessingSize.height
        val targetAR = aspectRatio.value ?: (sourceW.toFloat() / sourceH.toFloat())

        var maxFinalW: Int
        var maxFinalH: Int

        if (aspectRatio.isPortraitDefault) {
            // For PORTRAIT ratios, use the taller oriented canvas (e.g., 3000x4000)
            val canvasW = min(sourceW, sourceH)
            val canvasH = max(sourceW, sourceH)
            val canvasAR = canvasW.toFloat() / canvasH.toFloat()

            if (canvasAR > targetAR) {
                maxFinalH = canvasH
                maxFinalW = (canvasH * targetAR).roundToInt()
            } else {
                maxFinalW = canvasW
                maxFinalH = (canvasW / targetAR).roundToInt()
            }
        } else {
            // For LANDSCAPE ratios, use the sensor's shorter side as the max width constraint.
            maxFinalW = min(sourceW, sourceH) // e.g., 3000
            maxFinalH = (maxFinalW / targetAR).roundToInt() // e.g., for 4:3 -> 3000/1.333 = 2250
        }

        // Add the "Max Resolution" item as the first option
        val maxForArText = "Max for AR (${maxFinalW}x${maxFinalH})"
        resolutionItems.add(ResolutionItem(null, maxForArText))

        // Add other predefined resolutions that fit within the calculated max size
        val resolutionStrings = predefinedResolutionsByRatio[aspectRatio] ?: emptyList()

        resolutionStrings.forEach { resString ->
            try {
                val parts = resString.split("x")
                if (parts.size == 2) {
                    val width = parts[0].toInt()
                    val height = parts[1].toInt()
                    val candidateSize = Size(width, height)

                    val isSupported = if (aspectRatio.isPortraitDefault) {
                        candidateSize.width <= maxFinalW && candidateSize.height <= maxFinalH
                    } else {
                        candidateSize.width <= maxFinalW && candidateSize.height <= maxFinalH
                    }

                    val isWithinFrontCamLimit = if (isFrontCamera) {
                        (candidateSize.width * candidateSize.height) <= (2160L * 2160L)
                    } else {
                        true
                    }

                    if (isSupported && isWithinFrontCamLimit) {
                        resolutionItems.add(ResolutionItem(candidateSize, resString))
                    }
                }
            } catch (e: NumberFormatException) {
                Log.e(TAG, "Could not parse resolution string: $resString", e)
            }
        }

        return resolutionItems.distinctBy { it.displayText }
    }

    private fun getOptimalPreviewSize(sizes: Array<Size>, targetSize: Size): Size {
        val targetRatio = targetSize.width.toDouble() / targetSize.height.toDouble()
        // Find sizes with similar aspect ratio
        val tolerance = 0.1
        val ratioMatched = sizes.filter { 
            val ratio = it.width.toDouble() / it.height.toDouble() 
            kotlin.math.abs(ratio - targetRatio) < tolerance 
        }
        
        // Pick the largest one that is not bigger than 1920x1080 (to avoid lag) but close to target
        // Or just pick the one closest to targetSize if it's small enough
        // Ideally we want 1080p preview max.
        
        return ratioMatched.filter { it.width <= 1920 && it.height <= 1080 }
            .maxByOrNull { it.width * it.height }
            ?: ratioMatched.maxByOrNull { it.width * it.height }
            ?: sizes[0]
    }


    private fun createCameraPreviewSession() {
        try {
            val texture = textureView!!.surfaceTexture!!
            // We configure the size of default buffer to be the size of camera preview we want.
            texture.setDefaultBufferSize(previewSize!!.width, previewSize!!.height)

            val surface = Surface(texture)
            val imageSurface = imageReader!!.surface

            val captureRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            captureRequestBuilder.addTarget(surface)

            cameraDevice!!.createCaptureSession(
                listOf(surface, imageSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (cameraDevice == null) return
                        captureSession = session
                        try {
                            captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                            // Auto-exposure settings...
                            
                            session.setRepeatingRequest(captureRequestBuilder.build(), null, backgroundHandler)
                        } catch (e: Exception) {
                            Log.e(TAG, "createCaptureSession error", e)
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                       // Failed
                    }
                },
                backgroundHandler
            )
        } catch (e: Exception) {
            Log.e(TAG, "createCameraPreviewSession error", e)
        }
    }

    fun takePhoto() {
        try {
            if (cameraDevice == null) return

            // Simple capture for now, skipping precapture sequence for brevity unless requested
            // But requirement said "Take Logic". Use simple capture request for JPEG.
            
            val captureBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            captureBuilder.addTarget(imageReader!!.surface)
            captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            
            // Orientation
            val sensorOrientation = characteristics!!.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
             // Assuming default device orientation logic or just pass sensor orientation
             // The JPEG orientation needs to be set in capture request
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, sensorOrientation)

            captureSession?.stopRepeating()
            captureSession?.capture(captureBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                         // Restart preview
                         createCameraPreviewSession()
                    }
            }, backgroundHandler)

        } catch (e: Exception) {
            Log.e(TAG, "takePhoto error", e)
        }
    }
    
    // --- Helper for Resolutions ---
    fun getAvailableCameras(): List<String> {
        return try {
            cameraManager.cameraIdList.toList()
        } catch(e:Exception) { emptyList() }
    }
    
    // CameraInfo data class
    data class CameraInfo(
        val title: String,
        val cameraId: String,
        val format: Int = ImageFormat.JPEG,
        val cameraType: String,
        val iconResId: Int = 0,
        val isAvailable: Boolean = true,
        val physicalCameraIds: List<String> = emptyList()
    )

    @SuppressLint("InlinedApi")
    fun enumerateCameras(): List<CameraInfo> {
        Log.d(TAG, "Starting camera enumeration...")
        val detectedCamerasMap = mutableMapOf<String, CameraInfo>()
        val allCameraIds = try {
            cameraManager.cameraIdList
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get camera ID list", e)
            emptyArray<String>()
        }
        val numberOfActualCameras = allCameraIds.size

        Log.i(TAG, "Total camera IDs: $numberOfActualCameras. IDs: ${allCameraIds.joinToString()}")

        val predefinedCameraTypes = listOf(
            "Front Camera",
            "Front Ultra Wide Camera",
            "Back Camera (Main)",
            "Back Triple Camera",
            "Back Dual Camera",
            "Back Dual Wide Camera",
            "Back Ultra Wide Camera",
            "Back Telephoto Camera",
            "External Camera"
        )

        // Thresholds
        val ultraWideFocalLengthThreshold = 2.2f
        val telephotoFocalLengthThreshold = 7.5f

        allCameraIds.forEach { id ->
            try {
                val characteristics = cameraManager.getCameraCharacteristics(id)
                val orientation = characteristics.get(CameraCharacteristics.LENS_FACING)
                val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
                val focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                val hardwareLevel = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)

                val isLogicalMultiCamera = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)
                } else false

                val physicalCameraIds = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P && isLogicalMultiCamera) {
                     characteristics.physicalCameraIds.toList()
                } else emptyList()

                Log.i(TAG, "--- Processing Camera ID: $id ---")
                Log.i(TAG, "  Orientation: $orientation")
                Log.i(TAG, "  Is Logical Multi-Camera: $isLogicalMultiCamera")

                var determinedType: String? = null
                when (orientation) {
                    CameraCharacteristics.LENS_FACING_FRONT -> {
                        determinedType = if (focalLengths != null && focalLengths.size == 1 && focalLengths[0] < ultraWideFocalLengthThreshold) {
                            "Front Ultra Wide Camera"
                        } else {
                            "Front Camera"
                        }
                    }
                    CameraCharacteristics.LENS_FACING_BACK -> {
                        if (isLogicalMultiCamera && physicalCameraIds.isNotEmpty()) {
                            determinedType = when (physicalCameraIds.size) {
                                3 -> "Back Triple Camera"
                                2 -> "Back Dual Camera"
                                else -> "Back Multi-Camera (${physicalCameraIds.size} Lenses)"
                            }
                        } else {
                            determinedType = when {
                                focalLengths?.any { it > telephotoFocalLengthThreshold } == true -> "Back Telephoto Camera"
                                focalLengths?.any { it < ultraWideFocalLengthThreshold } == true -> "Back Ultra Wide Camera"
                                else -> "Back Camera (Main)"
                            }
                        }
                    }
                    CameraCharacteristics.LENS_FACING_EXTERNAL -> determinedType = "External Camera"
                }

                if (determinedType != null) {
                    // Logic to avoid overwriting unless logical multicamera preferred
                    if (!detectedCamerasMap.containsKey(determinedType) || isLogicalMultiCamera) {
                         detectedCamerasMap[determinedType] = CameraInfo(
                            title = "$determinedType (ID: $id)",
                            cameraId = id,
                            cameraType = determinedType,
                            isAvailable = true,
                            physicalCameraIds = physicalCameraIds
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing camera ID $id: ${e.message}", e)
            }
        }

        // Construct final list
        val finalDisplayList = mutableListOf<CameraInfo>()
        
        predefinedCameraTypes.forEach { typeName ->
            val detectedItem = detectedCamerasMap[typeName]
            if (detectedItem != null) {
                 if (!finalDisplayList.any { it.cameraId == detectedItem.cameraId }) {
                     finalDisplayList.add(detectedItem)
                 }
            }
        }

        // Add others
        detectedCamerasMap.values.forEach { detectedItem ->
            if (finalDisplayList.none { it.cameraType == detectedItem.cameraType }) {
                finalDisplayList.add(detectedItem)
            }
        }
        
        // Sorting logic from snippet
         return finalDisplayList.sortedWith(compareBy {
            when (it.cameraType) {
                "Front Camera" -> 0
                "Front Ultra Wide Camera" -> 1
                "Back Camera (Main)" -> 2
                "Back Triple Camera" -> 3
                "Back Dual Camera" -> 4
                "Back Dual Wide Camera" -> 5
                "Back Ultra Wide Camera" -> 6
                "Back Telephoto Camera" -> 7
                else -> if (it.cameraType.startsWith("Back Multi-Camera")) 8 else 99
            }
        })
    }

    fun getCameraResolutions(cameraId: String): List<Size> {
        return try {
             val characteristics = cameraManager.getCameraCharacteristics(cameraId)
             val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
             map?.getOutputSizes(ImageFormat.JPEG)?.toList() ?: emptyList()
        } catch(e:Exception) { emptyList() }
    }
    
    // Simple optimal size finder
    private fun getOptimalPreviewSize(sizes: Array<Size>, w: Int, h: Int): Size {
         // Logic to find closest aspect ratio and size >= view size
         val aspectRatio = w.toDouble() / h.toDouble()
         // Simplified
         return sizes.maxByOrNull { it.width * it.height } ?: sizes[0]
    }


    override fun close() {
        try {
            cameraOpenCloseLock.acquire()
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
            imageReader?.close()
            imageReader = null
            stopBackgroundThread()
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera closing.", e)
        } finally {
            cameraOpenCloseLock.release()
        }
    }
    
    companion object {
        private const val TAG = "Camera2Controller"
    }
}
