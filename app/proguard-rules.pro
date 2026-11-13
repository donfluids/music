# The app itself uses no reflection; default optimize rules are sufficient for it.

# LiteRT's native library is bound to its Java classes by their names (JNI); keep them whole.
-keep class org.tensorflow.lite.** { *; }
