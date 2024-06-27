package com.alex.obstaclealert.ui.home

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeech.OnInitListener
import android.speech.tts.Voice
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
import com.alex.obstaclealert.ml.Model1
import com.alex.obstaclealert.ui.information.GalleryFragment
import com.alex.obstaclealert.ui.utils.ExtractDocument
import com.alex.obstaclealert.ui.utils.RequestHttpLogs
import com.alex.obstaclealert.ui.utils.RequestHttpUser
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.nnapi.NnApiDelegate
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ExecutorService


class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 1001
        private val REQUIRED_PERMISSIONS =
            arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    }

    private lateinit var previewView: ImageView
    lateinit var textureView: TextureView
    private lateinit var overlayView: OverlayView

    private lateinit var buttonCapture: Button
    lateinit var cameraDevice: CameraDevice
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var cameraManager: CameraManager
    lateinit var bitmap: Bitmap

    lateinit var handler: Handler

    private var associatedAxisLabels: List<String>? = null

    private var nnApiDelegate: NnApiDelegate? = null

    private val requiredPermissions =
        arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)

    private lateinit var model: Model1

    private var tts: TextToSpeech? = null
    private val binding get() = _binding!!

    private var statusCaptureView = false

    private var lastDetectedObject: DetectedObject? = null
    private val detectionCooldown = 5000 // Tiempo en milisegundos (2 segundos)
    private val detectedObjects = mutableListOf<DetectedObject>()
    private val lastDetectionTimes = mutableMapOf<String, Long>()

    private var extractDocument = ExtractDocument()
    private var requestHttpLogs = RequestHttpLogs()
    private var userId: String = ""
    private val metadaLogs = mutableListOf<String>()
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        ViewModelProvider(this)[HomeViewModel::class.java]

        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        val root: View = binding.root

        model = Model1.newInstance(requireContext())
        previewView = root.findViewById(R.id.imageView)
        textureView = root.findViewById(R.id.textureView)

        overlayView = root.findViewById(R.id.overlay)
        buttonCapture = root.findViewById(R.id.button_capture)
        statusCaptureView = false

        ActivityCompat.requestPermissions(
            requireActivity(),
            requiredPermissions,
            REQUEST_CODE_PERMISSIONS
        )
        getUserId(requireContext(), "user_meta_data.json")

        val handlerThread = HandlerThread("videoThread")
        handlerThread.start()
        handler = Handler(handlerThread.looper)

        buttonCapture.setOnClickListener {
            startCamera()
            initializeInterpreter()
            statusCaptureView = true
            convertTextToSpeech("La aplicación iniciara en: 3. 2. 1.")
        }

        tts?.let { setLanguageAndVoice(it) }
        tts = TextToSpeech(
            requireContext(),
            OnInitListener { status ->
                if (status == TextToSpeech.SUCCESS) {
                    // TTS engine is successfully initialized.
                    convertTextToSpeech("Bienvenido, toque la pantalla para iniciar.")
                }
            })

        cameraManager = requireContext().getSystemService(Context.CAMERA_SERVICE) as CameraManager

        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(p0: SurfaceTexture, p1: Int, p2: Int) {
                startCamera()
                initializeInterpreter()
            }

            override fun onSurfaceTextureSizeChanged(p0: SurfaceTexture, p1: Int, p2: Int) {
            }

            override fun onSurfaceTextureDestroyed(p0: SurfaceTexture): Boolean {
                //Log.d(tag, "Aqui se cierra la camara")
                setMetadataField(requireContext())
                return false
            }

            override fun onSurfaceTextureUpdated(p0: SurfaceTexture) {
                if (statusCaptureView) {
                    bitmap = textureView.bitmap!!
                    processImage(bitmap, previewView)
                }

            }
        }

        return root
    }

    private fun setLanguageAndVoice(tts: TextToSpeech) {
        val desiredLocale = Locale("es", "EC")// Change to the desired language/locale
        tts.setLanguage(desiredLocale)

        val voices = tts.voices
        val voiceList: List<Voice> = ArrayList(voices)
        val selectedVoice = voiceList[19] // Change to the desired voice index
        tts.setVoice(selectedVoice)
    }

    private fun convertTextToSpeech(text: String) {
        if (tts != null) {
            tts!!.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
        }
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

    private fun processImage(bitmap: Bitmap, imageView: ImageView) {
        val results = runModel(bitmap, imageView)
        overlayView.setResults(results)
    }

    private fun runModel(bitmap: Bitmap, imageView: ImageView): List<DetectionResult> {
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

        val outputs = model.process(processedTensorImage)
        val locations = outputs.locationsAsTensorBuffer.floatArray
        val classes = outputs.classesAsTensorBuffer.floatArray
        val scores = outputs.scoresAsTensorBuffer.floatArray

        val mutable = bitmap.copy(Bitmap.Config.ARGB_8888, true)

        inferenceTime = SystemClock.uptimeMillis() - inferenceTime
        // Obtener las dimensiones de la vista de la cámara
        Log.d(tag, "Timesnap results: $inferenceTime")
        val results = parseResults(
            locations,
            classes,
            scores,
            mutable.width,
            mutable.height,
            inferenceTime
        )

        imageView.setImageBitmap(mutable)
        return (results)
    }

    private fun parseResults(
        locations: FloatArray,
        detectionClasses: FloatArray,
        detectionScores: FloatArray,
        previewWidth: Int,
        previewHeight: Int,
        inferenceTime: Long
    ): List<DetectionResult> {
        val detectionResults = mutableListOf<DetectionResult>()
        var box = 0

        for (i in detectionScores.indices) {
            val score = detectionScores[i]
            // Solo procesar detecciones con puntaje significativo (> 0)
            if (score > 0.75) {
                box = i
                box *= 4
                val top = locations[box] * previewHeight
                val left = locations[box + 1] * previewWidth
                val bottom = locations[box + 2] * previewHeight
                val right = locations[box + 3] * previewWidth

                // Calcular la distancia
                val distancePredict = getDistancePredict(
                    top, left, bottom, right,
                    previewHeight.toFloat(), previewWidth.toFloat()
                )

                val location = RectF(left, top, right, bottom)
                val category = associatedAxisLabels?.get(detectionClasses[i].toInt()) ?: "Unknown"

                val detectionResult =
                    DetectionResult(location, category, score, inferenceTime, distancePredict)
                detectionResults.add(detectionResult)
                val detectionObj = DetectedObject(category, distancePredict, inferenceTime)
                detectedObjects.add(detectionObj)
                checkAndHandleDetection(category, distancePredict, inferenceTime)
            }
        }
        //Log.d(TAG, "Inference results: ${detectionResults.toString()}")
        return detectionResults
    }

    private fun checkAndHandleDetection(category: String, distance: Float, timestamp: Long) {
        val currentObject = DetectedObject(category, distance, timestamp)

        if (lastDetectedObject == null || lastDetectedObject != currentObject) {
            lastDetectedObject = currentObject
            handleDetection(category, distance, timestamp)
        }
    }

    private fun handleDetection(category: String, distance: Float, timestamp: Long) {
        val currentTime = System.currentTimeMillis()

        // Verificar si ha pasado el tiempo de cooldown para la categoría detectada
        val lastDetectionTime = lastDetectionTimes[category] ?: 0
        if (currentTime - lastDetectionTime < detectionCooldown) {
            return // No ha pasado suficiente tiempo, no hacer nada
        }

        val recentDetections = detectedObjects.filter {
            currentTime - it.timestamp < detectionCooldown
        }

        val alreadyDetected = recentDetections.any {
            it.category == category && it.distance == distance
        }

        if (!alreadyDetected) {
            detectedObjects.add(DetectedObject(category, distance, currentTime))
            convertTextToSpeech("$category a ${"%.2f".format(distance)} metros")
            // Crea el metadata
            metadaLogs.add(setMetadataLogs(category, distance, timestamp))
            // Actualizar el tiempo de la última detección para la categoría
            lastDetectionTimes[category] = currentTime

            // Limpiar detecciones antiguas
            detectedObjects.removeAll {
                currentTime - it.timestamp >= detectionCooldown
            }
        }
    }

    private fun setMetadataLogs(category: String, distance: Float, currentTime: Long): String {
        return "${category}|${"%.2f".format(distance)}m|${currentTime}ms"
    }

    private fun getDistancePredict(
        top: Float, left: Float, bottom: Float, right: Float,
        previewHeight: Float, previewWidth: Float
    ): Float {
        // Calcular las coordenadas del cuadro delimitador
        val boxHeight = (bottom - top) * previewHeight

        // Parámetros de la cámara (ajusta según tu dispositivo)
        val realObjectHeight =
            1.7f // Altura real del objeto en metros (por ejemplo, una persona de 1.7m)
        val focalLength =
            800f // Longitud focal de la cámara en píxeles (ajusta según tu dispositivo)

        // Usar la altura del cuadro delimitador para calcular la distancia
        val distance = (realObjectHeight * focalLength) / boxHeight
        return distance * 1000
    }

    data class DetectedObject(
        val category: String, val distance: Float, val timestamp: Long
    )

    data class DetectionResult(
        val location: RectF,
        val category: String,
        val score: Float,
        val elapsedTime: Long,
        val distancePredict: Float
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
        if (tts != null) {
            tts!!.stop();
            tts!!.shutdown();
            statusCaptureView = false;
        }
        model.close()
    }

    private fun setMetadataField(context: Context) {
        val jsonObject = JSONObject()
        jsonObject.put("logs", metadaLogs.toString().replace("[", "").replace("]", ""))

        extractDocument.saveJsonToFile(context, "metadata_logs.json", jsonObject)
    }

    @SuppressLint("MissingPermission")
    fun startCamera() {
        cameraManager.openCamera(
            cameraManager.cameraIdList[0],
            object : CameraDevice.StateCallback() {
                override fun onOpened(p0: CameraDevice) {
                    cameraDevice = p0

                    var surfaceTexture = textureView.surfaceTexture
                    var surface = Surface(surfaceTexture)

                    var captureRequest =
                        cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                    captureRequest.addTarget(surface)

                    cameraDevice.createCaptureSession(
                        listOf(surface),
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(p0: CameraCaptureSession) {
                                p0.setRepeatingRequest(captureRequest.build(), null, null)
                            }

                            override fun onConfigureFailed(p0: CameraCaptureSession) {
                            }
                        },
                        handler
                    )
                }

                override fun onDisconnected(p0: CameraDevice) {

                }

                override fun onError(p0: CameraDevice, p1: Int) {

                }
            },
            handler
        )
    }

    // Función para mostrar Snackbar
    private fun showSnackbar(message: String) {
        view?.let { Snackbar.make(it, message, Snackbar.LENGTH_LONG).show() }
    }

    private fun getUserId(context: Context, fileName: String) {
        if (!extractDocument.isJsonFileEmpty(context, fileName)) {
            val jsonObject = extractDocument.getJsonFileContent(context, fileName)
            jsonObject?.let {
                userId = it.getString("user")
            }

            var metadaField = ""
            if (!extractDocument.isJsonFileEmpty(context, "metadata_logs.json")) {
                val jsonObjectLogs =
                    extractDocument.getJsonFileContent(context, "metadata_logs.json")
                jsonObjectLogs?.let {
                    metadaField = it.getString("logs")
                }

                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val logRequest = mutableListOf<RequestHttpLogs.LogsRequest>()
                        logRequest.add(
                            RequestHttpLogs.LogsRequest(
                                userId,
                                extractDocument.getCurrentDateTime(),
                                extractDocument.getCurrentDateTime(),
                                "complete",
                                "SSD MobileNetV1",
                                metadaField
                            )
                        )
                        val response = RequestHttpLogs.RetrofitInstance.api.postData(logRequest)
                        if (response.isSuccessful) {
                            val data = response.body()
                            if (data != null) {
                                showSnackbar("Consulta exitosa: ${data.message}")
                            } else {
                                showSnackbar("Consulta exitosa: ${response.code()}")
                            }
                        }
                    } catch (e: Exception) {
                        userId = ""
                        showSnackbar("Consulta exitosa: ${e.message}")
                    }
                }
            }
        } else {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val userRequest = RequestHttpUser.UserRequest(
                        "active",
                        extractDocument.getInstallationId(requireContext())
                    )
                    val response = RequestHttpUser.RetrofitInstance.api.postData(userRequest)
                    if (response.isSuccessful) {
                        val data = response.body()
                        // Guardar datos en un archivo JSON en el dispositivo
                        val jsonObject = JSONObject()
                        if (data != null) {
                            jsonObject.put("user", data.user)
                            jsonObject.put("message", data.message)
                            jsonObject.put("status", userRequest.status)
                            jsonObject.put("name", userRequest.name)
                            userId = data.user
                        } else {
                            jsonObject.put("user", "")
                            jsonObject.put("message", "")
                            jsonObject.put("status", "")
                            jsonObject.put("name", "")
                        }
                        extractDocument.saveJsonToFile(context, fileName, jsonObject)
                    }
                } catch (e: Exception) {
                    userId = ""
                    showSnackbar("Consulta exitosa: ${e.message}")
                }
            }
        }
    }

    private fun closeInterpreter() {
        nnApiDelegate?.close()
    }
}