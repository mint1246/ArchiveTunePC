/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package moe.rukamori.archivetune.shared

import kotlinx.cinterop.cValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import platform.CoreGraphics.CGRect
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSHTTPCookie
import platform.Foundation.NSNumber
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.Foundation.NSURLSession
import platform.Foundation.dataTaskWithURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUUID
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile
import platform.UIKit.*
import platform.UniformTypeIdentifiers.UTTypeAudio
import platform.UniformTypeIdentifiers.UTTypeJSON
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.WebKit.WKWebsiteDataStore
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class IosPlatformBridge : MusicPlatformBridge {
    override val canShare: Boolean = true
    override val canDownload: Boolean = true
    override val canImportLocalFiles: Boolean = true
    override val canSignInYouTube: Boolean = true
    override val canBackupRestore: Boolean = true
    private var retainedDelegate: NSObject? = null

    private fun appSupportSubdir(name: String): String {
        val fm = NSFileManager.defaultManager
        val base = (fm.URLsForDirectory(NSApplicationSupportDirectory, NSUserDomainMask)
            .firstOrNull() as? NSURL)?.path ?: "/tmp"
        val dir = "$base/ArchiveTune/$name"
        fm.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
        return dir
    }

    private fun cachesSubdir(name: String): String {
        val fm = NSFileManager.defaultManager
        val base = (fm.URLsForDirectory(NSCachesDirectory, NSUserDomainMask)
            .firstOrNull() as? NSURL)?.path ?: "/tmp"
        val dir = "$base/$name"
        fm.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
        return dir
    }

    private fun topPresenter(): UIViewController? {
        val root = UIApplication.sharedApplication.keyWindow?.rootViewController ?: return null
        var presenter: UIViewController = root
        while (presenter.presentedViewController != null) {
            presenter = presenter.presentedViewController!!
        }
        return presenter
    }

    override fun shareText(text: String) {
        dispatch_async(dispatch_get_main_queue()) {
            val presenter = topPresenter() ?: return@dispatch_async
            val sheet = UIActivityViewController(activityItems = listOf(text), applicationActivities = null)
            sheet.popoverPresentationController?.sourceView = presenter.view
            presenter.presentViewController(sheet, animated = true, completion = null)
        }
    }

    override fun copyToClipboard(text: String) {
        dispatch_async(dispatch_get_main_queue()) {
            UIPasteboard.generalPasteboard.string = text
        }
    }

    override fun openUrl(url: String) {
        val parsed = NSURL(string = url) ?: return
        dispatch_async(dispatch_get_main_queue()) {
            UIApplication.sharedApplication.openURL(parsed)
        }
    }

    override fun setKeepScreenAwake(enabled: Boolean) {
        dispatch_async(dispatch_get_main_queue()) {
            UIApplication.sharedApplication.idleTimerDisabled = enabled
        }
    }

    override suspend fun downloadAudio(
        trackId: String,
        source: ResolvedAudio,
        onProgress: (Float) -> Unit,
    ): Result<Pair<String, Long>> = withContext(Dispatchers.Default) {
        val url = NSURL(string = source.url)
            ?: return@withContext Result.failure(IllegalArgumentException("Invalid download URL"))
        val safeId = trackId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(64).ifBlank { "track" }
        val ext = when {
            source.mimeType.contains("mpeg", true) || source.mimeType.contains("mp3", true) -> "mp3"
            source.mimeType.contains("wav", true) -> "wav"
            source.mimeType.contains("flac", true) -> "flac"
            else -> "m4a"
        }
        val targetPath = "${appSupportSubdir("downloads")}/$safeId.$ext"
        onProgress(0.15f)
        val dataResult = suspendCancellableCoroutine<Result<NSData>> { cont ->
            val task = NSURLSession.sharedSession.dataTaskWithURL(
                url = url,
                completionHandler = { data, _, error ->
                    if (!cont.isActive) return@dataTaskWithURL
                    if (error != null || data == null || data.length.toLong() <= 0L) {
                        cont.resume(Result.failure(IllegalStateException("Download failed")))
                    } else {
                        cont.resume(Result.success(data))
                    }
                },
            )
            cont.invokeOnCancellation { task.cancel() }
            task.resume()
        }
        val data = dataResult.getOrElse { return@withContext Result.failure(it) }
        onProgress(0.85f)
        val ok = data.writeToFile(targetPath, atomically = true)
        if (!ok) return@withContext Result.failure(IllegalStateException("Could not save downloaded file"))
        onProgress(1f)
        val fileUrl = NSURL.fileURLWithPath(targetPath).absoluteString ?: "file://$targetPath"
        Result.success(fileUrl to data.length.toLong())
    }

    override fun deleteLocalFile(fileUrl: String) {
        val path = NSURL(string = fileUrl)?.path ?: return
        NSFileManager.defaultManager.removeItemAtPath(path, error = null)
    }

    override fun fileExists(fileUrl: String): Boolean {
        val path = NSURL(string = fileUrl)?.path ?: return false
        return NSFileManager.defaultManager.fileExistsAtPath(path)
    }

    override suspend fun importLocalAudioFiles(): List<MusicTrack> = suspendCancellableCoroutine { cont ->
        dispatch_async(dispatch_get_main_queue()) {
            val presenter = topPresenter()
            if (presenter == null) {
                if (cont.isActive) cont.resume(emptyList())
                return@dispatch_async
            }
            val picker = UIDocumentPickerViewController(forOpeningContentTypes = listOf(UTTypeAudio), asCopy = true)
            picker.allowsMultipleSelection = true
            val delegate = object : NSObject(), UIDocumentPickerDelegateProtocol {
                override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
                    val localDir = appSupportSubdir("local")
                    val fm = NSFileManager.defaultManager
                    val imported = didPickDocumentsAtURLs.mapNotNull { item ->
                        val srcUrl = item as? NSURL ?: return@mapNotNull null
                        val srcPath = srcUrl.path ?: return@mapNotNull null
                        val fileName = srcUrl.lastPathComponent ?: "audio.m4a"
                        val title = fileName.substringBeforeLast('.').ifBlank { fileName }
                        val id = "local-" + NSUUID().UUIDString.take(12).lowercase()
                        val ext = fileName.substringAfterLast('.', "m4a").lowercase()
                        val dstPath = "$localDir/$id.$ext"
                        fm.removeItemAtPath(dstPath, error = null)
                        val copied = fm.copyItemAtPath(srcPath, toPath = dstPath, error = null)
                        if (!copied) return@mapNotNull null
                        val fileUrl = NSURL.fileURLWithPath(dstPath).absoluteString ?: "file://$dstPath"
                        MusicTrack(
                            id = id,
                            title = title,
                            artist = "Local File",
                            localFilePath = fileUrl,
                        )
                    }
                    retainedDelegate = null
                    if (cont.isActive) cont.resume(imported)
                }

                override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
                    retainedDelegate = null
                    if (cont.isActive) cont.resume(emptyList())
                }
            }
            retainedDelegate = delegate
            picker.delegate = delegate
            cont.invokeOnCancellation {
                retainedDelegate = null
                picker.dismissViewControllerAnimated(true, null)
            }
            presenter.presentViewController(picker, animated = true, completion = null)
        }
    }

    override suspend fun importBackupJson(): String? = suspendCancellableCoroutine { cont ->
        dispatch_async(dispatch_get_main_queue()) {
            val presenter = topPresenter()
            if (presenter == null) {
                if (cont.isActive) cont.resume(null)
                return@dispatch_async
            }
            val picker = UIDocumentPickerViewController(forOpeningContentTypes = listOf(UTTypeJSON), asCopy = true)
            picker.allowsMultipleSelection = false
            val delegate = object : NSObject(), UIDocumentPickerDelegateProtocol {
                override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
                    val url = didPickDocumentsAtURLs.firstOrNull() as? NSURL
                    val path = url?.path
                    val text = path?.let { NSString.stringWithContentsOfFile(it, NSUTF8StringEncoding, null) }
                    retainedDelegate = null
                    if (cont.isActive) cont.resume(text)
                }

                override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
                    retainedDelegate = null
                    if (cont.isActive) cont.resume(null)
                }
            }
            retainedDelegate = delegate
            picker.delegate = delegate
            cont.invokeOnCancellation {
                retainedDelegate = null
                picker.dismissViewControllerAnimated(true, null)
            }
            presenter.presentViewController(picker, animated = true, completion = null)
        }
    }

    override suspend fun signInYouTube(): Result<MusicYouTubeAuth> = suspendCancellableCoroutine { cont ->
        dispatch_async(dispatch_get_main_queue()) {
            val presenter = topPresenter()
            if (presenter == null) {
                if (cont.isActive) cont.resume(Result.failure(IllegalStateException("Sign-in window unavailable")))
                return@dispatch_async
            }
            val controller = UIViewController()
            val config = WKWebViewConfiguration().apply {
                websiteDataStore = WKWebsiteDataStore.nonPersistentDataStore()
            }
            val webView = WKWebView(frame = cValue<CGRect>(), configuration = config)
            var finished = false
            val delegate = object : NSObject(), WKNavigationDelegateProtocol, UIAdaptivePresentationControllerDelegateProtocol {
                override fun presentationControllerDidDismiss(presentationController: UIPresentationController) {
                    if (!finished && cont.isActive) {
                        finished = true
                        retainedDelegate = null
                        cont.resume(Result.failure(IllegalStateException("Sign-in cancelled")))
                    }
                }

                override fun webView(webView: WKWebView, didFinishNavigation: WKNavigation?) {
                    if (finished) return
                    val currentUrl = webView.URL?.absoluteString.orEmpty()
                    if (!currentUrl.startsWith("https://music.youtube.com")) return
                    webView.configuration.websiteDataStore.httpCookieStore.getAllCookies { rawCookies ->
                        if (finished) return@getAllCookies
                        val ytCookies = rawCookies.orEmpty().mapNotNull { it as? NSHTTPCookie }.filter { cookie ->
                            val domain = cookie.domain.lowercase()
                            domain.endsWith("youtube.com") || domain.endsWith("google.com")
                        }
                        val byName = ytCookies.associate { it.name to it.value }
                        val sapisid = byName["SAPISID"] ?: byName["__Secure-3PAPISID"]
                        if (!sapisid.isNullOrBlank() && (byName.containsKey("SID") || byName.containsKey("__Secure-3PSID"))) {
                            finished = true
                            retainedDelegate = null
                            val cookieHeader = byName.entries.joinToString("; ") { "${it.key}=${it.value}" }
                            val auth = MusicYouTubeAuth(cookie = cookieHeader, sapisid = sapisid)
                            controller.dismissViewControllerAnimated(true, null)
                            if (cont.isActive) cont.resume(Result.success(auth))
                        }
                    }
                }
            }
            retainedDelegate = delegate
            webView.navigationDelegate = delegate
            controller.view = webView
            controller.modalPresentationStyle = UIModalPresentationPageSheet
            controller.presentationController?.delegate = delegate
            presenter.presentViewController(controller, animated = true, completion = null)
            val loginUrl = "https://accounts.google.com/ServiceLogin?service=youtube&uilel=3&passive=true&continue=https%3A%2F%2Fmusic.youtube.com%2F"
            NSURL(string = loginUrl)?.let { webView.loadRequest(NSURLRequest.requestWithURL(it)) }
            cont.invokeOnCancellation {
                retainedDelegate = null
                controller.dismissViewControllerAnimated(true, null)
            }
        }
    }

    override fun loadYouTubeAuth(): MusicYouTubeAuth? {
        val raw = IosKeychain.read(YT_AUTH_KEY) ?: return null
        return try {
            val obj = Json.parseToJsonElement(raw) as? JsonObject ?: return null
            val cookie = (obj["cookie"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) ?: return null
            val sapisid = (obj["sapisid"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) ?: return null
            val account = (obj["account"] as? JsonObject)?.let { acc ->
                val name = (acc["name"] as? JsonPrimitive)?.contentOrNull ?: return@let null
                MusicYouTubeAccount(
                    name = name,
                    email = (acc["email"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                    handle = (acc["handle"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                    avatarUrl = (acc["avatarUrl"] as? JsonPrimitive)?.contentOrNull,
                )
            }
            MusicYouTubeAuth(cookie = cookie, sapisid = sapisid, account = account)
        } catch (_: Exception) {
            null
        }
    }

    override fun saveYouTubeAuth(auth: MusicYouTubeAuth?) {
        if (auth == null) {
            IosKeychain.delete(YT_AUTH_KEY)
            return
        }
        val payload = buildJsonObject {
            put("cookie", auth.cookie)
            put("sapisid", auth.sapisid)
            auth.account?.let { acc ->
                put("account", buildJsonObject {
                    put("name", acc.name)
                    put("email", acc.email)
                    put("handle", acc.handle)
                    acc.avatarUrl?.let { put("avatarUrl", it) }
                })
            }
        }.toString()
        IosKeychain.write(YT_AUTH_KEY, payload)
    }

    override fun computeStorageUsage(): MusicStorageUsage = MusicStorageUsage(
        downloadsBytes = directorySizeBytes(appSupportSubdir("downloads")),
        songCacheBytes = directorySizeBytes(cachesSubdir("audio_cache")),
        imageCacheBytes = directorySizeBytes(cachesSubdir("coil3_disk_cache")),
    )

    override fun clearImageCache() {
        clearDirectory(cachesSubdir("coil3_disk_cache"))
    }

    override fun clearAudioCache() {
        clearDirectory(cachesSubdir("audio_cache"))
    }

    private fun directorySizeBytes(dirPath: String): Long {
        val fm = NSFileManager.defaultManager
        val enumerator = fm.enumeratorAtPath(dirPath) ?: return 0L
        var total = 0L
        while (true) {
            val rel = enumerator.nextObject() as? String ?: break
            val full = "$dirPath/$rel"
            val attrs = fm.attributesOfItemAtPath(full, error = null)
            val size = (attrs?.get(NSFileSize) as? NSNumber)?.longLongValue ?: 0L
            total += size
        }
        return total
    }

    private fun clearDirectory(dirPath: String) {
        val fm = NSFileManager.defaultManager
        val items = fm.contentsOfDirectoryAtPath(dirPath, error = null).orEmpty()
        items.forEach { name ->
            val rel = name as? String ?: return@forEach
            fm.removeItemAtPath("$dirPath/$rel", error = null)
        }
    }

    private companion object {
        const val YT_AUTH_KEY = "youtube_music_auth_v1"
    }
}
