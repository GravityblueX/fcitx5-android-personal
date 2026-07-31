# ML Kit discovers these registrars from manifest metadata and constructs them through
# reflection. R8 cannot infer that use, so Release builds must retain their public
# no-argument constructors.
-keep class * implements com.google.firebase.components.ComponentRegistrar {
    public <init>();
}

# ML Kit Digital Ink currently brings a WorkManager/Room version whose generated
# implementation is loaded by class name.
-keep class androidx.work.impl.WorkDatabase_Impl { *; }
