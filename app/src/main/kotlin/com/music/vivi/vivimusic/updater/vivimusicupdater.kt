package com.music.vivi.vivimusic.updater

import android.text.format.Formatter
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.music.vivi.R
import com.music.vivi.ui.component.IconButton
import com.music.vivi.ui.component.ErrorSnackbar
import com.music.vivi.ui.utils.backToMain
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun UpdateScreen(navController: NavHostController) {
    val context = LocalContext.current
    val checker = remember { TiniumUpdater.get(context) }
    val status by checker.state.collectAsState()
    val lastSuccess by checker.lastSuccess.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val installed = checker.installed
    // Opening this screen explicitly checks even when automatic checking is disabled.
    LaunchedEffect(checker) { checker.check(force = true) }
    val update = (status as? UpdateStatus.Available)?.update
    LaunchedEffect(update?.versionCode) { if (update != null) checker.markSeen() }
    val blocks = remember(update?.notes) { noteBlocks(update?.notes.orEmpty()) }

    Scaffold(containerColor = MaterialTheme.colorScheme.background,
        modifier = Modifier.widthIn(max = 700.dp).fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { CenterAlignedTopAppBar(
            title = { Icon(painterResource(R.drawable.mobile_update), null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary) },
            navigationIcon = { IconButton(onClick = navController::navigateUp, onLongClick = navController::backToMain) {
                Icon(painterResource(R.drawable.arrow_back), stringResource(R.string.tinium_update_back))
            } }, scrollBehavior = scrollBehavior) },
        snackbarHost = { ErrorSnackbar(hostState = snackbar) },
        bottomBar = { Surface(color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxWidth().padding(WindowInsets.navigationBars.asPaddingValues()).padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (update != null) {
                    Button(onClick = {
                        if (!openUpdateInBrowser(context, update)) scope.launch { snackbar.showSnackbar(context.getString(R.string.tinium_update_browser_error)) }
                    }, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text(stringResource(R.string.tinium_update_download), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)) }
                    OutlinedButton(onClick = { navController.navigateUp() }, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text(stringResource(R.string.later), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)) }
                } else {
                    Button(onClick = { scope.launch { checker.check(force = true) } }, enabled = status != UpdateStatus.Checking,
                        modifier = Modifier.fillMaxWidth().height(56.dp)) {
                        Text(stringResource(if (status is UpdateStatus.Error) R.string.try_again else R.string.check_for_update), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                    }
                }
            }
        } }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Text(stringResource(when (status) {
                    UpdateStatus.Idle -> R.string.tinium_update_idle
                    UpdateStatus.Checking -> R.string.tinium_update_checking
                    UpdateStatus.UpToDate -> R.string.tinium_update_latest
                    UpdateStatus.NoPublishedReleases -> R.string.tinium_update_no_releases
                    is UpdateStatus.Available -> R.string.tinium_update_available
                    is UpdateStatus.Error -> R.string.tinium_update_error
                }), style = if (status == UpdateStatus.Checking || status is UpdateStatus.Error) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineLarge,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
            }
            if (status == UpdateStatus.Checking) item { LinearWavyProgressIndicator(Modifier.fillMaxWidth()) }
            if (update != null) {
                item {
                    Text(stringResource(R.string.tinium_update_release_version, releaseTitle(update.versionName)), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.tinium_update_size, Formatter.formatFileSize(context, update.asset.size)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (blocks.isEmpty()) item { Text(stringResource(R.string.tinium_update_no_notes)) }
                else items(blocks) { ReleaseNoteBlock(it) }
            } else {
                if (status is UpdateStatus.Error) item { Text(stringResource(when ((status as UpdateStatus.Error).problem) {
                    UpdateProblem.NETWORK -> R.string.tinium_update_network_error
                    UpdateProblem.RATE_LIMIT -> R.string.tinium_update_rate_limit
                    UpdateProblem.INVALID_RELEASE -> R.string.tinium_update_invalid_release
                    UpdateProblem.HTTP -> R.string.tinium_update_http_error
                }), color = MaterialTheme.colorScheme.error) }
                item {
                    Text(stringResource(R.string.tinium_update_installed_version, installed.name), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.tinium_update_build_version, installed.code), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(24.dp))
                    Text(stringResource(R.string.tinium_update_last_success), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    Text(if (lastSuccess > 0) DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(lastSuccess))
                        else stringResource(R.string.tinium_update_never_checked), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}
