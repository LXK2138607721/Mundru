# Room
-keepclassmembers class * extends androidx.room.RoomDatabase {
    <init>();
}
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# Compose
-keepclassmembers class * {
    @androidx.compose.runtime.Composable *;
}

# Coil GIF 解码组件保留
-keep class coil.decode.ImageDecoderDecoder* { *; }
-keep class coil.decode.GifDecoder* { *; }
-keep class coil.drawable.MovieDrawable* { *; }
