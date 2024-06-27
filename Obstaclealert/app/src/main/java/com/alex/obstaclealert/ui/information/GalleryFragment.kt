package com.alex.obstaclealert.ui.information

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
import com.alex.obstaclealert.ui.utils.ExtractDocument
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

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    private var textView: TextView? = null

    private var extractDocument = ExtractDocument()

    @SuppressLint("SetTextI18n")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentGalleryBinding.inflate(inflater, container, false)
        val root: View = binding.root

        textView = binding.textGallery

        checkJsonAndFetchData(
            requireContext(),
            "user_meta_data.json",
            UserRequest("active", extractDocument.getInstallationId(requireContext()))
        )
        return root
    }

    @SuppressLint("SetTextI18n")
    private fun checkJsonAndFetchData(context: Context, fileName: String, datauser: UserRequest) {
        CoroutineScope(Dispatchers.IO).launch {
            if (extractDocument.isJsonFileEmpty(context, fileName)) {
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
                        } else {
                            jsonObject.put("user", "")
                            jsonObject.put("message", "")
                            jsonObject.put("status", "")
                            jsonObject.put("name", "")
                        }
                        extractDocument.saveJsonToFile(context, fileName, jsonObject)
                        if (data != null) {
                            textView!!.text = "Usuario:\n ${data.user} \n" +
                                    "Mensaje:\n ${data.message} \n" +
                                    "Detalle:\n ${datauser.name} \n" +
                                    "Estado:\n ${datauser.status}"
                        }
                    }
                } catch (_: Exception) {
                }
            } else {
                // El archivo JSON no está vacío, recuperar sus valores

                val jsonObject = extractDocument.getJsonFileContent(context, fileName)
                jsonObject?.let {
                    val user = it.getString("user")
                    val message = it.getString("message")
                    val status = it.getString("status")
                    val name = it.getString("name")

                    if (user == "") {
                        extractDocument.createDataUserInit(context)
                    }

                    textView!!.text = "Usuario:\n ${user} \n" +
                            "Mensaje:\n ${message} \n" +
                            "Detalle:\n ${name} \n" +
                            "Estado:\n ${status}"
                }
            }
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}