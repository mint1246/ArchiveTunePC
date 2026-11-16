package moe.rukamori.archivetune.shared

import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.request.crossfade
import androidx.compose.ui.window.ComposeUIViewController
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import platform.Foundation.NSUUID
import platform.Foundation.NSLog
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIBackgroundTaskInvalid
import platform.UIKit.UIViewController
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.setUnhandledExceptionHook
import kotlin.native.terminateWithUnhandledException

/** One application-scoped service graph: SwiftUI view recreation never stops the player. */
@OptIn(ExperimentalNativeApi::class)
private object IosServices {
    init {
        setUnhandledExceptionHook { failure ->
            val detail = failure.stackTraceToString().replace(Regex("https?://\\S+"), "[redacted URL]")
            NSLog("%@", "ArchiveTune uncaught Kotlin exception: $detail")
            terminateWithUnhandledException(failure)
        }
        // App.kt configures the same Coil transition for all Android artwork.
        SingletonImageLoader.setSafe { context -> ImageLoader.Builder(context).crossfade(true).build() }
    }
    private val scope = MainScope()
    private val catalog = InnerTubeCatalog(HttpClient(Darwin) {
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            requestTimeoutMillis = 30_000
            socketTimeoutMillis = 30_000
        }
    }, preferHls = true)
    private val player = IosPlayerEngine()
    private val library = MusicLibrary(IosMusicStore(), scope)
    private val lyrics = LyricsController(AndroidLyricsRepository(HttpClient(Darwin) {
        expectSuccess = false
        install(HttpTimeout) { requestTimeoutMillis = 15_000 }
    }), scope)
    private val social = MusicSocialController(HttpClient(Darwin) {
        expectSuccess = false
        install(HttpTimeout) { requestTimeoutMillis = 30_000 }
    }, scope, ::iosDiscordLogin, { NSUUID().UUIDString + NSUUID().UUIDString })
    private val news = MusicNewsController(HttpClient(Darwin) {
        expectSuccess = false
        install(HttpTimeout) { requestTimeoutMillis = 15_000 }
    }, scope)
    val controller = MusicController(catalog, player, scope, lyricsController = lyrics, libraryController = library, onShareTrack = ::shareIosTrack, social = social, news = news).also { music ->
        player.bindQueueCommands(onNext = music::next, onPrevious = music::previous)
    }

    // Flush before suspension; the finite background task lets a pending atomic write finish.
    private val backgroundObserver = NSNotificationCenter.defaultCenter.addObserverForName(
        UIApplicationDidEnterBackgroundNotification, `object` = null, queue = NSOperationQueue.mainQueue,
    ) {
        val app = UIApplication.sharedApplication
        var token = UIBackgroundTaskInvalid
        token = app.beginBackgroundTaskWithName("Save ArchiveTune library") {
            if (token != UIBackgroundTaskInvalid) app.endBackgroundTask(token)
            token = UIBackgroundTaskInvalid
        }
        scope.launch {
            try { library.flush() } catch (_: Exception) {
                // MusicLibrary publishes the persistence error and keeps the revision dirty.
            } finally {
                if (token != UIBackgroundTaskInvalid) app.endBackgroundTask(token)
                token = UIBackgroundTaskInvalid
            }
        }
    }
}

/** Exported as MainViewControllerKt.MainViewController() in ArchiveTuneShared.framework. */
fun MainViewController(): UIViewController = ComposeUIViewController {
    ArchiveTuneApp(IosServices.controller)
}
