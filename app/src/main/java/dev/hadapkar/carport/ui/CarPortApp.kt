package dev.hadapkar.carport.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

private enum class Screen { APPS, SETUP, SETTINGS, CAR_PREVIEW, RECENT }

@Composable
fun CarPortApp(viewModel: MainViewModel = viewModel()) {
    // Open the setup guide on launch until CarPort may install apps.
    var screen by rememberSaveable {
        mutableStateOf(if (viewModel.setup.value.canInstallApps) Screen.APPS else Screen.SETUP)
    }

    // Permissions and installed apps change while CarPort is in the background.
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    // Android blocks activity starts from the background, so confirmation and store screens
    // are started here, by the visible activity. Collection pauses while CarPort is not
    // started, so a request made in the background runs when the user comes back.
    val context = LocalContext.current
    val userAction by viewModel.userAction.collectAsStateWithLifecycle()
    LaunchedEffect(userAction) {
        val action = userAction ?: return@LaunchedEffect
        if (runCatching { context.startActivity(action.intent) }.isSuccess) {
            viewModel.onUserActionLaunched(action)
        } else {
            viewModel.onUserActionFailed(action)
        }
    }

    val job by viewModel.job.collectAsStateWithLifecycle()
    JobDialogs(
        job = job,
        onConfirmUninstall = viewModel::confirmUninstall,
        onCancelWaiting = { viewModel.cancelWaiting() },
        onReopenPrompt = viewModel::reopenPrompt,
        onRetry = viewModel::retryInstall,
        onDismiss = viewModel::dismissJob,
    )

    if (screen != Screen.APPS) {
        BackHandler { screen = if (screen == Screen.CAR_PREVIEW || screen == Screen.RECENT) Screen.SETTINGS else Screen.APPS }
    }
    when (screen) {
        Screen.APPS -> AppsScreen(
            viewModel = viewModel,
            onOpenSetup = { screen = Screen.SETUP },
            onOpenSettings = { screen = Screen.SETTINGS },
        )
        Screen.SETUP -> SetupScreen(
            viewModel = viewModel,
            onDone = { screen = Screen.APPS },
            onTestMedia = { screen = Screen.CAR_PREVIEW },
        )
        Screen.SETTINGS -> SettingsScreen(
            viewModel = viewModel,
            onBack = { screen = Screen.APPS },
            onOpenSetup = { screen = Screen.SETUP },
            onTestMedia = { screen = Screen.CAR_PREVIEW },
            onOpenRecent = { screen = Screen.RECENT },
        )
        Screen.RECENT -> RecentlyPlayedScreen(onBack = { screen = Screen.SETTINGS })
        Screen.CAR_PREVIEW -> CarPreviewScreen(
            onBack = { screen = Screen.SETTINGS },
            onOpenSetup = { screen = Screen.SETUP },
        )
    }
}
