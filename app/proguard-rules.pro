# Proguard rules for Sift
-keepattributes *Annotation*
-keepclassmembers class * {
    @androidx.room.* <methods>;
}
