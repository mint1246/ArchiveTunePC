package example

import moe.rukamori.archivetune.utils.SavedAccount
import moe.rukamori.archivetune.utils.decodeSavedAccounts
import moe.rukamori.archivetune.utils.encodeSavedAccounts

fun main() {
    val account = SavedAccount("id", "Example", "", "", "fixture-cookie", "", "", false, "")
    check(decodeSavedAccounts(encodeSavedAccounts(listOf(account))) == listOf(account))
    println("Saved account serialization works without Android UI")
}
