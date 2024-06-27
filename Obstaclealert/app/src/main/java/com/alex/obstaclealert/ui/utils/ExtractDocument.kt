package com.alex.obstaclealert.ui.utils

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ExtractDocument {
    fun getJsonFileContent(context: Context, fileName: String): JSONObject? {
        val file = File(context.filesDir, fileName)
        if (file.exists() && file.readText().isNotBlank()) {
            val content = file.readText()
            return JSONObject(content)
        }
        return null
    }

    fun saveJsonToFile(context: Context, fileName: String, jsonObject: JSONObject) {
        try {
            val file = File(context.filesDir, fileName)
            file.writeText(jsonObject.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun isJsonFileEmpty(context: Context, fileName: String): Boolean {
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

    fun getCurrentDateTime(): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val date = Date()
        return dateFormat.format(date)
    }

    @SuppressLint("HardwareIds")
    fun getInstallationId(context: Context): String {
        return Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
    }

    fun createDataUserInit(context: Context) {
        // Guardar datos en un archivo JSON en el dispositivo
        val jsonObject = JSONObject()
        jsonObject.put("user", "")
        jsonObject.put("message", "")
        jsonObject.put("status", "No existe")
        jsonObject.put("name", getInstallationId(context))

        saveJsonToFile(context, "user_meta_data.json", jsonObject)
    }
}