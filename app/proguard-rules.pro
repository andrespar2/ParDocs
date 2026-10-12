# PDFBox-Android is reflection-light but ships optional providers; keep the
# public API surface it exposes. Minification is disabled for the MVP release,
# these rules are here for when it is enabled.
-keep class com.tom_roush.pdfbox.** { *; }
-dontwarn com.tom_roush.pdfbox.**
-dontwarn org.bouncycastle.**
-dontwarn javax.imageio.**
-dontwarn java.awt.**
