package moe.rukamori.archivetune.shared

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.painterResource
import moe.rukamori.archivetune.shared.resources.Res
import moe.rukamori.archivetune.shared.resources.about_appbar
import moe.rukamori.archivetune.shared.resources.language
import moe.rukamori.archivetune.shared.resources.search

/** Android MainActivity's shell title. Callers provide only actions that have working destinations. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AndroidShellHeader(
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        modifier = modifier,
        windowInsets = WindowInsets(0, 0, 0, 0),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(Res.drawable.about_appbar), contentDescription = null,
                    modifier = Modifier.size(35.dp).padding(end = 3.dp))
                Text("ArchiveTune", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        actions = actions,
    )
}

/** Search discovery entry field from Android SearchScreen, opening the actual search flow. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AndroidSearchEntry(onClick: () -> Unit, modifier: Modifier = Modifier) {
    SearchBar(
        inputField = {
            SearchBarDefaults.InputField(
                query = "", onQueryChange = { onClick() }, onSearch = { onClick() },
                expanded = false, onExpandedChange = { if (it) onClick() },
                placeholder = { Text("Search YouTube Music", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { Icon(painterResource(Res.drawable.search), contentDescription = null) },
                trailingIcon = { Icon(painterResource(Res.drawable.language), contentDescription = null) },
            )
        },
        expanded = false, onExpandedChange = { if (it) onClick() },
        windowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
        modifier = modifier.fillMaxWidth(),
    ) {}
}
