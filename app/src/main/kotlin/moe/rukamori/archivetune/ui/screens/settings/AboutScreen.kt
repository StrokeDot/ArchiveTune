/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package moe.rukamori.archivetune.ui.screens.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.chrisbanes.haze.hazeSource
import moe.rukamori.archivetune.LocalPlayerAwareWindowInsets
import moe.rukamori.archivetune.LocalStableSystemBarsTopPadding
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.displayVersionName
import moe.rukamori.archivetune.ui.component.KeepStatusBarHiddenInDialog
import moe.rukamori.archivetune.ui.component.SettingsPageTopBar
import moe.rukamori.archivetune.ui.screens.ScreenHeaderHaze
import moe.rukamori.archivetune.ui.screens.rememberScreenHeaderHaze
import moe.rukamori.archivetune.ui.screens.settings.about.LocalSukisuBgRenderActive
import moe.rukamori.archivetune.ui.screens.settings.about.SukisuAboutBackground
import moe.rukamori.archivetune.ui.utils.backToMain
import moe.rukamori.archivetune.viewmodels.AboutDependencyLicensesUiState
import moe.rukamori.archivetune.viewmodels.AboutViewModel

private const val OriginalArchiveTuneUrl = "https://github.com/4nx3b/ArchiveTune"
private const val MusicLicenseUrl = "https://github.com/StrokeDot/ArchiveTune/blob/personal/vinyl/LICENSE"

@Composable
fun AboutScreen(
    navController: NavController,
    viewModel: AboutViewModel = hiltViewModel(),
) {
    val uriHandler = LocalUriHandler.current
    val playerAwareInsets = LocalPlayerAwareWindowInsets.current
    val bottomPadding = playerAwareInsets.asPaddingValues().calculateBottomPadding()
    val statusBarTop = LocalStableSystemBarsTopPadding.current
    val headerHaze = rememberScreenHeaderHaze()
    val navTransitionRenderActive = LocalSukisuBgRenderActive.current
    val listState = rememberLazyListState()
    var heroHeight by remember { mutableIntStateOf(0) }
    var showLicenses by rememberSaveable { mutableStateOf(false) }
    val backgroundAlpha by remember {
        derivedStateOf {
            when {
                listState.firstVisibleItemIndex > 0 -> 0f
                heroHeight == 0 -> 1f
                else -> ((1f - listState.firstVisibleItemScrollOffset.toFloat() / heroHeight) / 0.75f)
                    .coerceIn(0f, 1f)
            }
        }
    }

    Scaffold(
        modifier =
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(playerAwareInsets.only(WindowInsetsSides.Horizontal)),
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            SettingsPageTopBar(
                titleText = stringResource(R.string.about),
                onBack = navController::navigateUp,
                onBackLongClick = navController::backToMain,
            )
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().hazeSource(headerHaze)) {
            SukisuAboutBackground(
                modifier = Modifier.fillMaxSize(),
                alpha = { backgroundAlpha },
                renderActive = { navTransitionRenderActive() && backgroundAlpha > 0.01f },
                surface = MaterialTheme.colorScheme.surface,
            ) { }

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding =
                    PaddingValues(
                        start = SettingsDimensions.ScreenHorizontalPadding,
                        top = innerPadding.calculateTopPadding() + 24.dp,
                        end = SettingsDimensions.ScreenHorizontalPadding,
                        bottom = bottomPadding + SettingsDimensions.ScreenBottomPadding,
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                item(key = "music") {
                    Column(
                        modifier =
                            Modifier
                                .widthIn(max = 560.dp)
                                .fillMaxWidth()
                                .onSizeChanged { heroHeight = it.height },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Image(
                            painter = painterResource(R.drawable.app_icon_small),
                            contentDescription = null,
                            modifier = Modifier.size(88.dp).clip(CircleShape),
                        )
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            text = stringResource(R.string.music_about_version, displayVersionName()),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = stringResource(R.string.music_about_fork_credit),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
                item(key = "links") {
                    Card(modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
                        AboutLink(
                            label = stringResource(R.string.music_about_original_github),
                            icon = R.drawable.github,
                            onClick = { uriHandler.openUri(OriginalArchiveTuneUrl) },
                        )
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        AboutLink(
                            label = stringResource(R.string.music_about_licenses),
                            icon = R.drawable.info,
                            onClick = { showLicenses = true },
                        )
                    }
                }
            }
        }

        ScreenHeaderHaze(
            hazeState = headerHaze,
            systemBarsTopPadding = statusBarTop,
            scrolled = listState.canScrollBackward,
        )
    }

    if (showLicenses) {
        val licenses by viewModel.dependencyLicenses.collectAsStateWithLifecycle()
        LaunchedEffect(viewModel) {
            viewModel.loadDependencyLicenses()
        }
        AboutLicensesDialog(
            state = licenses,
            onDismiss = { showLicenses = false },
            onOpenAppLicense = { uriHandler.openUri(MusicLicenseUrl) },
            onRetry = viewModel::loadDependencyLicenses,
        )
    }
}

@Composable
private fun AboutLink(
    label: String,
    icon: Int,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = { Icon(painterResource(icon), contentDescription = null) },
        trailingContent = { Icon(painterResource(R.drawable.arrow_forward), contentDescription = null) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun AboutLicensesDialog(
    state: AboutDependencyLicensesUiState,
    onDismiss: () -> Unit,
    onOpenAppLicense: () -> Unit,
    onRetry: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        KeepStatusBarHiddenInDialog()
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.music_about_licenses)) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(
                                painter = painterResource(R.drawable.close),
                                contentDescription = stringResource(R.string.close_dialog),
                            )
                        }
                    },
                )
            },
        ) { innerPadding ->
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)),
                contentAlignment = Alignment.TopCenter,
            ) {
                LazyColumn(modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth()) {
                    item(key = "app_license") {
                        AboutLink(
                            label = stringResource(R.string.music_about_app_license),
                            icon = R.drawable.github,
                            onClick = onOpenAppLicense,
                        )
                        HorizontalDivider()
                    }
                    when (state) {
                        AboutDependencyLicensesUiState.Loading -> item {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(24.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                LoadingIndicator()
                            }
                        }

                        AboutDependencyLicensesUiState.Error -> item {
                            ListItem(
                                headlineContent = { Text(stringResource(R.string.error_unknown)) },
                                trailingContent = {
                                    TextButton(onClick = onRetry) {
                                        Text(stringResource(R.string.retry))
                                    }
                                },
                            )
                        }

                        is AboutDependencyLicensesUiState.Success -> {
                            items(count = state.licenses.size) { index ->
                                val dependency = state.licenses[index]
                                ListItem(
                                    headlineContent = { Text(dependency.name) },
                                    overlineContent = dependency.version?.let { version -> { Text(version) } },
                                    supportingContent = {
                                        Text(dependency.licenses ?: stringResource(R.string.about_license_unknown))
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
