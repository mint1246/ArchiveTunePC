/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import moe.rukamori.archivetune.account.login.YouTubeAccountBackend
import moe.rukamori.archivetune.account.login.YouTubeAccountCoordinator
import moe.rukamori.archivetune.account.login.YouTubeAccountCredentialPolicy
import moe.rukamori.archivetune.account.login.YouTubeAccountStore
import moe.rukamori.archivetune.auth.AndroidYouTubeAccountStore
import moe.rukamori.archivetune.auth.CoreYouTubeAccountBackend
import moe.rukamori.archivetune.auth.CoreYouTubeAccountCredentialPolicy
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object YouTubeAccountSdkModule {
    @Provides
    @Singleton
    fun provideCredentialPolicy(): YouTubeAccountCredentialPolicy = CoreYouTubeAccountCredentialPolicy()

    @Provides
    @Singleton
    fun provideBackend(): YouTubeAccountBackend = CoreYouTubeAccountBackend()

    @Provides
    @Singleton
    fun provideStore(store: AndroidYouTubeAccountStore): YouTubeAccountStore = store

    @Provides
    @Singleton
    fun provideCoordinator(
        credentialPolicy: YouTubeAccountCredentialPolicy,
        backend: YouTubeAccountBackend,
        store: YouTubeAccountStore,
    ): YouTubeAccountCoordinator =
        YouTubeAccountCoordinator(
            credentialPolicy = credentialPolicy,
            backend = backend,
            store = store,
        )
}
