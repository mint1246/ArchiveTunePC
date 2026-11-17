package moe.rukamori.archivetune.social

import android.content.Context
import kotlinx.coroutines.CoroutineScope

fun createAndroidSocialManager(
    context: Context,
    scope: CoroutineScope,
    baseUrl: String = AndroidSocialApi.DEFAULT_BASE_URL,
): AndroidSocialManager {
    val api = AndroidSocialApi(baseUrl)
    val login = AndroidSocialLogin(context, api)
    return AndroidSocialManager(
        scope = scope,
        api = api,
        sessionStore = AndroidSocialSessionStore(context),
        login = { login.signIn() },
        realtime = AndroidSocialRealtime(api),
    )
}
