# FileZen release rules
-keepattributes *Annotation*
-keep class com.filezen.files.data.db.** { *; }
-keepclassmembers class com.filezen.files.data.db.** { *; }
# Room
-dontwarn org.apache.commons.logging.**
-dontwarn org.codehaus.mojo.animal_sniffer.*
# Billing
-keep class com.android.billingclient.** { *; }
# Play services ads
-keep class com.google.android.gms.ads.** { *; }
-dontwarn com.google.android.gms.ads.**
