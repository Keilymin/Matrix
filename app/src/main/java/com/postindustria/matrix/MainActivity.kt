package com.postindustria.matrix

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.MediaRecorder
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.util.Log
import android.util.Size
import android.util.SizeF
import android.util.SparseIntArray
import android.view.Surface
import android.view.TextureView
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.util.*
import kotlin.math.atan


val TAG = MainActivity::class.simpleName
const val CAMERA_REQUEST_RESULT = 1

class MainActivity : AppCompatActivity() {

    var step = 0f
    private val time = System.currentTimeMillis().toString().replace(":", ".")
    private var focusDistance = 5f
    private var focalLength = -1f
    private var mm_to_pixel_x = -1f
    private var mm_to_pixel_y = -1f
    lateinit var seekBar: SeekBar
    lateinit var hAngle: TextView
    lateinit var vAngle: TextView
    lateinit var focus: TextView
    lateinit var resolution: TextView
    lateinit var cameraNum: TextView
    val mainHandler = Handler(Looper.getMainLooper())
    private var fovx : Double = 0.0
    private var fovy : Double = 0.0
    private lateinit var textureView: TextureView
    private val cameras = arrayListOf<String>()
    private lateinit var switch: Button
    private lateinit var cameraId: String
    private var cameraArrow = 0
    private lateinit var backgroundHandlerThread: HandlerThread
    private lateinit var backgroundHandler: Handler
    private lateinit var cameraManager: CameraManager
    private lateinit var cameraDevice: CameraDevice
    private lateinit var captureRequestBuilder: CaptureRequest.Builder
    private lateinit var cameraCaptureSession: CameraCaptureSession
    private var previewSize = arrayOf<Size>()
    private var videoSize = arrayOf<Size>()
    private var formatSize = Size(0,0)
    private lateinit var customHandler : CustomizedExceptionHandler
    private var shouldProceedWithOnResume: Boolean = true
    private var orientations: SparseIntArray = SparseIntArray(4).apply {
        append(Surface.ROTATION_0, 0)
        append(Surface.ROTATION_90, 90)
        append(Surface.ROTATION_180, 180)
        append(Surface.ROTATION_270, 270)
    }

    private lateinit var mediaRecorder: MediaRecorder
    private var isRecording: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        customHandler = CustomizedExceptionHandler("/mnt/sdcard/")
        Thread.setDefaultUncaughtExceptionHandler(customHandler)
        customHandler.addToLog("Application Started")


        setContentView(R.layout.activity_main)

        seekBar = findViewById(R.id.seekBar)
        seekBar.min = 1
        seekBar.max = 100
        switch = findViewById(R.id.change)
        hAngle = findViewById(R.id.textView)
        vAngle = findViewById(R.id.textView2)
        focus = findViewById(R.id.textView3)
        cameraNum = findViewById(R.id.textView4)
        resolution = findViewById(R.id.textView5)
        textureView = findViewById(R.id.texture_view)
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager


