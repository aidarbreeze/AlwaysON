# Add project specific ProGuard rules here.

# Keep SharedPreferences wrapper
-keep class com.aidarbreeze.alwayson.Prefs { *; }

# Keep data classes used for API responses
-keep class com.aidarbreeze.alwayson.weather.WeatherHour { *; }
-keep class com.aidarbreeze.alwayson.weather.WeatherDay { *; }
-keep class com.aidarbreeze.alwayson.weather.WeatherInfo { *; }
-keep class com.aidarbreeze.alwayson.weather.GeoPlace { *; }
-keep class com.aidarbreeze.alwayson.stock.Candle { *; }

# Keep Android framework classes used in reflection
-keep class android.content.SharedPreferences { *; }
-keep class androidx.security.crypto.EncryptedSharedPreferences { *; }
-keep class androidx.security.crypto.MasterKey { *; }

# Keep service and receiver classes referenced in manifest
-keep class com.aidarbreeze.alwayson.service.OverlayService { *; }
-keep class com.aidarbreeze.alwayson.recv.ChargingReceiver { *; }
-keep class com.aidarbreeze.alwayson.media.NowPlayingListenerService { *; }
-keep class com.aidarbreeze.alwayson.dream.ClockDreamService { *; }
-keep class com.aidarbreeze.alwayson.widget.AlwaysOnWidget { *; }

# Keep view classes used in layouts
-keep class com.aidarbreeze.alwayson.view.** { *; }

# Keep UI state object
-keep class com.aidarbreeze.alwayson.ui.StandbyUiState { *; }

# Keep controller
-keep class com.aidarbreeze.alwayson.StandbyController { *; }

# Optimization: remove unused resources and code
-optimizations !code/simplification/cyclic,!field/*simplify/assign
-allowaccessmodification
-dontpreverify
