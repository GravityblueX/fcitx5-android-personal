# disable obfuscation
-dontobfuscate

# preserve the line number information for debugging stack traces
-keepattributes SourceFile,LineNumberTable

# ML Kit Digital Ink 19.0.0 brings WorkManager 2.7.0 and Room 2.2.5.
# Room loads this generated database implementation by name, but that old
# dependency does not provide an R8 rule that keeps it.
-keep class androidx.work.impl.WorkDatabase_Impl { *; }

# ML Kit discovers its component registrars from manifest metadata and creates
# them reflectively. Keep their no-argument constructors for Release builds.
-keep class * implements com.google.firebase.components.ComponentRegistrar {
    public <init>();
}

# remove kotlin null checks
-processkotlinnullchecks remove
