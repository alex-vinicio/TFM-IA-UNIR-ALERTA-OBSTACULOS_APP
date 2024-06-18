package com.alex.obstaclealert.ui.home

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.RectF
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.LayoutInflater
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.alex.obstaclealert.R
import com.alex.obstaclealert.databinding.FragmentHomeBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.nnapi.NnApiDelegate
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors


class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null

    private var player: MediaPlayer? = null

    companion object {
        private const val TAG = "CameraXDemo"
        private const val REQUEST_CODE_PERMISSIONS = 1001
        private val REQUIRED_PERMISSIONS =
            arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    }

    private lateinit var previewView: PreviewView
    private lateinit var overlayView: OverlayView

    private lateinit var buttonCapture: Button
    private lateinit var videoCapture: VideoCapture<Recorder>
    private lateinit var interpreter: Interpreter
    private lateinit var cameraExecutor: ExecutorService

    private var associatedAxisLabels: List<String>? = null

    var nnApiDelegate: NnApiDelegate? = null

    private val requiredPermissions =
        arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        ViewModelProvider(this)[HomeViewModel::class.java]

        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        val root: View = binding.root

        previewView = root.findViewById(R.id.previewView)
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

        cameraExecutor = Executors.newSingleThreadExecutor()
        buttonCapture.setOnClickListener {
            startCamera()
            initializeInterpreter()
        }


        return root
    }

    private fun initializePlayer() {
        if (player == null) {
            player = MediaPlayer.create(requireContext(), R.raw.initial_audio)
        }
        player?.start()
    }

    private fun initializeInterpreter() {
        //val modelBuffer = loadModelFile("2.tflite")
        val tfliteModel = FileUtil.loadMappedFile(requireContext(), "1.tflite")

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

        try {
            interpreter = Interpreter(tfliteModel, options)
        }catch (e: Exception){
            throw RuntimeException(e)
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

    private fun processImage(imageProxy: ImageProxy) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val bitmap = imageProxy.toBitmap()
                    val results = runModel(bitmap)
                    overlayView.setResults(results)
                } finally {
                    imageProxy.close()
                }
            }
        }
    }

    private fun convertInputTensor(bitmap: Bitmap): ByteBuffer {
        val inputTensor = ByteBuffer.allocateDirect(300 * 300 * 3).order(ByteOrder.nativeOrder())

        // Resize the bitmap to 300x300
        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, 300, 300, true)

        // Convert the bitmap to ByteBuffer
        val intValues = IntArray(300 * 300)
        resizedBitmap.getPixels(intValues, 0, 300, 0, 0, 300, 300)
        var pixel = 0
        for (i in 0 until 300) {
            for (j in 0 until 300) {
                val value = intValues[pixel++]
                inputTensor.put((value shr 16 and 0xFF).toByte()) // Red
                inputTensor.put((value shr 8 and 0xFF).toByte())  // Green
                inputTensor.put((value and 0xFF).toByte())       // Blue
            }
        }

        return inputTensor;
    }

    private fun runModel(bitmap: Bitmap): List<DetectionResult> {
        // Inference time is the difference between the system time at the start and finish of the
        // process
        var inferenceTime = SystemClock.uptimeMillis()

        // Preprocesar la imagen utilizando TensorImage e ImageProcessor
        val tensorImage = TensorImage(DataType.UINT8)
        val imageProcessor = ImageProcessor.Builder()
            .add(ResizeOp(300, 300, ResizeOp.ResizeMethod.BILINEAR))  // Adjust dimensions as needed
            .build()
        tensorImage.load(bitmap)
        val processedTensorImage = imageProcessor.process(tensorImage)
        val inputBuffer = processedTensorImage.buffer

        // Crear el buffer de salida
        val maxDetections = 1001

        // Tamaños de los buffers, ajusta según sea necesario
        val numDetectionsSize = 4 * 1 // 1 detección, 4 bytes por float
        val detectionBoxesSize = 4 * maxDetections * 4   // 10 detecciones, 4 coordenadas por caja, 4 bytes por float
        val detectionClassesSize = 4 * maxDetections  // 10 detecciones, 4 bytes por float
        val detectionScoresSize = 4 * maxDetections  // 10 detecciones, 4 bytes por float

        // Crear los buffers para las salidas
        val numDetectionsBuffer = ByteBuffer.allocateDirect(numDetectionsSize).order(ByteOrder.nativeOrder())
        val detectionBoxesBuffer = ByteBuffer.allocateDirect(detectionBoxesSize).order(ByteOrder.nativeOrder())
        val detectionClassesBuffer = ByteBuffer.allocateDirect(detectionClassesSize).order(ByteOrder.nativeOrder())
        val detectionScoresBuffer = ByteBuffer.allocateDirect(detectionScoresSize).order(ByteOrder.nativeOrder())

        val outputMap: MutableMap<Int, Any> = HashMap()
        outputMap[0] = detectionBoxesBuffer
        outputMap[1] = detectionClassesBuffer
        outputMap[2] = detectionScoresBuffer
        outputMap[3] = numDetectionsBuffer

        // Ejecutar el modelo
        interpreter.runForMultipleInputsOutputs(arrayOf(inputBuffer), outputMap)

        val numDetectionsArray = FloatArray(1)
        numDetectionsBuffer.rewind()
        numDetectionsBuffer.asFloatBuffer().get(numDetectionsArray)
        val numDetections = numDetectionsArray[0].toInt()

        val detectionBoxesArray = Array(numDetections) { FloatArray(4) }
        detectionBoxesBuffer.rewind()
        val flattenedArray = FloatArray(numDetections * 4)
        detectionBoxesBuffer.asFloatBuffer().get(flattenedArray)
        for (i in 0 until numDetections) {
            System.arraycopy(flattenedArray, i * 4, detectionBoxesArray[i], 0, 4)
        }

        val detectionClassesArray = FloatArray(numDetections)
        detectionClassesBuffer.rewind()
        detectionClassesBuffer.asFloatBuffer().get(detectionClassesArray)

        val detectionScoresArray = FloatArray(numDetections)
        detectionScoresBuffer.rewind()
        detectionScoresBuffer.asFloatBuffer().get(detectionScoresArray)

        // Crear el mapa de etiquetas si las etiquetas se cargaron correctamente
        val labelMap = associatedAxisLabels?.mapIndexed { index, label -> index to label }?.toMap() ?: mapOf()

        // Obtener las dimensiones de la vista de la cámara
        val previewWidth = previewView.width.toFloat()
        val previewHeight = previewView.height.toFloat()

        val results = parseResults(
            detectionBoxesArray,
            detectionClassesArray,
            detectionScoresArray,
            labelMap,
            previewWidth,
            previewHeight
        )
        inferenceTime = SystemClock.uptimeMillis() - inferenceTime

        //Log.d(TAG, "Timesnap results: $inferenceTime")
        return (results)
    }

    private fun parseResults(
        detectionBoxes: Array<FloatArray>,
        detectionClasses: FloatArray,
        detectionScores: FloatArray,
        labelMap: Map<Int, String>,
        previewWidth: Float,
        previewHeight: Float
    ): List<DetectionResult> {
        val detectionResults = mutableListOf<DetectionResult>()

        for (i in detectionBoxes.indices) {
            val score = detectionScores[i]

            // Solo procesar detecciones con puntaje significativo (> 0)
            if (score > 0.60) {
                val box = detectionBoxes[i]
                val classIndex = detectionClasses[i].toInt()

                if (box.size == 4) {
                    val top = box[0] * previewHeight
                    val left = box[1] * previewWidth
                    val bottom = box[2] * previewHeight
                    val right = box[3] * previewWidth

                    val location = RectF(left, top, right, bottom)
                    val category = labelMap[classIndex] ?: "Unknown"

                    val detectionResult = DetectionResult(location, category, score)
                    detectionResults.add(detectionResult)
                }
            }
        }
        Log.d(TAG, "Inference results: ${detectionResults.toString()}")
        return detectionResults
    }


    data class DetectionResult(
        val location: RectF,
        val category: String,
        val score: Float
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

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

            val recorder = Recorder.Builder()
                .setExecutor(cameraExecutor)
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            var imageAnalyzer = ImageAnalysis.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                //.setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        processImage(imageProxy)
                    }
                }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    public fun closeInterpreter(){
        interpreter.close()
        nnApiDelegate?.close()
    }
}