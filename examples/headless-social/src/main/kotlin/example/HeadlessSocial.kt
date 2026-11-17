package example

import kotlinx.coroutines.runBlocking
import moe.rukamori.archivetune.social.AndroidSocialApi

fun main(args: Array<String>) = runBlocking {
    require(args.size == 1) { "Pass the social service base URL" }
    val token = requireNotNull(System.getenv("ARCHIVETUNE_SOCIAL_TOKEN")) { "Set ARCHIVETUNE_SOCIAL_TOKEN" }
    val user = AndroidSocialApi(args.single()).me(token)
    println("Signed in as ${user.name}")
}
