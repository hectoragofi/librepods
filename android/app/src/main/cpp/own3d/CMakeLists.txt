# OWN 3D spatial renderer (stereo -> 7 virtual speakers -> binaural), loaded by the app (parameter
# table) and by the Shizuku shell user service (rendering).
#
# own3d.c, own3d.h, own3d_jni.c and pffft/ are copied verbatim from the standalone test app
# (shizuku-test/app/src/main/cpp). To sync a new engine, copy those files over these; nothing else
# here needs to change. The JNI bridge binds to the Java class local.librepods.spatialshizuku.Own3D
# (app/src/main/java), which is why that class keeps the test app's package name.
add_library(own3d SHARED own3d.c own3d_jni.c pffft/pffft.c)
target_link_libraries(own3d log m)
target_compile_options(own3d PRIVATE -Wall -Wno-unused-parameter -Wno-sign-compare)
# The DSP must be optimised even in debug builds (last -O wins).
set_source_files_properties(own3d.c pffft/pffft.c PROPERTIES COMPILE_OPTIONS "-O3;-ffp-contract=fast")
