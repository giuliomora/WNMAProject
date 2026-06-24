package com.example.trekmesh

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object WeatherRelay {
    private const val LOG_TAG = "WeatherRelay"
    // URL di esempio: in un'app reale si userebbe OpenWeatherMap o simili
    private const val WEATHER_API_URL = "https://httpbin.org/get?weather=clear&temp=18"

    suspend fun fetchWeather(): String? = withContext(Dispatchers.IO) {
        try {
            val url = URL(WEATHER_API_URL)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 5000
            
            val response = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            
            val json = JSONObject(response)
            val args = json.getJSONObject("args")
            val status = args.getString("weather")
            val temp = args.getString("temp")
            
            val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            "Weather Bulletin [$time]: $status, Temp: $temp°C"
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Errore recupero meteo", e)
            null
        }
    }
}
