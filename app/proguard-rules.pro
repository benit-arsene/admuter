# AdMuter ProGuard Rules

# Keep service and receiver classes (they are invoked by the Android system)
-keep class com.admuter.MuterService { *; }
-keep class com.admuter.SpotifyReceiver { *; }

# Keep the inner ActionReceiver class
-keep class com.admuter.MuterService$ActionReceiver { *; }

# Keep the NotificationListenerService classes (instantiated by the system via manifest)
-keep class com.admuter.SpotifyNotificationListener { *; }
-keep class com.admuter.AdNotificationListener { *; }
