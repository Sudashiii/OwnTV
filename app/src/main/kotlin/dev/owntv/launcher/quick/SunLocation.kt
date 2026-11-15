package dev.owntv.launcher.quick

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.TimeZone

/** Where night light's sunset and sunrise are worked out for, and how OwnTV knows. */
data class SunPlace(val point: GeoPoint, val source: Source, val label: String) {
    enum class Source { Device, TimeZone }
}

/**
 * The TV's place for sunset to sunrise: Android's coarse location when OwnTV may read it (Google TV can locate
 * itself by Wi-Fi), else the main city of the TV's time zone (`assets/night/zones.tsv`, from tzdata).
 * The last device fix is kept, since TVs rarely have a fresh one.
 */
class SunLocation(private val context: Context, private val scope: CoroutineScope) {
    private val prefs = context.getSharedPreferences("quick_settings", Context.MODE_PRIVATE)
    private val current = MutableStateFlow<SunPlace?>(null)
    private var zones: Map<String, SunPlace>? = null

    val place: StateFlow<SunPlace?> = current.asStateFlow()

    /** Shows the system's permission dialog; set by the activity. Calls [onPermissionResult] when answered. */
    var permissionLauncher: (() -> Unit)? = null

    init {
        refresh()
    }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** Asks for the location permission, or looks again when OwnTV already has it. */
    fun requestAccess() {
        val launcher = permissionLauncher
        if (hasPermission() || launcher == null) refresh() else launcher()
    }

    fun onPermissionResult(@Suppress("UNUSED_PARAMETER") granted: Boolean) = refresh()

    fun refresh() {
        scope.launch {
            current.value = withContext(Dispatchers.IO) { cachedDevicePlace() ?: zonePlace() }
            if (hasPermission()) requestFreshFix()
        }
    }

    private fun cachedDevicePlace(): SunPlace? {
        if (!hasPermission()) return null
        lastKnown()?.let(::store)
        if (!prefs.contains(KEY_LAT)) return null
        val point = GeoPoint(prefs.getFloat(KEY_LAT, 0f).toDouble(), prefs.getFloat(KEY_LON, 0f).toDouble())
        return SunPlace(point, SunPlace.Source.Device, "This TV's location")
    }

    @Suppress("MissingPermission")
    private fun lastKnown(): Location? {
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        return runCatching { manager.getProviders(true) }.getOrDefault(emptyList())
            .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
    }

    @Suppress("MissingPermission")
    private fun requestFreshFix() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val manager = context.getSystemService(LocationManager::class.java) ?: return
        val providers = runCatching { manager.getProviders(true) }.getOrDefault(emptyList())
        val provider = listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER).firstOrNull { it in providers } ?: return
        runCatching {
            manager.getCurrentLocation(provider, null, ContextCompat.getMainExecutor(context)) { location ->
                if (location != null) {
                    store(location)
                    current.value = SunPlace(GeoPoint(location.latitude, location.longitude), SunPlace.Source.Device, "This TV's location")
                }
            }
        }
    }

    private fun store(location: Location) {
        prefs.edit().putFloat(KEY_LAT, location.latitude.toFloat()).putFloat(KEY_LON, location.longitude.toFloat()).apply()
    }

    private fun zonePlace(): SunPlace? {
        val table = zones ?: runCatching {
            context.assets.open("night/zones.tsv").bufferedReader().use { parseZones(it.readText()) }
        }.getOrDefault(emptyMap()).also { zones = it }
        val id = TimeZone.getDefault().id
        val canonical = runCatching { android.icu.util.TimeZone.getCanonicalID(id) }.getOrNull()
        return table[id] ?: canonical?.let { table[it] }
    }

    companion object {
        const val PERMISSION = Manifest.permission.ACCESS_COARSE_LOCATION
        private const val KEY_LAT = "night_location_lat"
        private const val KEY_LON = "night_location_lon"

        /** Lines of `zone<TAB>lat<TAB>lon<TAB>place`, see `scripts/zone_coords.py`. */
        fun parseZones(text: String): Map<String, SunPlace> = text.lineSequence().mapNotNull { line ->
            val f = line.split('\t')
            if (f.size < 4) return@mapNotNull null
            val lat = f[1].toDoubleOrNull() ?: return@mapNotNull null
            val lon = f[2].toDoubleOrNull() ?: return@mapNotNull null
            f[0] to SunPlace(GeoPoint(lat, lon), SunPlace.Source.TimeZone, f[3])
        }.toMap()
    }
}
