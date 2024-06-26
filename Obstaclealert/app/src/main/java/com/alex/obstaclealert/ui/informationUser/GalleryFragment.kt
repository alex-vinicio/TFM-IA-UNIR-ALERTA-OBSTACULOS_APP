package com.alex.obstaclealert.ui.informationUser

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.alex.obstaclealert.databinding.FragmentGalleryBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import java.io.File

class GalleryFragment : Fragment() {

    private var _binding: FragmentGalleryBinding? = null

    private val userApp: User? = null
    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    private var textView: TextView? = null
    @SuppressLint("SetTextI18n")
    override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
    ): View {

        _binding = FragmentGalleryBinding.inflate(inflater, container, false)
        val root: View = binding.root

        textView = binding.textGallery

        checkJsonAndFetchData(requireContext(), "user_meta_data.json", UserRequest("active", getInstallationId(requireContext())))
        return root
    }

    private fun isJsonFileEmpty(context: Context, fileName: String): Boolean {
        val file = File(context.filesDir, fileName)
        if (!file.exists()) {
            return true // El archivo no existe, se considera vacío
        }

        val content = file.readText()
        if (content.isBlank()) {
            return true // El archivo está vacío
        }

        // Intentar convertir el contenido a JSONObject
        return try {
            val jsonObject = JSONObject(content)
            jsonObject.length() == 0
        } catch (e: Exception) {
            true // Si hay una excepción, consideramos que el archivo es inválido o vacío
        }
    }

    @SuppressLint("SetTextI18n")
    private fun checkJsonAndFetchData(context: Context, fileName: String, datauser:UserRequest) {
        CoroutineScope(Dispatchers.IO).launch {
            if (isJsonFileEmpty(context, fileName)) {
                // El archivo JSON está vacío, enviar la petición al microservicio
                try {
                    val response = RetrofitInstance.api.postData(datauser)
                    if (response.isSuccessful) {
                        val data = response.body()
                        // Guardar datos en un archivo JSON en el dispositivo
                        val jsonObject = JSONObject()
                        if (data != null) {
                            jsonObject.put("user", data.user)
                            jsonObject.put("message", data.message)
                            jsonObject.put("status", datauser.status)
                            jsonObject.put("name", datauser.name)
                        }else{
                            jsonObject.put("user", "")
                            jsonObject.put("message", "")
                            jsonObject.put("status", "")
                            jsonObject.put("name", "")
                        }
                        saveJsonToFile(context, "data.json", jsonObject)
                        if (data != null) {
                            textView!!.text = "Usuario:\n ${data.user} \n" +
                                    "Mensaje:\n ${data.message} \n" +
                                    "Detalle:\n ${datauser.name} \n" +
                                    "Estado:\n ${datauser.status}"
                        }
                    } else {
                        // Manejar errores de respuesta
                        createDataUserInit(context)
                    }
                } catch (e: Exception) {
                    // Manejar errores de la petición
                    createDataUserInit(context)
                }
            } else {
                // El archivo JSON no está vacío, recuperar sus valores
                val jsonObject = getJsonFileContent(context, fileName)
                jsonObject?.let {
                    val user = it.getString("user")
                    val message = it.getString("message")
                    val status = it.getString("status")
                    val name = it.getString("name")

                    if(user == ""){
                        createDataUserInit(context)
                    }

                    textView!!.text = "Usuario:\n ${user} \n" +
                            "Mensaje:\n ${message} \n" +
                            "Detalle:\n ${name} \n" +
                            "Estado:\n ${status}"
                }
            }
        }
    }

    @SuppressLint("HardwareIds")
    fun getInstallationId(context: Context): String {
        return Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
    }
    private fun getJsonFileContent(context: Context, fileName: String): JSONObject? {
        val file = File(context.filesDir, fileName)
        if (file.exists() && file.readText().isNotBlank()) {
            val content = file.readText()
            return JSONObject(content)
        }
        return null
    }

    private fun createDataUserInit(context: Context){
        // Guardar datos en un archivo JSON en el dispositivo
        val jsonObject = JSONObject()
        jsonObject.put("user", "")
        jsonObject.put("message", "")
        jsonObject.put("status", "No existe")
        jsonObject.put("name", getInstallationId(requireContext()))



        saveJsonToFile(context, "data.json", jsonObject)
    }
    private fun saveJsonToFile(context: Context, fileName: String, jsonObject: JSONObject) {
        try {
            val file = File(context.filesDir, fileName)
            file.writeText(jsonObject.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    interface ApiService {
        @POST("api/service/v1/user")
        suspend fun postData(@Body data: UserRequest): Response<UserResponse>
    }
    object RetrofitInstance {
        val api: ApiService by lazy {
            Retrofit.Builder()
                .baseUrl("https://i8o724ju5g.execute-api.us-east-2.amazonaws.com/")
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(ApiService::class.java)
        }
    }

    data class UserResponse(
        val user: String,
        val message: String
    )
    data class UserRequest(
        val status: String,
        val name: String
    )
    data class User(
        val status: String,
        val name: String,
        val ip: String,
        val createdAt: String,
        val updateAt: String,
        val idUser: String,
        val description: String,
        val deviceIp: String
    )

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}