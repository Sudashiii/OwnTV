package dev.owntv.launcher.update

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.IntentCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val latest: UpdateInfo) : UpdateState
    data class Available(val update: UpdateInfo, val message: String? = null) : UpdateState

    /** OwnTV may not install apps yet; the user allows it once in Android's settings. */
    data class NeedsPermission(val update: UpdateInfo) : UpdateState
    data class Downloading(val update: UpdateInfo, val bytes: Long, val total: Long) : UpdateState {
        val fraction: Float? get() = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else null
    }

    /** Handed to Android's package installer, which may ask the user to confirm. */
    data class Installing(val update: UpdateInfo) : UpdateState

    /** [update] is the version that did not install; [retry] is the version in the list whose details could not be loaded. */
    data class Failed(
        val message: String,
        val update: UpdateInfo? = null,
        val detail: String? = null,
        val retry: ReleaseEntry? = null,
    ) : UpdateState
}

/** The version list in Settings > Updates, from GitHub's releases. */
sealed interface ReleasesState {
    data object Idle : ReleasesState
    data object Loading : ReleasesState
    data class Loaded(val releases: List<ReleaseEntry>) : ReleasesState
    data class Failed(val message: String) : ReleasesState
}

/**
 * Updates OwnTV from its GitHub releases: reads the latest release's `update.json`, downloads the
 * APK it points at, checks its SHA-256 and signing key, and installs it with [PackageInstaller].
 * Android installs it only over an app signed with the same key, so a debug build cannot take a release.
 */
