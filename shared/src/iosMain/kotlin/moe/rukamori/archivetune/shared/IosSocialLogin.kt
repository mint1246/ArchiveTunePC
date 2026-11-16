@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package moe.rukamori.archivetune.shared

import kotlinx.cinterop.cValue
import platform.CoreGraphics.CGRect
import io.ktor.http.Url
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.*
import platform.UIKit.*
import platform.WebKit.*
import platform.darwin.NSObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Isolated, ephemeral WebKit auth. Only the nonce-bound ArchiveTune callback is accepted. */
internal suspend fun iosDiscordLogin(url: String, nonce: String): String = suspendCancellableCoroutine { continuation ->
    val root = UIApplication.sharedApplication.keyWindow?.rootViewController
    if (root == null) {
        continuation.resumeWithException(IllegalStateException("Sign-in window unavailable"))
        return@suspendCancellableCoroutine
    }
    var presenter: UIViewController = root
    while (presenter.presentedViewController != null) presenter = presenter.presentedViewController!!
    val controller = UIViewController()
    val configuration = WKWebViewConfiguration().apply { websiteDataStore = WKWebsiteDataStore.nonPersistentDataStore() }
    val browser = WKWebView(frame = cValue<CGRect>(), configuration = configuration)
    // Controller owns its delegate through the retained closure until completion/cancellation.
    val delegate = object : NSObject(), WKNavigationDelegateProtocol, UIAdaptivePresentationControllerDelegateProtocol {
        override fun presentationControllerDidDismiss(presentationController: UIPresentationController) {
            if (continuation.isActive) continuation.cancel()
        }

        override fun webView(webView: WKWebView, decidePolicyForNavigationAction: WKNavigationAction,
            decisionHandler: (WKNavigationActionPolicy) -> Unit) {
            val target = decidePolicyForNavigationAction.request.URL?.absoluteString.orEmpty()
            val parsed = try { Url(target) } catch (_: Exception) { null }
            if (parsed?.host == "127.0.0.1" && parsed.protocol.name == "http" && parsed.port == 49152 && parsed.encodedPath == "/archivetune-auth") {
                decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyCancel)
                if (continuation.isActive) {
                    try { continuation.resume(discordCallbackCode(target, nonce)) }
                    catch (_: Exception) { continuation.resumeWithException(IllegalStateException("Sign-in callback rejected")) }
                }
                controller.dismissViewControllerAnimated(true, null)
            } else decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyAllow)
        }
    }
    browser.navigationDelegate = delegate
    controller.view = browser
    controller.modalPresentationStyle = UIModalPresentationPageSheet
    controller.presentationController?.delegate = delegate
    presenter.presentViewController(controller, true, null)
    browser.loadRequest(NSURLRequest.requestWithURL(NSURL(string = url)!!))
    continuation.invokeOnCancellation {
        // Keep delegate strongly reachable for the complete native navigation lifetime.
        browser.navigationDelegate = delegate
        controller.dismissViewControllerAnimated(true, null)
    }
}
