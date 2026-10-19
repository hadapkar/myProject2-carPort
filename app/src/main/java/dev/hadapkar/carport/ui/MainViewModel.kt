package dev.hadapkar.carport.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.hadapkar.carport.data.AppEntry
import dev.hadapkar.carport.data.InstalledApps
import dev.hadapkar.carport.data.KeyRepository
import dev.hadapkar.carport.data.SettingsStore
import dev.hadapkar.carport.data.SignerState
import dev.hadapkar.carport.install.InstallEvent
import dev.hadapkar.carport.install.InstallEvents
import dev.hadapkar.carport.install.Installer
import dev.hadapkar.carport.patcher.ApkPatcher
import dev.hadapkar.carport.patcher.PatchException
import dev.hadapkar.carport.patcher.PatchOptions
import dev.hadapkar.carport.patcher.PatchResult
import dev.hadapkar.carport.update.Release
import dev.hadapkar.carport.update.UpdateChecker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface JobState {
    data object Idle : JobState

    /**
     * @param cancellable waiting on Android, which may never answer (for example when its
     *   confirmation screen was left with Home), so the user can give up.
     * @param canReopenPrompt Android's confirmation screen can be shown again.
     */
    data class Working(
        val title: String,
        val step: String,
        val cancellable: Boolean = false,
        val canReopenPrompt: Boolean = false,
    ) : JobState

    /** The installed copy has another signature; Android must remove it (and its data) first. */
    data class ConfirmUninstall(val label: String, val result: PatchResult) : JobState

    data class Finished(
        val success: Boolean,
        val title: String,
        val message: String,
        /** The patched APKs are kept and can be installed again. */
        val canRetry: Boolean = false,
    ) : JobState
}

data class SetupStatus(
    val canInstallApps: Boolean = false,
    val androidAutoInstalled: Boolean = false,
)

