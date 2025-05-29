package com.example.raw

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
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
import java.util.*


class MainActivity : AppCompatActivity() {

    private lateinit var cameraManager: CameraManager
    private lateinit var cameraDevice: CameraDevice
    private lateinit var captureSession: CameraCaptureSession
    private lateinit var imageReader: ImageReader
    private lateinit var backgroundThread: HandlerThread
    private lateinit var backgroundHandler: Handler

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
        val surfaceView = SurfaceView(this)
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
                override fun surfaceDestroyed(holder: SurfaceHolder) {}
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
        Log.d("raw_image", "background thread started")
    }

    private fun stopBackgroundThread() {
        backgroundThread.quitSafely()
        backgroundThread.join()
    }
    private fun captureRawLoop() {
        val requestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            addTarget(imageReader.surface)
            set(CaptureRequest.CONTROL_MODE, CONTROL_MODE_OFF)
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
        imageReader =
            ImageReader.newInstance(rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, 2)
        imageReader.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage()
            latestRawImage?.close()
            latestRawImage = image
            Log.d("RAW_CAPTURE", "Captured RAW: ${image.timestamp}")
        }, backgroundHandler)



        cameraDevice.createCaptureSession(
            listOf(imageReader.surface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    captureRawLoop()
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e("Camera", "Capture session failed.")
                }
            },
            backgroundHandler
        )
    }
    override fun onDestroy() {
        super.onDestroy()
        latestRawImage?.close()
        imageReader.close()
        cameraDevice.close()
        stopBackgroundThread()
    }
}
