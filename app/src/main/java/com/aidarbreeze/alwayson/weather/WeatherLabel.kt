package com.aidarbreeze.alwayson.weather

import java.util.Locale

/** Tiny WMO weather-code -> short label map (en / ru). */
object WeatherLabel {
    fun of(code: Int): String {
        val ru = Locale.getDefault().language.equals("ru", ignoreCase = true)
        return when (code) {
            0 -> if (ru) "ясно" else "clear"
            1 -> if (ru) "почти ясно" else "mostly clear"
            2 -> if (ru) "переменная облачность" else "partly cloudy"
            3 -> if (ru) "пасмурно" else "overcast"
            45, 48 -> if (ru) "туман" else "fog"
            51, 53, 55 -> if (ru) "морось" else "drizzle"
            56, 57, 66, 67 -> if (ru) "ледяной дождь" else "freezing rain"
            61, 63, 65, 80, 81, 82 -> if (ru) "дождь" else "rain"
            71, 73, 75, 77, 85, 86 -> if (ru) "снег" else "snow"
            95, 96, 99 -> if (ru) "гроза" else "thunderstorm"
            else -> ""
        }
    }
}
