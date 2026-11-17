package moe.rukamori.archivetune.utils

import kotlin.test.Test
import kotlin.test.assertEquals

class SavedAccountsTest {
    private val account = SavedAccount(
        id = "account-id",
        name = "Name",
        email = "name@example.invalid",
        channelHandle = "@name",
        innerTubeCookie = "fixture-cookie",
        visitorData = "visitor",
        dataSyncId = "sync-id",
        ytmSync = true,
        selectedYtmPlaylists = "playlist-a",
    )

    @Test
    fun readsExistingJsonAndIgnoresUnknownFields() {
        val stored = """[{"id":"account-id","name":"Name","email":"name@example.invalid","channelHandle":"@name","innerTubeCookie":"fixture-cookie","visitorData":"visitor","dataSyncId":"sync-id","ytmSync":true,"selectedYtmPlaylists":"playlist-a","futureField":42}]"""
        assertEquals(listOf(account), decodeSavedAccounts(stored))
        assertEquals(stored.replace(",\"futureField\":42", ""), encodeSavedAccounts(listOf(account)))
    }

    @Test
    fun roundTripsAccountsInOrderAndRetainsDuplicates() {
        val accounts = listOf(account, account.copy(id = "second"), account)
        assertEquals(accounts, decodeSavedAccounts(encodeSavedAccounts(accounts)))
    }

    @Test
    fun emptyOrMalformedStorageRetainsExistingFallback() {
        assertEquals(emptyList(), decodeSavedAccounts(" "))
        assertEquals(emptyList(), decodeSavedAccounts("not json"))
    }
}
