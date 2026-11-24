package moe.rukamori.archivetune.shared

import androidx.compose.ui.window.ComposeUIViewController
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.request.crossfade
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.playback.ios.AvFoundationPlaybackEngine
import moe.rukamori.archivetune.sdk.lifecycle.SdkLifecycle
import platform.Foundation.NSLog
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSUUID
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIBackgroundTaskInvalid
import platform.UIKit.UIViewController
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.setUnhandledExceptionHook
import kotlin.native.terminateWithUnhandledException

/** One application-scoped service graph backed by ArchiveTune SDK (`sdk-lifecycle`, `ios-playback`, etc.). */
@OptIn(ExperimentalNativeApi::class)
private object IosServices {
    init {
        setUnhandledExceptionHook { failure ->
            val detail = failure.stackTraceToString().replace(Regex("https?://\\S+"), "[redacted URL]")
            // Use %s (not %@): Kotlin/Native bridges varargs String to C char*, not NSString*.
            NSLog("%s", "ArchiveTune uncaught Kotlin exception: $detail")
            terminateWithUnhandledException(failure)
        }
        // App.kt configures the same Coil transition for all Android artwork.
        SingletonImageLoader.setSafe { context -> ImageLoader.Builder(context).crossfade(true).build() }
    }

    private val lifecycle = SdkLifecycle(Dispatchers.Main.immediate)
    private val scope = lifecycle.scope()
    private val catalog = InnerTubeCatalog(HttpClient(Darwin) {
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            requestTimeoutMillis = 30_000
            socketTimeoutMillis = 30_000
        }
    }, preferHls = true)
    private val engine = AvFoundationPlaybackEngine()
    private val library = MusicLibrary(IosMusicStore(), scope)
    private val lyrics = LyricsController(AndroidLyricsRepository(HttpClient(Darwin) {
        expectSuccess = false
        install(HttpTimeout) { requestTimeoutMillis = 15_000 }
    }), scope)
    private val platformBridge = IosPlatformBridge()
    private val social = MusicSocialController(
        http = HttpClient(Darwin) {
            expectSuccess = false
            install(HttpTimeout) { requestTimeoutMillis = 30_000 }
        },
        scope = scope,
        login = ::iosDiscordLogin,
        nonce = { NSUUID().UUIDString + NSUUID().UUIDString },
        sessionStore = IosSocialSessionStore(),
    )
    private val news = MusicNewsController(HttpClient(Darwin) {
        expectSuccess = false
        install(HttpTimeout) { requestTimeoutMillis = 15_000 }
    }, scope)
    val controller = lifecycle.register(
        MusicController(
            catalog = catalog,
            engine = engine,
            scope = scope,
            lyricsController = lyrics,
            libraryController = library,
            onShareTrack = ::shareIosTrack,
            social = social,
            news = news,
            platformBridge = platformBridge,
        ),
    )

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
            try {
                library.flush()
            } catch (_: Exception) {
                // MusicLibrary publishes the persistence error and keeps the revision dirty.
            } finally {
                if (token != UIBackgroundTaskInvalid) app.endBackgroundTask(token)
                token = UIBackgroundTaskInvalid
            }
        }
    }.also { observer ->
        lifecycle.onClosing("remove-background-observer") {
            NSNotificationCenter.defaultCenter.removeObserver(observer)
        }
    }
}

/** Exported as MainViewControllerKt.MainViewController() in ArchiveTuneShared.framework. */
fun MainViewController(): UIViewController = ComposeUIViewController {
    ArchiveTuneApp(IosServices.controller)
}
