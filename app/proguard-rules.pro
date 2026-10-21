# R8 rules for the release build. Libraries (kotlinx.serialization, Compose, Coil, Ktor, OkHttp)
# ship their own consumer rules; this file only adds what they leave out.

# Ktor's debugger detection uses JVM management APIs that Android does not have; it is never reached.
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean

# Performance metrics name each screen after its class (Screen.Settings is "Settings").
-keepnames class dev.owntv.launcher.ui.Screen$*
