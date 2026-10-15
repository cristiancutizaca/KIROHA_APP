package eu.kanade.tachiyomi.ui.more

import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.core.preference.asState
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.more.MoreScreen
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.connections.discord.DiscordRPCService
import eu.kanade.tachiyomi.data.connections.discord.DiscordScreen
import eu.kanade.tachiyomi.data.download.anime.AnimeDownloadManager
import eu.kanade.tachiyomi.data.download.manga.MangaDownloadManager
import eu.kanade.tachiyomi.ui.category.CategoriesTab
import eu.kanade.tachiyomi.ui.download.DownloadsTab
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.setting.PlayerSettingsScreen
import eu.kanade.tachiyomi.ui.setting.SettingsScreen
import eu.kanade.tachiyomi.ui.stats.StatsTab
import eu.kanade.tachiyomi.ui.storage.StorageTab
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import mihon.feature.support.SupportUsScreen
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

data object MoreTab : Tab {

    override val options: TabOptions
        @Composable
        get() {
            val isSelected = LocalTabNavigator.current.current.key == key
            val image = AnimatedImageVector.animatedVectorResource(R.drawable.anim_more_enter)
            return TabOptions(
                index = 4u,
                title = stringResource(MR.strings.label_more),
                icon = rememberAnimatedVectorPainter(image, isSelected),
            )
        }

    override suspend fun onReselect(navigator: Navigator) {
        navigator.push(SettingsScreen())
    }

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val viewModel = metroViewModel<MoreViewModel>()
        val downloadQueueState by viewModel.downloadQueueState.collectAsState()
        val navStyle = currentNavigationStyle()
        MoreScreen(
            downloadQueueStateProvider = { downloadQueueState },
            downloadedOnly = viewModel.downloadedOnly,
            onDownloadedOnlyChange = { viewModel.downloadedOnly = it },
            incognitoMode = viewModel.incognitoMode,
            onIncognitoModeChange = { viewModel.incognitoMode = it },
            // SY -->
            showNavUpdates = viewModel.showNavUpdates,
            showNavHistory = viewModel.showNavHistory,
            // SY <--
            navStyle = navStyle,
            onClickAlt = { navigator.push(navStyle.moreTab) },
            onClickDownloadQueue = { navigator.push(DownloadsTab) },
            onClickCategories = { navigator.push(CategoriesTab) },
            onClickStats = { navigator.push(StatsTab) },
            onClickNetworkStream = { navigator.push(NetworkStreamScreen) },
            onClickStorage = { navigator.push(StorageTab) },
            onClickDataAndStorage = { navigator.push(SettingsScreen(SettingsScreen.Destination.DataAndStorage)) },
            onClickPlayerSettings = { navigator.push(PlayerSettingsScreen(mainSettings = false)) },
            onClickSettings = { navigator.push(SettingsScreen()) },
            onClickSupport = { navigator.push(SupportUsScreen()) },
            onClickAbout = { navigator.push(SettingsScreen(SettingsScreen.Destination.About)) },
        )

        LaunchedEffect(Unit) {
            (context as? MainActivity)?.ready = true
            // AM (DISCORD) -->
            DiscordRPCService.setScreen(context, DiscordScreen.MORE)
            // <-- AM (DISCORD)
        }
    }
}

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class MoreViewModel(
    private val downloadManager: MangaDownloadManager,
    private val animeDownloadManager: AnimeDownloadManager,
    preferences: BasePreferences,
    // SY -->
    uiPreferences: UiPreferences,
    // SY <--
) : ViewModel() {

    var downloadedOnly by preferences.downloadedOnly.asState(viewModelScope)
    var incognitoMode by preferences.incognitoMode.asState(viewModelScope)

    // SY -->
    val showNavUpdates by uiPreferences.showNavUpdates.asState(viewModelScope)
    val showNavHistory by uiPreferences.showNavHistory.asState(viewModelScope)
    // SY <--

    private var _downloadQueueState: MutableStateFlow<DownloadQueueState> = MutableStateFlow(
        DownloadQueueState.Stopped,
    )
    val downloadQueueState: StateFlow<DownloadQueueState> = _downloadQueueState.asStateFlow()

    init {
        // Collect all four sources together. The previous nested combine
        // cancelled and re-subscribed to anime flows whenever the manga
        // queue changed, leading to extra work and transient stale states.
        viewModelScope.launchIO {
            combine(
                downloadManager.isDownloaderRunning,
                downloadManager.queueState,
                animeDownloadManager.isDownloaderRunning,
                animeDownloadManager.queueState,
            ) { mangaRunning, mangaQueue, animeRunning, animeQueue ->
                val pending = mangaQueue.size + animeQueue.size
                when {
                    pending == 0 -> DownloadQueueState.Stopped
                    mangaRunning || animeRunning -> DownloadQueueState.Downloading(pending)
                    else -> DownloadQueueState.Paused(pending)
                }
            }.collect { state ->
                _downloadQueueState.value = state
            }
        }
    }

}

sealed interface DownloadQueueState {
    data object Stopped : DownloadQueueState
    data class Paused(val pending: Int) : DownloadQueueState
    data class Downloading(val pending: Int) : DownloadQueueState
}
