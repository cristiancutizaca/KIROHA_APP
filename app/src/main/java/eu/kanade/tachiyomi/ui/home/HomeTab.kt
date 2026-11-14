package eu.kanade.tachiyomi.ui.home

import androidx.activity.compose.LocalActivity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.presentation.home.HomeFeedScreen
import eu.kanade.presentation.util.Tab
import kotlinx.coroutines.channels.Channel
import mihon.app.di.appGraph
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

data object HomeTab : Tab {
    private fun readResolve(): Any = HomeTab

    val openSettingsSheetEvent = Channel<Unit>()

    suspend fun requestOpenSettingsSheet() {
        openSettingsSheetEvent.send(Unit)
    }

    override val options: TabOptions
        @Composable
        get() {
            return TabOptions(
                index = 0u,
                title = stringResource(MR.strings.label_home),
                icon = rememberVectorPainter(Icons.Outlined.Home),
            )
        }

    @Composable
    override fun isEnabled(): Boolean {
        val context = LocalContext.current
        val uiPreferences = remember { context.appGraph.uiPreferences }
        val showHomeTab by uiPreferences.showHomeTab.collectAsState()
        return showHomeTab
    }

    override suspend fun onReselect(navigator: Navigator) {
        requestOpenSettingsSheet()
    }

    @Composable
    override fun Content() {
        val owner = LocalActivity.current as? ViewModelStoreOwner
        if (owner == null) {
            HomeFeedScreen()
        } else {
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                HomeFeedScreen()
            }
        }
    }
}