class Updater(
    private val context: Context,
    private val scope: CoroutineScope,
    private val updateUrl: String,
    private val releasesUrl: String,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val folder = File(context.cacheDir, "updates")
    private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val packageInfo: PackageInfo = context.packageManager.getPackageInfo(context.packageName, 0)

    val installedVersionName: String = packageInfo.versionName ?: "?"
    val installedVersionCode: Long = packageInfo.longVersionCode

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val _releases = MutableStateFlow<ReleasesState>(ReleasesState.Idle)
    val releases: StateFlow<ReleasesState> = _releases.asStateFlow()

    private var job: Job? = null
    private var releasesJob: Job? = null
    private var lastCheck = 0L

    @Volatile private var sessionId: Int? = null

    /** This process is the first run of a version the updater installed. */
    private var justUpdated = false
    private var updateNotice: String? = null

    init {
        reportFinishedInstall()
    }

    /** Checks for an update unless one was found or ruled out in the last hour. Settings calls this when it opens. */
    fun checkIfStale() {
        when (state.value) {
            is UpdateState.Available, is UpdateState.UpToDate -> {
                if (System.currentTimeMillis() - lastCheck < STALE_MS) {
                    if (releases.value !is ReleasesState.Loaded) loadReleases()
                    return
                }
            }
            UpdateState.Idle, is UpdateState.Failed -> Unit
            else -> return
        }
        check()
    }

    /** Looks for a newer release and refreshes the version list. */
    fun check() {
        loadReleases()
        launchExclusive {
            _state.value = UpdateState.Checking
            _state.value = try {
                val info = fetchInfo()
                lastCheck = System.currentTimeMillis()
                if (info.isNewerThan(installedVersionCode)) UpdateState.Available(info) else UpdateState.UpToDate(info)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                UpdateState.Failed(describe(e))
            }
        }
    }

    /** Loads the list of all releases. A list that is already there stays on screen while it refreshes or when that fails. */
    fun loadReleases() {
        if (releasesUrl.isEmpty() || releasesJob?.isActive == true) return
        releasesJob = scope.launch {
            if (_releases.value !is ReleasesState.Loaded) _releases.value = ReleasesState.Loading
            try {
                val text = fetchText(releasesUrl, MAX_RELEASES_BYTES, "The release list")
                // Up to 100 releases with their notes: parsing them took about 40 ms, too long for the main thread.
                val list = withContext(Dispatchers.Default) { ReleaseEntry.parseGitHub(text) }
                    .getOrElse { error("The release list is not valid: ${it.message}") }
                _releases.value = ReleasesState.Loaded(list)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (_releases.value !is ReleasesState.Loaded) _releases.value = ReleasesState.Failed(describe(e))
            }
        }
    }

    /**
     * Installs a newer version from the list: loads its `update.json` (APK link and checksum), then goes on as [install] does.
     * Older versions are ignored; Android does not install them over a newer one.
     */
    fun install(entry: ReleaseEntry) {
        if (entry.versionCode > installedVersionCode) installFromList(entry)
    }

    private fun installFromList(entry: ReleaseEntry) = launchExclusive {
        val info = try {
            fetchInfo(entry.updateJsonUrl).also {
                if (it.versionCode != entry.versionCode) error("update.json is for OwnTV ${it.versionName}, not ${entry.versionName}.")
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            _state.value = UpdateState.Failed(describe(e), retry = entry)
            return@launchExclusive
        }
        installNow(info, skipPermissionCheck = false)
    }

    /**
     * Downloads [update] (or reuses an earlier download), checks it and hands it to Android's installer.
     * [skipPermissionCheck] is for TVs without the "install unknown apps" screen; the installer asks itself then.
     */
    fun install(update: UpdateInfo, skipPermissionCheck: Boolean = false) = launchExclusive {
        installNow(update, skipPermissionCheck)
    }

    private suspend fun installNow(update: UpdateInfo, skipPermissionCheck: Boolean) {
        if (!skipPermissionCheck && !context.packageManager.canRequestPackageInstalls()) {
            _state.value = UpdateState.NeedsPermission(update)
            return
        }
        try {
            val apk = download(update)
            withContext(Dispatchers.IO) { verify(apk, update) }
            _state.value = UpdateState.Installing(update)
            withContext(Dispatchers.IO) { commit(apk, update) }
        } catch (e: Exception) {
            // A cancelled download ends in an IOException from OkHttp, so look at the job, not the exception.
            _state.value = if (currentCoroutineContext().isActive) UpdateState.Failed(describe(e), update) else UpdateState.Available(update)
            if (e is CancellationException) throw e
        }
    }

    fun cancel() {
        job?.cancel()
    }

    /** Opens Android's "install unknown apps" switch for OwnTV. */
    fun openInstallPermission(update: UpdateInfo) {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            install(update, skipPermissionCheck = true)
        }
    }

    /** The Updates screen calls this when it comes back to the front, for example from the permission screen. */
    fun onResume() {
        val current = state.value
        if (current is UpdateState.NeedsPermission && context.packageManager.canRequestPackageInstalls()) install(current.update)
    }

    /** Results of [PackageInstaller.Session.commit], forwarded by [UpdateInstallReceiver]. */
    fun onInstallStatus(intent: Intent) {
        val id = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        if (id != sessionId) return
        val update = (state.value as? UpdateState.Installing)?.update
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
            try {
                context.startActivity(confirm!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                sessionFinished()
                _state.value = UpdateState.Failed("Android could not show the install confirmation.", update, e.message)
            }
            return
        }
        sessionFinished()
        _state.value = when (status) {
            // Usually the process is replaced before this arrives; reportFinishedInstall() says so on the next start.
            PackageInstaller.STATUS_SUCCESS -> update?.let { UpdateState.UpToDate(it) } ?: UpdateState.Idle
            PackageInstaller.STATUS_FAILURE_ABORTED -> update?.let { UpdateState.Available(it, "The installation was canceled.") } ?: UpdateState.Idle
            else -> UpdateState.Failed(installError(status), update, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
        }
    }

    /**
     * Replacing OwnTV ends its process, and Android then shows the last app instead of Home.
     * After an update installed from here, open Home again (the default Home app may start activities).
     */
    fun onPackageReplaced() {
        if (!justUpdated) return
        justUpdated = false
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(home) }
    }

    /** "Updated to OwnTV x" once after an update; shown when OwnTV is in front, since Android drops toasts from the background. */
    fun takeUpdateNotice(): String? = updateNotice.also { updateNotice = null }

    private fun launchExclusive(block: suspend CoroutineScope.() -> Unit) {
        if (job?.isActive == true) return
        val previous = job
        job = scope.launch {
            previous?.join()
            block()
        }
    }

    private suspend fun fetchInfo(url: String = updateUrl): UpdateInfo =
        UpdateInfo.parse(fetchText(url, MAX_INFO_BYTES, "update.json")).getOrElse { error("update.json is not valid: ${it.message}") }

    private suspend fun fetchText(url: String, maxBytes: Int, what: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("Cache-Control", "no-cache").build()
        client.newCall(request).execute().use { response ->
            if (response.code == 404) error("No release was found at $url.")
            if (!response.isSuccessful) error("The server answered HTTP ${response.code}.")
            val out = ByteArrayOutputStream()
            response.body.byteStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    if (out.size() > maxBytes) error("$what is too large.")
                }
            }
            out.toString(Charsets.UTF_8.name())
        }
    }

    private suspend fun download(update: UpdateInfo): File = withContext(Dispatchers.IO) {
        folder.mkdirs()
        val target = File(folder, "owntv-${update.versionCode}.apk")
        if (target.isFile && sha256(target) == update.sha256) return@withContext target
        folder.listFiles()?.forEach { it.delete() }

        val part = File(folder, "${target.name}.part")
        _state.value = UpdateState.Downloading(update, 0, -1)
        val call = client.newCall(Request.Builder().url(update.apk).build())
        coroutineContext.job.invokeOnCompletion { call.cancel() }
        try {
            val hash = call.execute().use { response ->
                if (!response.isSuccessful) error("The download failed with HTTP ${response.code}.")
                val total = response.body.contentLength()
                val digest = MessageDigest.getInstance("SHA-256")
                var bytes = 0L
                var reported = 0L
                response.body.byteStream().use { input ->
                    part.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            bytes += read
                            if (bytes > MAX_APK_BYTES) error("The download is much larger than an OwnTV APK.")
                            if (bytes - reported >= PROGRESS_STEP_BYTES) {
                                reported = bytes
                                _state.value = UpdateState.Downloading(update, bytes, total)
                            }
                        }
                    }
                }
                digest.digest().toHex()
            }
            if (hash != update.sha256) error("The download is damaged (its checksum does not match). Try again.")
            if (!part.renameTo(target)) throw IOException("Could not save the download.")
            target
        } catch (e: Exception) {
            part.delete()
            throw e
        }
    }

    /** Catches APKs Android would refuse anyway, with a message that says why. */
    private fun verify(apk: File, update: UpdateInfo) {
        val pm = context.packageManager
        val archive = archiveInfo(pm, apk) ?: error("The download is not a valid APK.")
        if (archive.packageName != context.packageName) error("The download is not an OwnTV update.")
        if (archive.longVersionCode != update.versionCode) error("The download is not OwnTV ${update.versionName}.")
        if (archive.longVersionCode <= installedVersionCode) error("The download is not newer than the installed version.")
        val ours = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signers()
        val theirs = archive.signers()
        if (ours.isNotEmpty() && theirs.isNotEmpty() && ours.none { it in theirs }) {
            error(
                "This OwnTV is signed with a different key than the release, for example because it is a debug build. " +
                    "Install the release APK by hand once (after uninstalling this one); later updates then install from here.",
            )
        }
    }

    private fun archiveInfo(pm: PackageManager, apk: File): PackageInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageArchiveInfo(apk.path, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES)
        }

    private fun PackageInfo.signers(): Set<Signature> {
        val info = signingInfo ?: return emptySet()
        return if (info.hasMultipleSigners()) info.apkContentsSigners.toSet() else info.signingCertificateHistory.toSet()
    }

    private fun commit(apk: File, update: UpdateInfo) {
        val installer = context.packageManager.packageInstaller
        sessionId?.let { runCatching { installer.abandonSession(it) } }
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            setInstallReason(PackageManager.INSTALL_REASON_USER)
            // With UPDATE_PACKAGES_WITHOUT_USER_ACTION, Android 12+ updates OwnTV without asking again.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setPackageSource(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)
        }
        val id = installer.createSession(params)
        sessionId = id
        try {
            installer.openSession(id).use { session ->
                session.openWrite("owntv.apk", 0, apk.length()).use { output ->
                    apk.inputStream().use { it.copyTo(output) }
                    session.fsync(output)
                }
                prefs.edit().putLong(PENDING_CODE, update.versionCode).putString(PENDING_NAME, update.versionName).apply()
                val status = Intent(context, UpdateInstallReceiver::class.java).setPackage(context.packageName)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                session.commit(PendingIntent.getBroadcast(context, id, status, flags).intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(id) }
            sessionFinished()
            throw e
        }
    }

    private fun sessionFinished() {
        sessionId = null
        prefs.edit().remove(PENDING_CODE).remove(PENDING_NAME).apply()
    }

    /** After an update Android starts a new process; note that it worked and drop the download. */
    private fun reportFinishedInstall() {
        val pending = prefs.getLong(PENDING_CODE, -1)
        if (pending < 0) return
        val name = prefs.getString(PENDING_NAME, null)
        prefs.edit().remove(PENDING_CODE).remove(PENDING_NAME).apply()
        if (installedVersionCode >= pending) {
            justUpdated = true
            updateNotice = "Updated to OwnTV ${name ?: installedVersionName}"
            folder.deleteRecursively()
        }
    }

    private fun installError(status: Int): String = when (status) {
        PackageInstaller.STATUS_FAILURE_BLOCKED -> "Android blocked the installation."
        PackageInstaller.STATUS_FAILURE_CONFLICT -> "The update conflicts with the installed OwnTV, for example because it is signed with a different key."
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "The update does not work on this TV."
        PackageInstaller.STATUS_FAILURE_INVALID -> "Android says the downloaded APK is invalid."
        PackageInstaller.STATUS_FAILURE_STORAGE -> "There is not enough free storage to install the update."
        else -> "The installation failed."
    }

    private fun describe(e: Exception): String = when (e) {
        is UnknownHostException, is ConnectException -> "Check the TV's internet connection."
        is SocketTimeoutException -> "The server took too long to answer."
        else -> e.message ?: e.javaClass.simpleName
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private companion object {
        const val STALE_MS = 60 * 60 * 1000L
        const val MAX_INFO_BYTES = 64 * 1024
        const val MAX_RELEASES_BYTES = 2 * 1024 * 1024
        const val MAX_APK_BYTES = 200L * 1024 * 1024
        const val PROGRESS_STEP_BYTES = 256 * 1024
        const val PENDING_CODE = "pending_version_code"
        const val PENDING_NAME = "pending_version_name"
    }
}
