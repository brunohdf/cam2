package com.brx.camera2

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import kotlinx.android.synthetic.main.activity_main.*
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var galleryFolder: File
    private lateinit var surfaceTextureListener: TextureView.SurfaceTextureListener
    private lateinit var captureRequest: CaptureRequest
    private lateinit var captureRequestBuilder: CaptureRequest.Builder
    private lateinit var cameraId: String
    private lateinit var previewSize: Size
    private lateinit var cameraManager: CameraManager

    private var cameraCaptureSession: CameraCaptureSession? = null
    private var cameraDevice: CameraDevice? = null
    private var backgroundHandler: Handler? = null
    private var backgroundThread: HandlerThread? = null
    private var cameraFacing: Int = -1
    private var CAMERA_REQUEST_CODE: Int = 999

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE), CAMERA_REQUEST_CODE)

        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        cameraFacing = CameraCharacteristics.LENS_FACING_BACK

        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
                setUpCamera()
                openCamera()
            }

            override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                return false
            }

            override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) = Unit

            override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture)  = Unit
        }
    }

    override fun onResume() {
        super.onResume()

        openBackgroundThread()
        if (texture_view.isAvailable) {
            setUpCamera()
            openCamera()
        } else {
            texture_view.surfaceTextureListener = surfaceTextureListener
        }

        createImageGallery()
        fab_take_photo.setOnClickListener {
            onTakePhotoButtonClicked()
            Toast.makeText(this, "take pic", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onStop() {
        super.onStop()
        closeCamera()
        closeBackgroundThread()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == CAMERA_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                createImageGallery()
            } else {
                Toast.makeText(this, "Permission Denied!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun onTakePhotoButtonClicked() {
        lock()
        var outputPhoto: FileOutputStream? = null
        try {
            outputPhoto = FileOutputStream(createImageFile(galleryFolder))
            texture_view.bitmap
                .compress(Bitmap.CompressFormat.PNG, 100, outputPhoto)

        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            unlock()
            try {
                outputPhoto?.close()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }

    private fun lock() {
        try {
            cameraCaptureSession?.capture(
                captureRequestBuilder.build(),
                null, backgroundHandler
            )
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }

    }

    private fun unlock() {
        try {
            cameraCaptureSession?.setRepeatingRequest(
                captureRequestBuilder.build(),
                null, backgroundHandler
            )
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }

    }

    private fun closeCamera() {
        cameraCaptureSession?.let {
            it.close()
            cameraCaptureSession = null
        }

        cameraDevice?.let {
            it.close()
            cameraDevice = null
        }
    }

    private fun closeBackgroundThread() {
        backgroundHandler?.let {
            backgroundThread?.quitSafely()
            backgroundThread = null
            backgroundHandler = null
        }
    }

    private fun setUpCamera() {
        try {
            for (cameraId in cameraManager.cameraIdList) {
                val cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId)
                if (cameraCharacteristics.get(CameraCharacteristics.LENS_FACING) == cameraFacing) {
                    val streamConfigurationMap = cameraCharacteristics.get(
                        CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
                    )
                    previewSize = streamConfigurationMap!!.getOutputSizes(SurfaceTexture::class.java)[0]
                    this.cameraId = cameraId
                }
            }
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }

    }

    private fun openCamera() {
        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                cameraManager.openCamera(cameraId, stateCallback, backgroundHandler)
            }
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(cameraDevice: CameraDevice) {
            this@MainActivity.cameraDevice = cameraDevice
            createPreviewSession()
        }

        override fun onDisconnected(cameraDevice: CameraDevice) {
            cameraDevice.close()
            this@MainActivity.cameraDevice = null
        }

        override fun onError(cameraDevice: CameraDevice, error: Int) {
            cameraDevice.close()
            this@MainActivity.cameraDevice = null
        }
    }

    private fun createPreviewSession() {
        try {
            val surfaceTexture = texture_view.surfaceTexture
            surfaceTexture.setDefaultBufferSize(texture_view.width, texture_view.height)
            val previewSurface = Surface(surfaceTexture)
            captureRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            captureRequestBuilder.addTarget(previewSurface)

            cameraDevice!!.createCaptureSession(
                Collections.singletonList(previewSurface),
                object : CameraCaptureSession.StateCallback() {

                    override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
                        if (cameraDevice == null) return

                        try {
                            captureRequest = captureRequestBuilder.build()
                            this@MainActivity.cameraCaptureSession = cameraCaptureSession
                            this@MainActivity.cameraCaptureSession?.setRepeatingRequest(captureRequest, null, backgroundHandler)
                        } catch (e: CameraAccessException) {
                            e.printStackTrace()
                        }

                    }

                    override fun onConfigureFailed(cameraCaptureSession: CameraCaptureSession) = Unit
                }, backgroundHandler
            )
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }

    }

    private fun openBackgroundThread() {
        backgroundThread = HandlerThread("camera_background_thread")
        backgroundThread?.let {
            it.start()
            backgroundHandler = Handler(it.looper)
        }
    }

    private fun createImageGallery() {
        val storageDirectory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        galleryFolder = File(storageDirectory, resources.getString(R.string.app_name))
        if (!galleryFolder.exists()) {
            val wasCreated = galleryFolder.mkdirs()
            if (!wasCreated) {
                Log.e("CapturedImages", "Failed to create directory")
            }
        }
    }

    @Throws(IOException::class)
    private fun createImageFile(galleryFolder: File): File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val imageFileName = "image_" + timeStamp + "_"
        return File.createTempFile(imageFileName, ".jpg", galleryFolder)
    }
}
