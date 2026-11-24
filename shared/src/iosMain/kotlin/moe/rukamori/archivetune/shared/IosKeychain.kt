/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package moe.rukamori.archivetune.shared

import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFBooleanTrue
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

/** Native iOS Keychain storage for sensitive tokens and account cookies. */
internal object IosKeychain {
    private const val SERVICE = "moe.rukamori.archivetune.ios.keychain"

    fun read(account: String): String? = memScoped {
        val query = CFDictionaryCreateMutable(kCFAllocatorDefault, 5, null, null) ?: return null
        val svcRef = CFBridgingRetain(SERVICE)
        val accRef = CFBridgingRetain(account)
        try {
            CFDictionaryAddValue(query, kSecClass, kSecClassGenericPassword)
            CFDictionaryAddValue(query, kSecAttrService, svcRef)
            CFDictionaryAddValue(query, kSecAttrAccount, accRef)
            CFDictionaryAddValue(query, kSecReturnData, kCFBooleanTrue)
            CFDictionaryAddValue(query, kSecMatchLimit, kSecMatchLimitOne)

            val result = alloc<CFTypeRefVar>()
            val status = SecItemCopyMatching(query, result.ptr)
            if (status != errSecSuccess || result.value == null) return null
            val data = CFBridgingRelease(result.value) as? NSData ?: return null
            NSString.create(data = data, encoding = NSUTF8StringEncoding)?.toString()
        } finally {
            CFRelease(query)
            svcRef?.let(::CFRelease)
            accRef?.let(::CFRelease)
        }
    }

    fun write(account: String, value: String): Boolean {
        delete(account)
        val data = NSString.create(string = value).dataUsingEncoding(NSUTF8StringEncoding) ?: return false
        val query = CFDictionaryCreateMutable(kCFAllocatorDefault, 5, null, null) ?: return false
        val svcRef = CFBridgingRetain(SERVICE)
        val accRef = CFBridgingRetain(account)
        val dataRef = CFBridgingRetain(data)
        return try {
            CFDictionaryAddValue(query, kSecClass, kSecClassGenericPassword)
            CFDictionaryAddValue(query, kSecAttrService, svcRef)
            CFDictionaryAddValue(query, kSecAttrAccount, accRef)
            CFDictionaryAddValue(query, kSecValueData, dataRef)
            CFDictionaryAddValue(query, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
            SecItemAdd(query, null) == errSecSuccess
        } finally {
            CFRelease(query)
            svcRef?.let(::CFRelease)
            accRef?.let(::CFRelease)
            dataRef?.let(::CFRelease)
        }
    }

    fun delete(account: String) {
        val query = CFDictionaryCreateMutable(kCFAllocatorDefault, 3, null, null) ?: return
        val svcRef = CFBridgingRetain(SERVICE)
        val accRef = CFBridgingRetain(account)
        try {
            CFDictionaryAddValue(query, kSecClass, kSecClassGenericPassword)
            CFDictionaryAddValue(query, kSecAttrService, svcRef)
            CFDictionaryAddValue(query, kSecAttrAccount, accRef)
            SecItemDelete(query)
        } finally {
            CFRelease(query)
            svcRef?.let(::CFRelease)
            accRef?.let(::CFRelease)
        }
    }
}
