package com.music.vivi.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.content.Intent
import android.net.Uri
import androidx.navigation.NavController
import com.music.vivi.LocalPlayerAwareWindowInsets
import com.music.vivi.R
import com.music.vivi.ui.component.IconButton
import com.music.vivi.ui.component.ExpressiveSettingGroup
import com.music.vivi.ui.component.Material3SettingsItem
import com.music.vivi.ui.utils.backToMain
import com.music.vivi.vivimusic.updater.getAutoUpdateCheckSetting
import com.music.vivi.vivimusic.updater.saveAutoUpdateCheckSetting
import com.music.vivi.vivimusic.updater.getUpdateAvailableState
import android.widget.Toast
import androidx.compose.ui.res.pluralStringResource
import com.music.vivi.vivimusic.updater.getBetaUpdatesSetting
import com.music.vivi.vivimusic.updater.saveBetaUpdatesSetting
import androidx.compose.material3.MaterialTheme
import com.music.vivi.vivimusic.updater.TiniumUpdater
import androidx.compose.runtime.collectAsState
import com.music.vivi.vivimusic.updater.UpdateStatus
import com.music.vivi.BuildConfig
import com.music.vivi.utils.rememberPreference
import com.music.vivi.constants.EnableNotificationsKey
import android.os.Build
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import android.Manifest
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.Lifecycle



/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */




@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateSettings(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior
) {
    val context = LocalContext.current
    var autoUpdateEnabled by remember { mutableStateOf(getAutoUpdateCheckSetting(context)) }
    var betaUpdatesEnabled by remember { mutableStateOf(getBetaUpdatesSetting(context)) }
    var isUpdateAvailable by remember { mutableStateOf(getUpdateAvailableState(context)) }
    val checker = remember { TiniumUpdater.get(context) }
    val status by checker.state.collectAsState()

    // Detect system permission state dynamically
    var hasSystemPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            } else true
        )
    }

    // Refresh system permission state when screen is resumed
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasSystemPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED
                } else true
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val (notificationsEnabled, _) = rememberPreference(
        EnableNotificationsKey,
        defaultValue = true
    )

    val isNotificationsActive = hasSystemPermission && notificationsEnabled

    DisposableEffect(context) {
        val sharedPrefs = context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "update_available") {
                isUpdateAvailable = getUpdateAvailableState(context)
            }
        }
        sharedPrefs.registerOnSharedPreferenceChangeListener(listener)
        // Sync initial state
        isUpdateAvailable = getUpdateAvailableState(context)
        onDispose {
            sharedPrefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    LaunchedEffect(Unit) { TiniumUpdater.automaticCheck(context) }

    Column(
        Modifier
            .windowInsetsPadding(LocalPlayerAwareWindowInsets.current)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.update_settings_title),
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(8.dp))

        Spacer(modifier = Modifier.height(10.dp))
        ExpressiveSettingGroup(
            items = listOf(
                Material3SettingsItem(
                    icon = painterResource(R.drawable.network_update),
                    title = { Text(stringResource(R.string.system_update)) },
                    trailingContent = {
                        if (isUpdateAvailable) {
                            Text(
                                text = stringResource(R.string.update_available),
                                color = MaterialTheme.colorScheme.error
                            )
                        } else {
                            Text(stringResource(if (status == UpdateStatus.UpToDate) R.string.tinium_update_latest else R.string.tinium_update_settings_status))
                        }
                    },
                    onClick = {
                        navController.navigate("update")
                    }
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.info),
                    title = { Text(stringResource(R.string.app_version)) },
                    trailingContent = { Text(checker.installed.name) }
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.stockpot_flavour),
                    title = { Text(stringResource(R.string.flavour)) },
                    trailingContent = {
                        val variant = if (BuildConfig.CAST_AVAILABLE) "GMS" else "FOSS"
                        Text(variant)
                    }
                )
            )
        )

        Spacer(modifier = Modifier.height(10.dp))
        
        ExpressiveSettingGroup(
            items = listOf(
                Material3SettingsItem(
                    icon = painterResource(R.drawable.update),
                    title = { Text(stringResource(R.string.auto_update_check)) },
                    trailingContent = {
                        Switch(
                            checked = autoUpdateEnabled,
                            onCheckedChange = { enabled ->
                                autoUpdateEnabled = enabled
                                saveAutoUpdateCheckSetting(context, enabled)
                            },
                            thumbContent = {
                                Icon(
                                    painter = painterResource(
                                        id = if (autoUpdateEnabled) R.drawable.check else R.drawable.close
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize)
                                )
                            }
                        )
                    },
                    onClick = {
                        autoUpdateEnabled = !autoUpdateEnabled
                        saveAutoUpdateCheckSetting(context, autoUpdateEnabled)
                    }
                ),

                Material3SettingsItem(
                    icon = painterResource(R.drawable.biotech),
                    title = { Text(stringResource(R.string.beta_updates)) },
                    trailingContent = {
                        Switch(
                            checked = betaUpdatesEnabled,
                            onCheckedChange = { enabled ->
                                betaUpdatesEnabled = enabled
                                saveBetaUpdatesSetting(context, enabled)
                            },
                            thumbContent = {
                                Icon(
                                    painter = painterResource(
                                        id = if (betaUpdatesEnabled) R.drawable.check else R.drawable.close
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize)
                                )
                            }
                        )
                    },
                    onClick = {
                        betaUpdatesEnabled = !betaUpdatesEnabled
                        saveBetaUpdatesSetting(context, betaUpdatesEnabled)
                    }
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.notification),
                    title = { Text(stringResource(R.string.notification_settings)) },
                    trailingContent = {
                        val status = if (isNotificationsActive) {
                            stringResource(R.string.enabled)
                        } else {
                            stringResource(R.string.disabled)
                        }
                        Text(status)
                    },
                    onClick = {
                        navController.navigate("settings/update/notification_permission")
                    }
                )
            )
        )

        Spacer(modifier = Modifier.height(10.dp))

        ExpressiveSettingGroup(
            items = listOf(
                Material3SettingsItem(
                    icon = painterResource(R.drawable.history),
                    title = { Text(stringResource(R.string.changelog)) },
                    description = { Text(stringResource(R.string.view_version_history)) },
                    onClick = { navController.navigate("settings/changelog") }
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.commit),
                    title = { Text(stringResource(R.string.commits)) },
                    description = { Text(stringResource(R.string.view_commit_history)) },
                    onClick = { navController.navigate("settings/commits") }
                )
            )
        )

        Spacer(modifier = Modifier.height(16.dp))
    }

    TopAppBar(
        title = {
//            Text(stringResource(R.string.update_settings_title))
        },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain
            ) {
                Icon(
                    painterResource(R.drawable.arrow_back),
                    contentDescription = null
                )
            }
        }
    )
}
