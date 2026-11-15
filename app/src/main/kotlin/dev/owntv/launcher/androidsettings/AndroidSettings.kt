package dev.owntv.launcher.androidsettings

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.text.format.DateFormat
import android.view.Display
import dev.owntv.launcher.adb.SelfAdb
import dev.owntv.launcher.ui.help
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.util.TimeZone

/** What Settings > Android shows. Read straight from Android; nothing here needs adb. */
data class AndroidState(
    val wifiOn: Boolean,
    /** "Home-5G", "Wi-Fi" when the name is hidden, "Ethernet", or null without a connection. */
    val connection: String?,
    /** [connection] when it is the Wi-Fi network, else null. */
    val wifiConnection: String?,
    val ip: String?,
    val bluetoothOn: Boolean,
    /** Names of the paired devices; null while OwnTV may not see them. */
    val pairedDevices: List<String>?,
    val screenOffMillis: Int,
    val systemSounds: Boolean,
    val surround: SurroundMode,
    /** "3840 × 2160 · 60 Hz". */
    val resolution: String?,
    val autoTime: Boolean,
    val is24Hour: Boolean,
    val timeZoneId: String,
    val storageFree: Long,
    val storageTotal: Long,
    val model: String,
    val androidVersion: String,
    val securityPatch: String?,
    val developerOptions: Boolean,
    val usbDebugging: Boolean,
    /** OwnTV may write the system, secure and global tables itself (WRITE_SECURE_SETTINGS). */
    val canWriteSecure: Boolean,
)

/**
 * Settings > Android: the TV's own settings shown and changed inside OwnTV. Reading needs no permission.
 * Writing goes straight to Android once OwnTV was allowed to ([grantAccess], one adb command each); until
 * then, and for the switches that need a shell (Wi-Fi, Bluetooth, time zone), it runs the one command
 * through [adb], the TV's own debugging that Settings > Developer > ADB on this TV sets up.
 */
class AndroidSettings(private val context: Context, private val adb: SelfAdb) {
    private val packageName = context.packageName
    private val resolver = context.contentResolver

    private val current = MutableStateFlow(read())
    val state: StateFlow<AndroidState> = current.asStateFlow()

    fun refresh() {
        current.value = read()
    }

    /** Lets OwnTV change the TV's settings without adb from now on. Null when done, else what went wrong. */
    suspend fun grantAccess(): String? = shell(AndroidCommands.grantSecureSettings(packageName)).also { refresh() }

    /** Lets OwnTV see which app is open. Null when done, else what went wrong. */
    suspend fun grantUsageAccess(): String? = shell(AndroidCommands.allowUsageAccess(packageName)).also { refresh() }

    /** Lets OwnTV list the paired Bluetooth devices. Null when done, else what went wrong. */
    suspend fun grantBluetooth(): String? = shell(AndroidCommands.grantBluetooth(packageName)).also { refresh() }

    /** Writes one setting: directly when OwnTV may, else through adb. Null when done, else what went wrong. */
    suspend fun put(table: SettingsTable, key: String, value: String): String? {
        val direct = runCatching {
            // The secure-settings permission also covers the system table.
            when {
                !canWriteSecure() -> null
                table == SettingsTable.System -> Settings.System.putString(resolver, key, value)
                table == SettingsTable.Secure -> Settings.Secure.putString(resolver, key, value)
                else -> Settings.Global.putString(resolver, key, value)
            }
        }.getOrNull()
        if (direct == true) {
            refresh()
            return null
        }
        return shell(AndroidCommands.put(table, key, value)).also { refresh() }
    }

    /** Runs a command of [AndroidCommands] on this TV. Null when it worked, else a sentence for the user. */
    suspend fun shell(command: String): String? {
        val result = adb.run(command)
        val error = result.exceptionOrNull()
        val shellResult = result.getOrNull()
        return when {
            error is SelfAdb.AdbException -> error.problem.help()
            error != null -> "Could not reach the TV's debugging. Try again."
            shellResult?.exitCode != null && shellResult.exitCode != 0 -> shellResult.output.lineSequence().firstOrNull { it.isNotBlank() } ?: "The TV said no."
            else -> null
        }
    }

