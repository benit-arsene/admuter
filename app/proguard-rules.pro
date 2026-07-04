# AdMuter ProGuard Rules

# Keep service and receiver classes (they are invoked by the Android system)
-keep class com.admuter.MuterService { *; }
-keep class com.admuter.SpotifyReceiver { *; }

# Keep the inner ActionReceiver class
-keep class com.admuter.MuterService$ActionReceiver { *; }