data class KeyInfo(val sha256: String, val backedUp: Boolean)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: Release) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/** A screen the visible activity must start (Android blocks starts from the background). */
data class UserAction(val intent: Intent, val isConfirmation: Boolean)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val context get() = getApplication<Application>()
    private val keys = KeyRepository(application)
    private val settings = SettingsStore(application)

    private val _apps = MutableStateFlow<List<AppEntry>?>(null)
    /** null while loading. */
    val apps: StateFlow<List<AppEntry>?> = _apps.asStateFlow()

    private val _job = MutableStateFlow<JobState>(JobState.Idle)
    val job: StateFlow<JobState> = _job.asStateFlow()

    private val _userAction = MutableStateFlow<UserAction?>(null)
    val userAction: StateFlow<UserAction?> = _userAction.asStateFlow()

    private val _setup = MutableStateFlow(SetupStatus())
    val setup: StateFlow<SetupStatus> = _setup.asStateFlow()

    private val _keyInfo = MutableStateFlow<KeyInfo?>(null)
    val keyInfo: StateFlow<KeyInfo?> = _keyInfo.asStateFlow()

    private val _options = MutableStateFlow(settings.options)
    val options: StateFlow<PatchOptions> = _options.asStateFlow()

    private val _update = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val update: StateFlow<UpdateState> = _update.asStateFlow()
    private var autoUpdateChecked = false

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = _loadError.asStateFlow()

    /** All work on files under filesDir/patch and filesDir/update runs here, one task at a time and in order. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val patchFiles = Dispatchers.IO.limitedParallelism(1)

    private enum class Stage { UNINSTALL_ORIGINAL, INSTALL_PATCHED, RESTORE_ORIGINAL, SELF_UPDATE }

    /** The one request CarPort is waiting on Android for. Events for any other attempt are ignored. */
    private class Waiting(
        val stage: Stage,
        val attempt: Int,
        val label: String,
        val packageName: String,
        val result: PatchResult? = null,
        /** INSTALL_PATCHED only: the original was uninstalled for this install, so failing loses the app. */
        val originalRemoved: Boolean = false,
    ) {
        /** Set from the install thread before the commit. */
        @Volatile var sessionId: Int? = null
        var confirmIntent: Intent? = null
    }

    private class Retry(val label: String, val result: PatchResult, val originalRemoved: Boolean)

    /**
     * An uninstall of the original that CarPort stopped waiting for (cancelled or reported as
     * failed). Android can't take an uninstall request back, and a confirmation screen may still
     * be open, so the patched APKs are kept: if the original disappears later, CarPort installs
     * the patched copy on its next resume instead of leaving the user with nothing.
     */
    private var unresolvedUninstall: Retry? = null

    private var waiting: Waiting? = null
    private var retry: Retry? = null
    private var refreshJob: Job? = null

    init {
        viewModelScope.launch { InstallEvents.events.collect(::onInstallEvent) }
        // A finished self-update leaves its APK behind; the new process removes it.
        viewModelScope.launch(patchFiles) { File(context.filesDir, UPDATE_DIR).deleteRecursively() }
        refresh()
        checkForUpdate(manual = false)
    }

    /**
     * Re-reads permissions, the key and the app list. Called on every resume;
     * [userInitiated] (pull to refresh) also shows the refresh indicator.
     */
    fun refresh(userInitiated: Boolean = false) {
        resumeUnresolvedUninstall()
        _setup.value = SetupStatus(
            canInstallApps = context.packageManager.canRequestPackageInstalls(),
            androidAutoInstalled = InstalledApps.isInstalled(context, InstalledApps.ANDROID_AUTO_PACKAGE),
        )
        refreshJob?.cancel()
        _refreshing.value = userInitiated
        refreshJob = viewModelScope.launch {
            try {
                val sha = withContext(Dispatchers.IO) { keys.key().sha256 }
                _keyInfo.value = KeyInfo(sha, settings.backedUpKeySha256 == sha)
                _apps.value = withContext(Dispatchers.IO) { InstalledApps.load(context, sha) }
                _loadError.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _loadError.value = e.userMessage()
                if (_apps.value == null) _apps.value = emptyList()
            } finally {
                // A cancelled refresh must not hide the indicator of the one that replaced it.
                if (refreshJob === coroutineContext.job) _refreshing.value = false
            }
        }
    }

    private fun resumeUnresolvedUninstall() {
        val unresolved = unresolvedUninstall ?: return
        if (_job.value is JobState.Working || waiting != null) return
        if (InstalledApps.isInstalled(context, unresolved.result.packageName)) return
        // The original was removed after all: install the patched copy now.
        unresolvedUninstall = null
        retry = null
        viewModelScope.launch { installPatched(unresolved.label, unresolved.result, originalRemoved = true) }
    }

    fun setOptions(options: PatchOptions) {
        settings.options = options
        _options.value = options
    }

    // ---- Patching ----

    fun patchInstalledApp(app: AppEntry) {
        startJob(app.label) { _ ->
            File(app.sourceDir) to app.splitSourceDirs.map(::File)
        }
    }

    fun patchApkFile(uri: Uri) {
        startJob(FILE_LABEL) { workDir ->
            val target = File(workDir, "input.apk")
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { input.copyTo(it, 1 shl 16) }
            } ?: throw PatchException("Could not open the selected file.")
            target to emptyList()
        }
    }

    private fun startJob(label: String, prepare: (workDir: File) -> Pair<File, List<File>>) {
        if (_job.value != JobState.Idle) return
        retry = null
        unresolvedUninstall = null
        _job.value = JobState.Working(label, "Preparing")
        viewModelScope.launch {
            try {
                val result = withContext(patchFiles) {
                    val root = File(context.filesDir, PATCH_DIR).apply { deleteRecursively() }
                    val importDir = File(root, "import").apply { mkdirs() }
                    val (base, splits) = prepare(importDir)
                    ApkPatcher(keys.key()).patch(
                        base = base,
                        splits = splits,
                        outputDir = File(root, "out"),
                        options = _options.value,
                    ) { step -> _job.value = JobState.Working(label, step) }
                }
                val displayLabel = if (label == FILE_LABEL) result.packageName else label
                val sha = withContext(Dispatchers.IO) { keys.key().sha256 }
                when (InstalledApps.signerState(context, result.packageName, sha)) {
                    SignerState.NOT_INSTALLED, SignerState.CARPORT_KEY ->
                        installPatched(displayLabel, result, originalRemoved = false)
                    SignerState.OTHER_KEY -> _job.value = JobState.ConfirmUninstall(displayLabel, result)
                    SignerState.OTHER_PROFILE -> finishAndClean(
                        "Can't replace $displayLabel",
                        "$displayLabel is also installed in another profile (work, cloned or private space). " +
                            "Android would keep that copy, so the patched one could not be installed. " +
                            "Remove it from the other profile first. Nothing was changed.",
                    )
                    SignerState.ARCHIVED -> finishAndClean(
                        "Can't replace $displayLabel",
                        "$displayLabel is archived on this phone. Restore it (tap its icon) or uninstall it fully, " +
                            "then patch again. Nothing was changed.",
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                finishAndClean("Couldn't patch $label", e.userMessage())
            }
        }
    }

    fun confirmUninstall() {
        val state = _job.value as? JobState.ConfirmUninstall ?: return
        val attempt = Installer.nextAttempt()
        waiting = Waiting(Stage.UNINSTALL_ORIGINAL, attempt, state.label, state.result.packageName, state.result)
        _job.value = JobState.Working(state.label, "Waiting for Android to uninstall the original", cancellable = true)
        try {
            Installer.uninstall(context, state.result.packageName, attempt)
        } catch (e: Exception) {
            waiting = null
            finishAndClean("Couldn't uninstall ${state.label}", e.userMessage())
        }
    }

    private suspend fun installPatched(label: String, result: PatchResult, originalRemoved: Boolean) {
        val attempt = Installer.nextAttempt()
        val wait = Waiting(Stage.INSTALL_PATCHED, attempt, label, result.packageName, result, originalRemoved)
        waiting = wait
        // Not cancellable while the APKs are copied into the session; only once Android has it.
        _job.value = JobState.Working(label, "Installing")
        try {
            withContext(patchFiles) {
                Installer.install(context, result.packageName, result.apks, attempt) { wait.sessionId = it }
            }
            markCancellableIfStillWaiting(wait, "Installing")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (waiting === wait) waiting = null
            failInstall(label, result, originalRemoved, e.userMessage())
        }
    }

    /** The session is committed: let the user give up if Android never answers. */
    private fun markCancellableIfStillWaiting(wait: Waiting, step: String) {
        // Android may already have answered (or asked for confirmation) while the APKs were copied.
        if (waiting === wait && wait.confirmIntent == null && _job.value is JobState.Working) {
            _job.value = JobState.Working(wait.label, step, cancellable = true)
        }
    }

    /** "Try again" after a failed or cancelled install; the patched APKs are still on disk. */
    fun retryInstall() {
        val again = retry ?: return
        if (_job.value !is JobState.Finished) return
        retry = null
        viewModelScope.launch { installPatched(again.label, again.result, again.originalRemoved) }
    }

    private fun failInstall(label: String, result: PatchResult, originalRemoved: Boolean, reason: String) {
        retry = Retry(label, result, originalRemoved)
        val warning = if (originalRemoved) {
            "\n\nThe original app was already uninstalled. Tap Try again to install the patched copy. " +
                "If you close this message, the patched copy is deleted too."
        } else {
            ""
        }
        _job.value = JobState.Finished(false, "Couldn't install $label", reason + warning, canRetry = true)
    }

    // ---- Restore original ----

    /** Removes the patched copy so the original can be reinstalled (for example from the Play Store). */
    fun restoreOriginal(app: AppEntry) {
        if (_job.value != JobState.Idle) return
        val attempt = Installer.nextAttempt()
        waiting = Waiting(Stage.RESTORE_ORIGINAL, attempt, app.label, app.packageName)
        _job.value = JobState.Working(app.label, "Waiting for Android to uninstall the patched copy", cancellable = true)
        try {
            Installer.uninstall(context, app.packageName, attempt)
        } catch (e: Exception) {
            waiting = null
            _job.value = JobState.Finished(false, "Couldn't uninstall ${app.label}", e.userMessage())
        }
    }

    // ---- Self-update ----

    /** Automatic checks run once per launch and stay silent on errors; manual checks report them. */
    fun checkForUpdate(manual: Boolean) {
        if (_update.value == UpdateState.Checking) return
        if (!manual && autoUpdateChecked) return
        autoUpdateChecked = true
        _update.value = UpdateState.Checking
        viewModelScope.launch {
            _update.value = try {
                withContext(Dispatchers.IO) { UpdateChecker.findUpdate() }
                    ?.let { UpdateState.Available(it) } ?: UpdateState.UpToDate
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (manual) UpdateState.Failed(e.userMessage()) else UpdateState.Idle
            }
        }
    }

    fun installUpdate() {
        val release = (_update.value as? UpdateState.Available)?.release ?: return
        if (_job.value != JobState.Idle) return
        val title = "Updating CarPort to ${release.version}"
        _job.value = JobState.Working(title, "Downloading")
        viewModelScope.launch {
            try {
                val apk = withContext(patchFiles) {
                    val dir = File(context.filesDir, UPDATE_DIR).apply {
                        deleteRecursively()
                        mkdirs()
                    }
                    val file = File(dir, "carport-${release.version}.apk")
                    UpdateChecker.download(release, file) { percent ->
                        _job.value = JobState.Working(title, if (percent >= 0) "Downloading $percent%" else "Downloading")
                    }
                    _job.value = JobState.Working(title, "Checking the download")
                    UpdateChecker.verify(context, file)
                    file
                }
                val attempt = Installer.nextAttempt()
                val wait = Waiting(Stage.SELF_UPDATE, attempt, title, context.packageName)
                waiting = wait
                val step = "Installing. CarPort closes when the update is done; open it again afterwards."
                _job.value = JobState.Working(title, step)
                withContext(patchFiles) {
                    Installer.install(context, context.packageName, listOf(apk), attempt) { wait.sessionId = it }
                }
                markCancellableIfStillWaiting(wait, step)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                waiting = null
                _job.value = JobState.Finished(false, "Update failed", e.userMessage())
            }
        }
    }

    // ---- Android's answers ----

    private suspend fun onInstallEvent(event: InstallEvent) {
        val wait = waiting ?: return
        if (event.attempt != wait.attempt) return

        if (event.needsUserAction) {
            val confirm = event.confirmIntent
            if (confirm == null) {
                cancelWaiting("Android didn't provide its confirmation screen.")
                return
            }
            wait.confirmIntent = confirm
            _userAction.value = UserAction(confirm, isConfirmation = true)
            _job.value = JobState.Working(
                wait.label,
                "Confirm on Android's screen",
                cancellable = true,
                canReopenPrompt = true,
            )
            return
        }

        waiting = null
        _userAction.value = null
        when (wait.stage) {
            Stage.UNINSTALL_ORIGINAL ->
                if (event.isSuccess) {
                    installPatched(wait.label, wait.result!!, originalRemoved = true)
                } else {
                    keepForUnresolvedUninstall(wait, "${wait.label} was not uninstalled", Installer.describeFailure(event))
                }

            Stage.INSTALL_PATCHED -> {
                if (event.isSuccess) {
                    finishAndClean(
                        "${wait.label} is ready for your car",
                        "Connect your phone to the car. The app appears in the Android Auto launcher.\n\n" +
                            "If it doesn't, check that Android Auto's Unknown sources setting is on (setup guide).",
                        success = true,
                    )
                } else {
                    failInstall(wait.label, wait.result!!, wait.originalRemoved, Installer.describeFailure(event))
                }
                refresh()
            }

            Stage.RESTORE_ORIGINAL -> {
                _job.value = if (event.isSuccess) {
                    _userAction.value = UserAction(
                        Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${wait.packageName}")),
                        isConfirmation = false,
                    )
                    JobState.Finished(true, "Patched copy removed", "Reinstall ${wait.label} from its store page or wherever you got it.")
                } else {
                    JobState.Finished(false, "${wait.label} was not uninstalled", Installer.describeFailure(event))
                }
                refresh()
            }

            Stage.SELF_UPDATE ->
                // On success Android usually closes CarPort before this arrives.
                _job.value = if (event.isSuccess) {
                    JobState.Finished(true, "CarPort updated", "You're on the latest version.")
                } else {
                    JobState.Finished(false, "Update failed", Installer.describeFailure(event))
                }
        }
    }

    /** Shows Android's confirmation screen again after the user left it. */
    fun reopenPrompt() {
        val confirm = waiting?.confirmIntent ?: return
        _userAction.value = UserAction(confirm, isConfirmation = true)
    }

    /** Stops waiting for Android. Later answers for this attempt are ignored. */
    fun cancelWaiting(reason: String? = null) {
        val wait = waiting ?: return
        if (_job.value !is JobState.Working) return
        waiting = null
        _userAction.value = null
        wait.sessionId?.let { Installer.abandon(context, it) }
        val message = reason ?: "Cancelled."
        when (wait.stage) {
            Stage.INSTALL_PATCHED -> failInstall(wait.label, wait.result!!, wait.originalRemoved, message)
            Stage.UNINSTALL_ORIGINAL -> keepForUnresolvedUninstall(wait, "${wait.label} was not changed", message)
            Stage.RESTORE_ORIGINAL, Stage.SELF_UPDATE -> _job.value = JobState.Finished(false, wait.label, message)
        }
    }

    private fun keepForUnresolvedUninstall(wait: Waiting, title: String, message: String) {
        unresolvedUninstall = Retry(wait.label, wait.result!!, originalRemoved = true)
        retry = null
        _job.value = JobState.Finished(
            false,
            title,
            "$message\n\nIf you still confirm Android's uninstall screen, CarPort installs the patched copy " +
                "the next time you open it.",
        )
    }

    fun onUserActionLaunched(action: UserAction) {
        if (_userAction.value === action) _userAction.value = null
    }

    fun onUserActionFailed(action: UserAction) {
        onUserActionLaunched(action)
        if (action.isConfirmation) cancelWaiting("Android's confirmation screen could not be opened.")
    }

    fun dismissJob() {
        if (_job.value is JobState.Working) return
        retry = null
        _job.value = JobState.Idle
        // Keep the patched APKs while an uninstall might still complete; the next job replaces them.
        if (unresolvedUninstall == null) cleanUpPatchFiles()
        resumeUnresolvedUninstall()
    }

    private fun finishAndClean(title: String, message: String, success: Boolean = false) {
        retry = null
        _job.value = JobState.Finished(success, title, message)
        cleanUpPatchFiles()
    }

    /** Patched APKs of big apps can be hundreds of MB; drop them once they can't be needed. */
    private fun cleanUpPatchFiles() {
        viewModelScope.launch(patchFiles) { File(context.filesDir, PATCH_DIR).deleteRecursively() }
    }

    // ---- Signing key ----

    fun exportKey(uri: Uri) {
        viewModelScope.launch {
            val message = try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { keys.export(it) }
                        ?: throw PatchException("Could not open the destination file.")
                    settings.backedUpKeySha256 = keys.key().sha256
                }
                refresh()
                "Signing key saved. Keep it somewhere safe."
            } catch (e: Exception) {
                "Backup failed: ${e.userMessage()}"
            }
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    fun importKey(uri: Uri) {
        viewModelScope.launch {
            val message = try {
                withContext(Dispatchers.IO) {
                    val imported = context.contentResolver.openInputStream(uri)?.use { keys.import(it) }
                        ?: throw PatchException("Could not open the selected file.")
                    // An imported key is by definition backed up elsewhere.
                    settings.backedUpKeySha256 = imported.sha256
                }
                refresh()
                "Signing key imported."
            } catch (e: Exception) {
                "Import failed: ${e.userMessage()}"
            }
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    private companion object {
        const val PATCH_DIR = "patch"
        const val UPDATE_DIR = "update"
        const val FILE_LABEL = "APK file"
    }

    private fun Throwable.userMessage(): String = when (this) {
        is PatchException -> message ?: "Unknown error."
        is OutOfMemoryError -> "The phone ran out of memory while patching this app."
        else -> message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName
    }
}
