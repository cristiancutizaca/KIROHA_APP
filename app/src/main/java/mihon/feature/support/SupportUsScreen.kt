package mihon.feature.support

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import eu.kanade.presentation.util.Screen
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Information about the KIROHA project. The old upstream donation screen used
 * third-party payment URLs and claimed funding belonged to the active fork.
 * KIROHA does not solicit donations or misrepresent upstream contributors.
 */
class SupportUsScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val uriHandler = LocalUriHandler.current
        val repository = "https://github.com/cristiancutizaca/KIROHA_APP"

        Scaffold(
            topBar = {
                AppBar(
                    title = stringResource(MR.strings.kiroha_project_title),
                    navigateUp = navigator::pop,
                )
            },
        ) { contentPadding ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(contentPadding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(MR.strings.kiroha_project_tagline),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(MR.strings.kiroha_project_description),
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextPreferenceWidget(
                    title = stringResource(MR.strings.kiroha_project_source),
                    icon = Icons.Outlined.Code,
                    onPreferenceClick = { uriHandler.openUri(repository) },
                )
                TextPreferenceWidget(
                    title = stringResource(MR.strings.kiroha_project_issues),
                    icon = Icons.Outlined.BugReport,
                    onPreferenceClick = { uriHandler.openUri("$repository/issues") },
                )
            }
        }
    }
}