    suspend fun setWifi(on: Boolean) = shell(AndroidCommands.wifi(on)).also { refresh() }

    suspend fun setBluetooth(on: Boolean) = shell(AndroidCommands.bluetooth(on)).also { refresh() }

    suspend fun setTimeZone(id: String) = shell(AndroidCommands.timeZone(id)).also { refresh() }

    private fun canWriteSecure() = context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    private fun read(): AndroidState {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val network = connectivity?.activeNetwork
        val capabilities = network?.let { connectivity.getNetworkCapabilities(it) }
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        val onWifi = capabilities != null && !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) &&
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        val connection = when {
            capabilities == null -> null
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            onWifi -> wifiName(wifi) ?: "Wi-Fi"
            else -> "Connected"
        }
        val ip = network?.let { connectivity.getLinkProperties(it) }?.linkAddresses
            ?.map { it.address }?.filterIsInstance<Inet4Address>()?.firstOrNull()?.hostAddress

        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        val bluetoothOn = runCatching { adapter?.isEnabled == true }.getOrDefault(false)
        val paired = if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            runCatching { adapter?.bondedDevices.orEmpty().map { it.name ?: it.address }.sorted() }.getOrNull()
        } else {
            null
        }

        val stat = runCatching { StatFs(Environment.getDataDirectory().path) }.getOrNull()
        return AndroidState(
            wifiOn = runCatching { wifi?.isWifiEnabled == true }.getOrDefault(false),
            connection = connection,
            wifiConnection = connection.takeIf { onWifi },
            ip = ip,
            bluetoothOn = bluetoothOn,
            pairedDevices = paired,
            screenOffMillis = Settings.System.getInt(resolver, Settings.System.SCREEN_OFF_TIMEOUT, 60_000),
            systemSounds = Settings.System.getInt(resolver, Settings.System.SOUND_EFFECTS_ENABLED, 1) != 0,
            surround = SurroundMode.of(Settings.Global.getInt(resolver, "encoded_surround_output", 0)),
            resolution = resolution(),
            autoTime = Settings.Global.getInt(resolver, Settings.Global.AUTO_TIME, 1) != 0,
            is24Hour = DateFormat.is24HourFormat(context),
            timeZoneId = TimeZone.getDefault().id,
            storageFree = stat?.availableBytes ?: 0,
            storageTotal = stat?.totalBytes ?: 0,
            model = listOf(Build.MANUFACTURER, Build.MODEL).filter { it.isNotBlank() }.distinct().joinToString(" ").ifBlank { "Unknown" },
            androidVersion = Build.VERSION.RELEASE,
            securityPatch = Build.VERSION.SECURITY_PATCH.takeIf { it.isNotBlank() },
            developerOptions = Settings.Global.getInt(resolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) != 0,
            usbDebugging = Settings.Global.getInt(resolver, Settings.Global.ADB_ENABLED, 0) != 0,
            canWriteSecure = canWriteSecure(),
        )
    }

    /** The SSID, which Android hides without location permission; null then. */
    @Suppress("DEPRECATION")
    private fun wifiName(wifi: WifiManager?): String? =
        runCatching { wifi?.connectionInfo?.ssid }.getOrNull()
            ?.trim('"')?.takeIf { it.isNotBlank() && it != WifiManager.UNKNOWN_SSID }

    @Suppress("DEPRECATION")
    private fun resolution(): String? = runCatching {
        val display = context.getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val mode = display.mode
        "${mode.physicalWidth} × ${mode.physicalHeight} · ${Math.round(mode.refreshRate)} Hz"
    }.getOrNull()
}
