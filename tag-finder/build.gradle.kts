// Plugins are declared per module (core: Kotlin/JVM; app: Android + Kotlin) so that
// `-PcoreOnly` builds never need Google's Maven repository, and the Kotlin Android plugin
// shares a classloader with the Android Gradle Plugin.