        findViewById<Button>(R.id.record_video_btn).apply {
            setOnClickListener {
                customHandler.addToLog("Button clicked | isRecording - $isRecording")

                if (isRecording) {
                    seekBar.isEnabled = true
                    mediaRecorder.stop()
                    mediaRecorder.reset()

                    cameraDevice.close()
                    connectCamera()
                    findViewById<Button>(R.id.record_video_btn).setText("Record")
                } else {
                    seekBar.isEnabled = false
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        mediaRecorder = MediaRecorder(this@MainActivity)
                    } else{
                        mediaRecorder = MediaRecorder()
                    }
                    customHandler.addToLog("mediaRecorder created")
                    setupMediaRecorder()
                    startRecording()
                    findViewById<Button>(R.id.record_video_btn).setText("Stop")
                }

                isRecording = !isRecording
            }
        }
        val ar = mutableListOf<String>()
        if (!wasPermissionWasGiven(Manifest.permission.CAMERA)) {
            ar.add(Manifest.permission.CAMERA)
        }
        if (!wasPermissionWasGiven(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            ar.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        if (!wasPermissionWasGiven(Manifest.permission.READ_EXTERNAL_STORAGE)) {

            ar.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
//        if (Build.VERSION.SDK_INT >= 30) {
//            if (!Environment.isExternalStorageManager()) {
//                val getpermission = Intent()
//                getpermission.action = Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION
//                startActivity(getpermission)
//            }
//        }

        switch.setOnClickListener {
            cameraArrow++
            if (cameraArrow >= cameras.size)
                cameraArrow = 0
            cameraId = cameras[cameraArrow]
            cameraDevice.close()
            connectCamera()
            val cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId)
            setFormats(cameraCharacteristics)
        }

        if (ar.size > 0) {
            requestPermissions(ar.toTypedArray(), CAMERA_REQUEST_RESULT)
        }

        startBackgroundThread()

    }

    @SuppressLint("MissingPermission")
    override fun onResume() {
        super.onResume()
        startBackgroundThread()
        if (textureView.isAvailable && shouldProceedWithOnResume) {
            setupCamera()
        } else if (!textureView.isAvailable) {
            textureView.surfaceTextureListener = surfaceTextureListener
        }
        shouldProceedWithOnResume = !shouldProceedWithOnResume
    }

    private fun setupCamera() {
        cameras.clear()
        val cameraIds: Array<String> = cameraManager.cameraIdList

        for (id in cameraIds) {
            val cameraCharacteristics = cameraManager.getCameraCharacteristics(id)

            //If we want to choose the rear facing camera instead of the front facing one
            if (cameraCharacteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT) {
                continue
            }

            val focalLengths: FloatArray? = cameraCharacteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            Log.d("INFO", "Focal lengths:")
            Log.d("INFO", Arrays.toString(focalLengths))

            val calibration: FloatArray? = cameraCharacteristics.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)
            Log.d("INFO", "Calibration:")
            Log.d("INFO", Arrays.toString(calibration))

            val physicalSize: SizeF? = cameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            Log.d("INFO", "Physical size (mm): $physicalSize")

            val pixelSize: Size? = cameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
            Log.d("INFO", "Pixel size: $pixelSize")

            mm_to_pixel_x = pixelSize!!.width / physicalSize!!.width
            mm_to_pixel_y = pixelSize.height / physicalSize.height

            val minimumLensFocus = cameraCharacteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
            if (minimumLensFocus != null && minimumLensFocus != 0.0f) {
                step =
                    cameraCharacteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)!!
                        .toFloat() / 100
                Log.d("INFO", "Focus distance step:$step")
            } else step = 0.1f
            mainHandler.post(object : Runnable {
                override fun run() {
                    hAngle.text = " fovx  $fovx"
                    vAngle.text = " fovy  $fovy"
                    mainHandler.postDelayed(this, 2500)
                }
            })
            seekBar.progress = 50
            focus.setText("focus " + seekBar.progress * step)

            setFormats(cameraCharacteristics)
            textureView.setTransform(Utility.computeTransformationMatrix(textureView,cameraCharacteristics,formatSize,Surface.ROTATION_0))

            cameraId = id
            cameras.add(id)

        }
        cameraArrow = cameras.indexOf(cameraId)
        cameraNum.setText("number of cameras "+cameras.size)

    }

    private fun setFormats(cameraCharacteristics: CameraCharacteristics) {
        val streamConfigurationMap: StreamConfigurationMap? =
            cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(
                seekBar: SeekBar?,
                progress: Int,
                fromUser: Boolean
            ) {
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                focusDistance = seekBar!!.progress.toFloat() * step
                cameraDevice.close()
                connectCamera()
                focus.setText("focus " + (seekBar.progress * step))
            }
        })

        if (streamConfigurationMap != null) {
            val capabilities: IntArray? =
                cameraCharacteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            val cap =
                capabilities?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT)
            Log.e("Is support depth?", cap.toString())
            previewSize =
                cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
                    .getOutputSizes(ImageFormat.JPEG)
            videoSize =
                cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
                    .getOutputSizes(MediaRecorder::class.java)

            val set = mutableSetOf<Size>()
            for (i in previewSize){
                for (j in videoSize){
                    if (i.width == j.width && i.height == j.height){
                        set.add(i)
                    }
                }
            }
            var formatSizeList = set.toList()
            formatSizeList = formatSizeList.sortedByDescending { it.width * it.height }
            Log.e("sd","$formatSizeList")
            for (i in formatSizeList){
                if (i.width.toFloat() / i.height.toFloat() == 16f / 9f){
                    formatSize = i
                    Log.e("asd",i.toString())
                    break
                }
            }


            resolution.setText(formatSize.width.toString() + "\n" + formatSize.height)
        }
    }

    private fun wasPermissionWasGiven(permission: String): Boolean {
        if (ContextCompat.checkSelfPermission(
                this,
                permission
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            return true
        }

        return false
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            surfaceTextureListener.onSurfaceTextureAvailable(
                textureView.surfaceTexture!!,
                textureView.width,
                textureView.height
            )
        } else if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        ) {

        } else {
            Toast.makeText(
                this,
                "Camera permission is needed to run this application",
                Toast.LENGTH_LONG
            )
                .show()
            if (ActivityCompat.shouldShowRequestPermissionRationale(
                    this,
                    Manifest.permission.CAMERA
                )
            ) {
                val intent = Intent()
                intent.action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                intent.data = Uri.fromParts("package", this.packageName, null)
                startActivity(intent)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectCamera() {
        cameraManager.openCamera(cameraId, cameraStateCallback, backgroundHandler)
    }

    private fun setupMediaRecorder() {
        mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        mediaRecorder.setVideoSize( formatSize.width, formatSize.height)
        mediaRecorder.setVideoFrameRate(30)
        mediaRecorder.setOrientationHint(90)
        mediaRecorder.setOutputFile(File(createFile(), "$time.mp4").path)
        mediaRecorder.setVideoEncodingBitRate(10_000_000)
        mediaRecorder.prepare()
        customHandler.addToLog("mediaRecorder setup")
    }

    private fun startRecording() {
        val surfaceTexture: SurfaceTexture? = textureView.surfaceTexture
        surfaceTexture?.setDefaultBufferSize(formatSize.width, formatSize.height)
        val previewSurface: Surface = Surface(surfaceTexture)
        val recordingSurface = mediaRecorder.surface

        captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)

        disableAutoFocus()

        captureRequestBuilder.addTarget(previewSurface)
        captureRequestBuilder.addTarget(recordingSurface)

        cameraDevice.createCaptureSession(
            listOf(previewSurface, recordingSurface),
            captureStateVideoCallback,
            backgroundHandler
        )
        customHandler.addToLog("mediaRecorder start recording")

        val file = File(createFile(), "$time.txt")
        file.createNewFile()
        val stream = FileOutputStream(file)
        try {
            stream.write("$fovx $fovy".toByteArray())
            customHandler.addToLog("file saved")
        } finally {
            stream.close()
        }

    }

    private fun disableAutoFocus() {
        captureRequestBuilder.set(
            CaptureRequest.CONTROL_AF_MODE,
            CaptureRequest.CONTROL_AF_MODE_OFF
        )
        captureRequestBuilder.set(
            CaptureRequest.CONTROL_AF_TRIGGER,
            CaptureRequest.CONTROL_AF_TRIGGER_CANCEL
        )
        //captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)

        //captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_OFF)
//        captureRequestBuilder.set(
//            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
//            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
//        )
//        captureRequestBuilder.set(
//            CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
//            CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF
//        )

        val focusDistance: Float? = captureRequestBuilder.get(CaptureRequest.LENS_FOCUS_DISTANCE)
        Log.d("INFO", "Focus distance:" + focusDistance.toString())

        captureRequestBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, this.focusDistance)

        val focusDistance2: Float? = captureRequestBuilder.get(CaptureRequest.LENS_FOCUS_DISTANCE)
        Log.d("INFO", "Focus distance:" + focusDistance2.toString())

        focalLength = captureRequestBuilder.get(CaptureRequest.LENS_FOCAL_LENGTH)!!
        Log.d("INFO", "Focal length (mm): $focalLength")

        val pixelFocalLengthX = focalLength * mm_to_pixel_x
        val pixelFocalLengthY = focalLength * mm_to_pixel_y

        Log.d("INFO", "Focal length x (pixel): $pixelFocalLengthX")
        Log.d("INFO", "Focal length y (pixel): $pixelFocalLengthY")

        val zoomCrop = Rect(0, 0, formatSize.width, formatSize.height)
        captureRequestBuilder.set(CaptureRequest.SCALER_CROP_REGION, zoomCrop)
        val cropRegion = captureRequestBuilder.get(CaptureRequest.SCALER_CROP_REGION)

        Log.d("INFO", "Crop region:$cropRegion")
        if(cropRegion != null) {
            fovx = 2.0f * atan((cropRegion!!.width() / (2.0f * pixelFocalLengthX)).toDouble())
            fovy = 2.0f * atan((cropRegion.height() / (2.0f * pixelFocalLengthY)).toDouble())
        }

        Log.d("INFO", "fovx: $fovx")
        Log.d("INFO", "fovy: $fovy")

//        captureRequestBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, 1000000L)
    }

    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        @SuppressLint("MissingPermission")
        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
            if (wasPermissionWasGiven(Manifest.permission.CAMERA)) {
                setupCamera()
                connectCamera()
            }
        }

        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {

        }

        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
            return true
        }

        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {

        }
    }

    /**
     * Camera State Callbacks
     */

    private val cameraStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraDevice = camera
            val surfaceTexture: SurfaceTexture? = textureView.surfaceTexture
            surfaceTexture?.setDefaultBufferSize(formatSize.width, formatSize.height)
            val previewSurface: Surface = Surface(surfaceTexture)

            captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)

            disableAutoFocus()

            captureRequestBuilder.addTarget(previewSurface)

            cameraDevice.createCaptureSession(listOf(previewSurface), captureStateCallback, null)

        }

        override fun onDisconnected(cameraDevice: CameraDevice) {
        }

        override fun onError(cameraDevice: CameraDevice, error: Int) {
            val errorMsg = when (error) {
                ERROR_CAMERA_DEVICE -> "Fatal (device)"
                ERROR_CAMERA_DISABLED -> "Device policy"
                ERROR_CAMERA_IN_USE -> "Camera in use"
                ERROR_CAMERA_SERVICE -> "Fatal (service)"
                ERROR_MAX_CAMERAS_IN_USE -> "Maximum cameras in use"
                else -> "Unknown"
            }
            Log.e(TAG, "Error when trying to connect camera $errorMsg")
        }
    }

    /**
     * Background Thread
     */
    private fun startBackgroundThread() {
        backgroundHandlerThread = HandlerThread("CameraVideoThread")
        backgroundHandlerThread.start()
        backgroundHandler = Handler(backgroundHandlerThread.looper)
    }

    private fun stopBackgroundThread() {
        backgroundHandlerThread.quitSafely()
        backgroundHandlerThread.join()
    }

    /**
     * Capture State Callback
     */

    private val captureStateCallback = object : CameraCaptureSession.StateCallback() {
        override fun onConfigureFailed(session: CameraCaptureSession) {

        }

        override fun onConfigured(session: CameraCaptureSession) {
            cameraCaptureSession = session

            cameraCaptureSession.setRepeatingRequest(
                captureRequestBuilder.build(),
                null,
                backgroundHandler
            )
        }
    }

    private val captureStateVideoCallback = object : CameraCaptureSession.StateCallback() {
        override fun onConfigureFailed(session: CameraCaptureSession) {
            Log.e(TAG, "Configuration failed")
            customHandler.addToLog("Configuration failed")
        }

        override fun onConfigured(session: CameraCaptureSession) {
            customHandler.addToLog("start build")
            cameraCaptureSession = session
            try {
                cameraCaptureSession.setRepeatingRequest(
                    captureRequestBuilder.build(), null,
                    backgroundHandler
                )
                mediaRecorder.start()
                customHandler.addToLog("finish build")
            } catch (e: CameraAccessException) {
                e.printStackTrace()
                Log.e(TAG, "Failed to start camera preview because it couldn't access the camera")
                customHandler.addToLog("Failed to start camera preview because it couldn't access the camera")
            } catch (e: IllegalStateException) {
                e.printStackTrace()
                customHandler.addToLog(e.stackTrace.toString())
            }
        }
    }


    private fun createFile(): File {
        val file = File(
            Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS
            ),
            "/Matrix"
        )
        file.mkdirs()
        return file
    }

}