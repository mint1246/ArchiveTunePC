package moe.rukamori.archivetune.social

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun createAndroidSocialManager(
    context: Context,
    scope: CoroutineScope,
    baseUrl: String = AndroidSocialApi.DEFAULT_BASE_URL,
): AndroidSocialManager {
    val api = AndroidSocialApi(baseUrl)
    val login = AndroidSocialLogin(api, openBrowser = { url ->
        withContext(Dispatchers.Main.immediate) {
            context.applicationContext.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    })
    return AndroidSocialManager(
        scope = scope,
        api = api,
        sessionStore = AndroidSocialSessionStore(context),
        login = { login.signIn() },
        realtime = AndroidSocialRealtime(api),
    )
}
