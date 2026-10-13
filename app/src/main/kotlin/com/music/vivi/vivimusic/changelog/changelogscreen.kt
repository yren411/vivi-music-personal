package com.music.vivi.vivimusic.changelog

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.music.vivi.R
import com.music.vivi.ui.component.IconButton
import com.music.vivi.ui.utils.backToMain
import com.music.vivi.vivimusic.updater.GitHubReleaseSource
import com.music.vivi.vivimusic.updater.openHttpsInBrowser

/** Release history is maintained on GitHub; the updater itself renders the current release body. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangelogScreen(navController: NavController, scrollBehavior: TopAppBarScrollBehavior) {
    val context = LocalContext.current
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.tinium_update_history)) },
        navigationIcon = { IconButton(onClick = navController::navigateUp, onLongClick = navController::backToMain) {
            Icon(painterResource(R.drawable.arrow_back), stringResource(R.string.tinium_update_back))
        } }, scrollBehavior = scrollBehavior) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(24.dp)) {
            Text(stringResource(R.string.tinium_update_history_open), style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(24.dp))
            Button(onClick = { openHttpsInBrowser(context, "https://github.com/${GitHubReleaseSource.REPOSITORY}/releases") }) {
                Text(stringResource(R.string.tinium_update_history_open))
            }
        }
    }
}
