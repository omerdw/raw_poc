package com.example.raw

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
import android.hardware.camera2.CameraMetadata.CONTROL_MODE_OFF
import android.hardware.camera2.CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW
import android.media.Image
import android.media.ImageReader
import android.os.*
import androidx.appcompat.app.AppCompatActivity
import android.util.Log
import android.util.Size
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import java.util.*
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit


class MainActivity : AppCompatActivity() {

    private lateinit var cameraManager: CameraManager
    private lateinit var cameraDevice: CameraDevice
    private lateinit var captureSession: CameraCaptureSession
    private lateinit var imageReader: ImageReader
    private lateinit var backgroundThread: HandlerThread
    private lateinit var backgroundHandler: Handler
    private lateinit var processingThread: HandlerThread
    private lateinit var processingHandler: Handler
    private lateinit var surfaceView: SurfaceView


    private val cameraId: String by lazy {
        cameraManager.cameraIdList.first { id ->
            val characteristics = cameraManager.getCameraCharacteristics(id)
            val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
            capabilities?.contains(REQUEST_AVAILABLE_CAPABILITIES_RAW) == true &&
                    lensFacing == CameraCharacteristics.LENS_FACING_BACK
        }
    }

    private var latestRawImage: Image? = null // This is what you inspect during debugging

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        surfaceView = SurfaceView(this)
        setContentView(surfaceView)

        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 0)
        } else {
            surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    openCamera()
                }

                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    stopBackgroundThread()
                    latestRawImage?.close()
                    imageReader.close()
                    if (::cameraDevice.isInitialized) {
                        cameraDevice.close()
                    }
                }
            })
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                setupCapture()
            }

            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
            }

            override fun onError(camera: CameraDevice, error: Int) {
                camera.close()
            }
        }, Handler(Looper.getMainLooper()))
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread.looper)

        processingThread = HandlerThread("ImageProcessing").also { it.start() }
        processingHandler = Handler(processingThread.looper)
        Log.d("raw_image", "background thread started")
    }

    private fun stopBackgroundThread() {
        backgroundThread.quitSafely()
        backgroundThread.join()
        processingThread.quitSafely()
        processingThread.join()
    }
    private fun captureRawLoop() {
        val requestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            addTarget(imageReader.surface)
            set(CaptureRequest.CONTROL_MODE, CONTROL_MODE_OFF)
            set(CaptureRequest.SENSOR_EXPOSURE_TIME, 10000000L) // 10ms
            set(CaptureRequest.SENSOR_SENSITIVITY, 800)
        }

        backgroundHandler.post(object : Runnable {
            override fun run() {
                captureSession.capture(requestBuilder.build(), null, backgroundHandler)
                backgroundHandler.postDelayed(this, 10)
            }
        })
    }

    private fun setupCapture() {
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val rawSizes = map?.getOutputSizes(ImageFormat.RAW_SENSOR)
        val rawSize = rawSizes?.maxByOrNull { it.width * it.height } ?: Size(640, 480)
        startBackgroundThread()
        imageReader = ImageReader.newInstance(rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, 3)

        val Queue = LinkedBlockingDeque<Image>(1)
        imageReader.setOnImageAvailableListener({ reader ->
            reader.acquireLatestImage()?.let { image ->
                Log.d("RAW_CAPTURE", "image captured")
                Queue.pollFirst()?.close()
                Queue.offerFirst(image)
            }
        }, backgroundHandler)


        cameraDevice.createCaptureSession(
            listOf(imageReader.surface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    Log.d("CAMERA_SESSION", "onConfigured called")
                    captureSession = session
                    captureRawLoop()

                    processingHandler.post {
                        Log.d("PROCESSING_THREAD", "Image processing thread started")
                        var frameCount = 0
                        var lastFpsUpdateTime = System.nanoTime()
                        var lastConversionTime = 0L
                        
                        while (true) {
                            val image = Queue.pollFirst(500, TimeUnit.MILLISECONDS)
                            if (image != null) {
                                try {
                                    val startTime = System.nanoTime()
                                    val bitmap = rawToRgb(image)
                                    lastConversionTime = System.nanoTime() - startTime
                                    
                                    displayBitmap(bitmap)
                                    
                                    frameCount++
                                    val currentTime = System.nanoTime()
                                    val elapsedTime = currentTime - lastFpsUpdateTime
                                    
                                    if (elapsedTime >= 1_000_000_000) { // Update FPS every second
                                        val fps = frameCount * 1_000_000_000.0 / elapsedTime
                                        Log.d("PERFORMANCE", String.format("FPS: %.2f, Conversion time: %.2f ms", 
                                            fps, lastConversionTime / 1_000_000.0))
                                        frameCount = 0
                                        lastFpsUpdateTime = currentTime
                                    }
                                    
                                    Log.d("RAW_CAPTURE", "image posted: ${image.timestamp}")
                                } catch (e: Exception) {
                                    Log.e("RAW_CAPTURE", "Error processing image", e)
                                } finally {
                                    image.close()
                                }
                            } else {
                                Log.w("RAW_CAPTURE", "No image received in 500ms")
                            }
                        }
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e("Camera", "Capture session failed.")
                }
            },
            backgroundHandler
        )
    }

    private fun rawToRgb(image: Image): Bitmap {
        if (!OpenCVLoader.initDebug()) {
            Log.e("RAW_CAPTURE", "OpenCV initialization failed")
            return Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
        }

        val width = image.width
        val height = image.height
        val buffer = image.planes[0].buffer
        val data = ShortArray(buffer.remaining() / 2)
        buffer.asShortBuffer().get(data)
        Log.d("RAW_CAPTURE", "RAW data size: ${data.size}, expected: ${width * height}, sample: ${data.take(10).joinToString()}")

        val rawMat = Mat(height, width, CvType.CV_16U)
        rawMat.put(0, 0, data)

        // Demosaic to RGB
        val rgbMat = Mat()
        Imgproc.cvtColor(rawMat, rgbMat, Imgproc.COLOR_BayerGR2RGB)

        // Convert to Bitmap
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val rgbMat8 = Mat()
        Core.normalize(rgbMat, rgbMat, 0.0, 255.0, Core.NORM_MINMAX)
        rgbMat.convertTo(rgbMat8, CvType.CV_8UC3)
        Utils.matToBitmap(rgbMat8, bitmap)

        val pixel = bitmap.getPixel(width / 2, height / 2)
        Log.d("RAW_CAPTURE", "Bitmap created: ${bitmap.width}x${bitmap.height}, isRecycled: ${bitmap.isRecycled}, center pixel: $pixel")

        rawMat.release()
        rgbMat.release()
        rgbMat8.release()
        return bitmap
    }

    private fun displayBitmap(bitmap: Bitmap) {
        if (!::surfaceView.isInitialized) {
            Log.e("RAW_CAPTURE", "SurfaceView not initialized")
            bitmap.recycle()
            return
        }

        val holder = surfaceView.holder
        if (holder.surface.isValid) {
            val canvas: Canvas? = holder.lockCanvas()
            if (canvas != null) {
                try {
                    // Clear the canvas
                    canvas.drawColor(android.graphics.Color.WHITE)
                    // Draw the bitmap, scaling it to fit the SurfaceView
                    val scaleX = canvas.width.toFloat() / bitmap.width
                    val scaleY = canvas.height.toFloat() / bitmap.height
                    val scale = minOf(scaleX, scaleY) // Maintain aspect ratio
                    val scaledWidth = bitmap.width * scale
                    val scaledHeight = bitmap.height * scale
                    val left = (canvas.width - scaledWidth) / 2
                    val top = (canvas.height - scaledHeight) / 2
                    canvas.scale(scale, scale)
                    canvas.drawBitmap(bitmap, left / scale, top / scale, null)
                } finally {
                    // Unlock the canvas and post the changes
                    holder.unlockCanvasAndPost(canvas)
                }
            } else {
                Log.w("RAW_CAPTURE", "Canvas is null")
            }
        } else {
            Log.w("RAW_CAPTURE", "Surface is not valid")
        }
        // Recycle the bitmap to free memory
        bitmap.recycle()
    }

    override fun onDestroy() {
        super.onDestroy()
        latestRawImage?.close()
        imageReader.close()
        cameraDevice.close()
        stopBackgroundThread()
    }
}
