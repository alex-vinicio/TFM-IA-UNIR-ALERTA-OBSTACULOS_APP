package com.alex.obstaclealert.ui.home

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.LayoutInflater
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.alex.obstaclealert.R
import com.alex.obstaclealert.databinding.FragmentHomeBinding
import com.alex.obstaclealert.ml.Yolov5sFp16
import com.alex.obstaclealert.ui.utils.Recognition
import com.alex.obstaclealert.ui.utils.Yolov5TFLiteDetector
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.nnapi.NnApiDelegate
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.io.IOException
import java.util.concurrent.ExecutorService


class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null

    private var player: MediaPlayer? = null

    companion object {
        private const val TAG = "CameraXDemo"
        private const val REQUEST_CODE_PERMISSIONS = 1001
        private val REQUIRED_PERMISSIONS =
            arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    }

    private lateinit var previewView: ImageView
    lateinit var textureView: TextureView
    private lateinit var overlayView: OverlayView

    private lateinit var buttonCapture: Button
    lateinit var cameraDevice: CameraDevice
    private lateinit var interpreter: Interpreter
    private lateinit var cameraExecutor: ExecutorService
    lateinit var cameraManager: CameraManager
    lateinit var bitmap:Bitmap

    lateinit var handler: Handler

    private var associatedAxisLabels: List<String>? = null

    var nnApiDelegate: NnApiDelegate? = null

    private val requiredPermissions =
        arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)

    lateinit var model:Yolov5sFp16
    private val binding get() = _binding!!

    var yolov5TFLiteDetector: Yolov5TFLiteDetector? = null
    var boxPaint: Paint = Paint()
    var textPain: Paint = Paint()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        ViewModelProvider(this)[HomeViewModel::class.java]

        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        val root: View = binding.root

        model = Yolov5sFp16.newInstance(requireContext())
        previewView = root.findViewById(R.id.imageView)
        textureView = root.findViewById(R.id.textureView)

        overlayView = root.findViewById(R.id.overlay)
        buttonCapture = root.findViewById(R.id.button_capture)

        if (allPermissionsGranted()) {
            initializePlayer()
        } else {
            ActivityCompat.requestPermissions(
                requireActivity(),
                requiredPermissions,
                REQUEST_CODE_PERMISSIONS
            )
        }

        val handlerThread = HandlerThread("videoThread")
        handlerThread.start()
        handler = Handler(handlerThread.looper)

        buttonCapture.setOnClickListener {
           // startCamera()
        }

        yolov5TFLiteDetector = Yolov5TFLiteDetector()
        yolov5TFLiteDetector!!.modelFile = "yolov5n-fp16.tflite"
        yolov5TFLiteDetector!!.initialModel(requireContext())
        boxPaint.strokeWidth = 5f
        boxPaint.style = Paint.Style.STROKE
        boxPaint.color = Color.RED

        textPain.textSize = 50f
        textPain.color = Color.GREEN
        textPain.style = Paint.Style.FILL

        textureView.surfaceTextureListener = object:TextureView.SurfaceTextureListener{
            override fun onSurfaceTextureAvailable(p0: SurfaceTexture, p1: Int, p2: Int) {
                startCamera()
                //initializeInterpreter()
            }
            override fun onSurfaceTextureSizeChanged(p0: SurfaceTexture, p1: Int, p2: Int) {
            }

            override fun onSurfaceTextureDestroyed(p0: SurfaceTexture): Boolean {
                return false
            }

            override fun onSurfaceTextureUpdated(p0: SurfaceTexture) {
                bitmap = textureView.bitmap!!
                //processImage(bitmap, previewView)
                processImage2(bitmap, previewView)
            }
        }

        cameraManager = requireContext().getSystemService(Context.CAMERA_SERVICE) as CameraManager

        return root
    }

    private fun initializePlayer() {
        if (player == null) {
            player = MediaPlayer.create(requireContext(), R.raw.initial_audio)
        }
        player?.start()
    }

    private fun initializeInterpreter() {

        try {
            associatedAxisLabels = FileUtil.loadLabels(requireContext(), "labels.txt")
        } catch (e: IOException) {
            Log.e("tfliteSupport", "Error reading label file", e)
        }

        val options = Interpreter.Options()

        // Initialize interpreter with NNAPI delegate for Android Pie or above
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            nnApiDelegate = NnApiDelegate()
            options.addDelegate(nnApiDelegate)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        closeInterpreter()
        _binding = null
    }

    private fun allPermissionsGranted() = requiredPermissions.all {
        ContextCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED
    }

    private fun processImage(bitmap: Bitmap, imageView:ImageView ) {
        val results = runModel(bitmap, imageView)
        overlayView.setResults(results)
    }

    private fun processImage2(bitmap: Bitmap, imageView:ImageView ) {
        val recognitions: ArrayList<Recognition> = yolov5TFLiteDetector!!.detect(bitmap)
        val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(mutableBitmap)

        for (recognition in recognitions) {
            if (recognition.getConfidence() > 0.4) {
                val location: RectF = recognition.getLocation()
                canvas.drawRect(location, boxPaint)
                canvas.drawText(
                    recognition.getLabelName() + ":" + recognition.getConfidence(),
                    location.left,
                    location.top,
                    textPain
                )
            }
        }

        imageView.setImageBitmap(mutableBitmap)
        //overlayView.setResults(results)
    }
    private fun runModel(bitmap: Bitmap, imageView: ImageView): List<DetectionResult> {
        // Inference time is the difference between the system time at the start and finish of the
        // process
        var inferenceTime = SystemClock.uptimeMillis()

        // Preprocesar la imagen utilizando TensorImage e ImageProcessor
        val tensorImage = TensorImage.fromBitmap(bitmap)

        val imageProcessor = ImageProcessor.Builder()
            .add(ResizeOp(320, 320, ResizeOp.ResizeMethod.BILINEAR))
            .add(NormalizeOp(0.0f, 255.0f))  // Normaliza los valores de píxeles al rango [0, 1]
            .build()
        val processedTensorImage = imageProcessor.process(tensorImage)

        val tensorBuffer = TensorBuffer.createFixedSize(intArrayOf(1, 320, 320, 3), DataType.FLOAT32)
        tensorBuffer.loadBuffer(processedTensorImage.buffer)

        val outputs = model.process(processedTensorImage.tensorBuffer)
        val floatArrayBuffer = outputs.outputFeature0AsTensorBuffer.floatArray
        // Número de detecciones
        val outputSize = intArrayOf(1, 6300, 85);
        val numDetections = floatArrayBuffer.size / 85

        val mutable = bitmap.copy(Bitmap.Config.ARGB_8888, true)

        inferenceTime = SystemClock.uptimeMillis() - inferenceTime
        // Obtener las dimensiones de la vista de la cámara
        val previewWidth = mutable.width
        val previewHeight = mutable.height

        val results = parseResults(
            floatArrayBuffer,
            previewWidth,
            previewHeight,
            inferenceTime,
            outputSize
        )
        //Log.d(TAG, "Timesnap results: $inferenceTime")
        imageView.setImageBitmap(mutable)
        return (results)
    }

    private fun parseResults(
        floatArrayBuffer: FloatArray,
        previewWidth: Int,
        previewHeight: Int,
        inferenceTime: Long,
        outputSize: IntArray
    ): List<DetectionResult> {
        val detectionResults = mutableListOf<DetectionResult>()
        var box = 0
        for (i in 0 until outputSize[1]) {
            val startIndex = i * 85
            val endIndex = startIndex + 85

            // Acceder a los datos de la detección actual
            val detectionData = floatArrayBuffer.sliceArray(startIndex until endIndex)
            // Aquí puedes interpretar los datos según la estructura de salida del modelo YOLOv5

            val confidence = detectionData[4]

            if (confidence > 0.80) {
                    val x = detectionData[0]* previewHeight
                    val y = detectionData[1] *previewWidth
                    val w = detectionData[2] * previewHeight
                    val h = detectionData[3] * previewWidth

                    val xmin = Math.max(0f, x - w / 2.0f)
                    val ymin = Math.max(0f, y - h / 2.0f)
                    val xmax = Math.min(previewWidth.toFloat(), x + w / 2.0f).toInt()
                    val ymax = Math.min(previewHeight.toFloat(), y + h / 2.0f).toInt()

                    val location = RectF(xmin, ymin, xmax.toFloat(), ymax.toFloat())
                    val classIndex = detectionData[5].toInt()  // Índice de la clase detectada
                    val className = associatedAxisLabels?.get(classIndex) ?: "Unknown"

                    val detectionResult = DetectionResult(location, className, confidence, inferenceTime)
                    detectionResults.add(detectionResult)
            }
        }
        //Log.d(TAG, "Inference results: ${detectionResults.toString()}")
        return detectionResults
    }

    data class DetectionResult(
        val location: RectF,
        val category: String,
        val score: Float,
        val elapsedTime: Long
    )

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(
                    requireContext(),
                    "Permissions not granted by the user.",
                    Toast.LENGTH_SHORT
                ).show()
                requireActivity().finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        closeInterpreter()
        cameraExecutor.shutdown()
    }

    @SuppressLint("MissingPermission")
    fun startCamera(){
        cameraManager.openCamera(cameraManager.cameraIdList[0], object: CameraDevice.StateCallback(){
            override fun onOpened(p0: CameraDevice) {
                cameraDevice = p0

                var surfaceTexture = textureView.surfaceTexture
                var surface = Surface(surfaceTexture)

                var captureRequest = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                captureRequest.addTarget(surface)

                cameraDevice.createCaptureSession(listOf(surface), object: CameraCaptureSession.StateCallback(){
                    override fun onConfigured(p0: CameraCaptureSession) {
                        p0.setRepeatingRequest(captureRequest.build(), null, null)
                    }
                    override fun onConfigureFailed(p0: CameraCaptureSession) {
                    }
                }, handler)
            }

            override fun onDisconnected(p0: CameraDevice) {

            }

            override fun onError(p0: CameraDevice, p1: Int) {

            }
        }, handler)
    }

    private fun closeInterpreter(){
        nnApiDelegate?.close()
    }
}