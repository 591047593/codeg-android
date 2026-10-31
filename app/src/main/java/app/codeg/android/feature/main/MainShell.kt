package app.codeg.android.feature.main

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.NavGraph.Companion.findStartDestination
import app.codeg.android.R
import app.codeg.android.app.AppViewModel
import app.codeg.android.core.datastore.ServerProfile
import app.codeg.android.core.designsystem.theme.CodegTheme
import app.codeg.android.feature.projects.ProjectDetailScreen
import app.codeg.android.feature.search.SearchScreen
import app.codeg.android.feature.projects.ProjectListScreen
import app.codeg.android.feature.server.ServerEditorScreen
import app.codeg.android.feature.settings.SettingsScreen
import app.codeg.android.feature.server.ServerListScreen
import app.codeg.android.feature.sessiondetail.SessionDetailScreen
import app.codeg.android.feature.sessions.SessionListScreen

/**
 * Bottom-navigation destinations. Each carries an outlined icon (unselected) and
 * a filled icon (selected) — the Material 3 / GitHub / ChatGPT convention where
 * the active tab fills in.
 */
enum class HomeTab(
    val route: String,
    @StringRes val label: Int,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
) {
    CHATS("chats", R.string.tab_chats, Icons.Outlined.Forum, Icons.Rounded.Forum),
    FOLDERS("folders", R.string.tab_folders, Icons.Outlined.FolderOpen, Icons.Rounded.FolderOpen),
    // "Activity" is gone as a destination: it rendered the same rows over the same
    // conversations as Chats, differing only in grouping. Both groups (Running, last 24
    // hours) now sit at the top of the Chats list, so the signal survives without a tab
    // that duplicated the screen next to it.
    SEARCH("search", R.string.tab_search, Icons.Outlined.Search, Icons.Rounded.Search),
    SETTINGS("settings", R.string.tab_settings, Icons.Outlined.Settings, Icons.Rounded.Settings),
}

private const val ROUTE_SERVERS = "servers"
private const val ROUTE_EDITOR = "editor"
private const val ROUTE_NEW_TASK = "conversation_new"

/**
 * Whether the Chats screen's New Task FAB is expanded.
 *
 * The bottom bar used to slide away on scroll and this carried its state down so the
 * FAB could collapse in lockstep. The bar no longer hides (see [MainShell]), so the FAB
 * is always expanded; the local is kept rather than threading a literal through the
 * screen's signature, and would carry a future collapse animation if one is added.
 */
val LocalBarsVisible = compositionLocalOf { true }

/**
 * The main app shell once a server exists: a Material 3 bottom navigation bar
 * over a single [NavHost]. Tab destinations show the bar; full-screen
 * destinations (session detail, server management) hide it. Mirrors the iOS
 * compact-size `TabView`, with session detail / server management pushed on top.
 */
@Composable
fun MainShell(appViewModel: AppViewModel, servers: List<ServerProfile>) {
    val nav = rememberNavController()
    val backStackEntry by nav.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = HomeTab.entries.any { it.route == currentRoute }
    val tabRoutes = remember { HomeTab.entries.map { it.route }.toSet() }
    val selectedProfile by appViewModel.selectedProfile.collectAsStateWithLifecycle()
    // Wide screens (tablets / landscape) use a side NavigationRail instead of the bottom bar.
    val wide = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 720

    // Scroll-driven bottom bar: scrolling up (reading further down the list) slides
    // the bar off the bottom edge and collapses the New Task FAB to just "+";
    // scrolling back down slides it in and restores the label. The bar lives in this
    // shared Scaffold while the scroll happens in each tab's LazyColumn, so a
    // NestedScrollConnection on the content picks up every tab's scroll for free.
    // The bottom bar is always visible.
    //
    // It used to slide away when the list scrolled and come back on a downward scroll.
    // On a phone that read as a bug rather than a courtesy: scrolling to the end of a
    // long list hid the bar and it did not come back, so there was no way to switch
    // tabs without first scrolling the other way. Material's own guidance is that
    // navigation should not be hidden by scrolling; the vertical space it frees is not
    // worth a control the user has to hunt for.
    val barsVisible = true

    val onTab: (HomeTab) -> Unit = { tab ->
        nav.navigate(tab.route) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    // Handle incoming codeg:// deep links.
    val pendingLink by app.codeg.android.core.common.DeepLinkBus.pending.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(pendingLink) {
        val route = pendingLink ?: return@LaunchedEffect
        fun toTab(it: HomeTab) = nav.navigate(it.route) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true; restoreState = true
        }
        when (route) {
            is app.codeg.android.core.common.DeepLinkRoute.OpenTab -> when (route.tab) {
                "chats" -> toTab(HomeTab.CHATS)
                "projects", "folders" -> toTab(HomeTab.FOLDERS)
                // Kept as an alias: an old link must still land somewhere sensible
                // rather than failing, and the data it pointed at lives in Chats now.
                "activity" -> toTab(HomeTab.CHATS)
                "search" -> toTab(HomeTab.SEARCH)
                "settings" -> toTab(HomeTab.SETTINGS)
                else -> {}
            }
            is app.codeg.android.core.common.DeepLinkRoute.OpenConversation ->
                nav.navigate("conversation/${route.id}") { launchSingleTop = true }
            is app.codeg.android.core.common.DeepLinkRoute.OpenProject -> nav.navigate("project/${route.id}")
            is app.codeg.android.core.common.DeepLinkRoute.OpenSettings -> {
                // Navigate to the tab but leave the link pending: SettingsScreen picks
                // the slug up and opens the matching leaf, then consumes it. Consuming
                // here would drop the slug on the floor (the old behaviour).
                toTab(HomeTab.SETTINGS)
                return@LaunchedEffect
            }
        }
        app.codeg.android.core.common.DeepLinkBus.consume(route)
    }

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showBottomBar && !wide) {
                CodegBottomBar(currentRoute, onTab)
            }
        },
    ) { padding ->
        androidx.compose.foundation.layout.Row(
            Modifier.padding(padding).fillMaxSize(),
        ) {
            if (showBottomBar && wide) CodegNavRail(currentRoute, onTab)
            NavHost(
                navController = nav,
                startDestination = HomeTab.CHATS.route,
                modifier = Modifier.weight(1f).fillMaxHeight(),
                // Material motion: lateral tab switches fade through; pushing a
                // detail/editor slides in along the X axis (and back reverses it).
                enterTransition = {
                    val lateral = initialState.destination.route in tabRoutes &&
                        targetState.destination.route in tabRoutes
                    if (lateral) fadeIn(tween(190))
                    else slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Left, tween(300)) +
                        fadeIn(tween(300))
                },
                exitTransition = {
                    val lateral = initialState.destination.route in tabRoutes &&
                        targetState.destination.route in tabRoutes
                    if (lateral) fadeOut(tween(190))
                    else slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Left, tween(300)) +
                        fadeOut(tween(300))
                },
                popEnterTransition = {
                    slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Right, tween(300)) +
                        fadeIn(tween(300))
                },
                popExitTransition = {
                    slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Right, tween(300)) +
                        fadeOut(tween(300))
                },
            ) {
            composable(HomeTab.CHATS.route) {
                CompositionLocalProvider(LocalBarsVisible provides barsVisible) {
                    SessionListScreen(
                        servers = servers,
                        selectedId = selectedProfile?.id,
                        onSelectServer = appViewModel::selectServer,
                        onManageServers = { nav.navigate(ROUTE_SERVERS) },
                        onOpenConversation = { id ->
                            // launchSingleTop: a double tap on a row used to push the same
                            // destination twice, so Back landed on an identical screen.
                            nav.navigate("conversation/$id") { launchSingleTop = true }
                        },
                        onNewTask = { nav.navigate(ROUTE_NEW_TASK) },
                        onOpenFolder = { id -> nav.navigate("project/$id") },
                        onOpenFolders = { onTab(HomeTab.FOLDERS) },
                        onOpenSessionInFolder = { folderId ->
                            nav.navigate("$ROUTE_NEW_TASK?folderId=$folderId")
                        },
                    )
                }
            }
            composable(HomeTab.FOLDERS.route) {
                ProjectListScreen(onOpenFolder = { id -> nav.navigate("project/$id") })
            }
            composable(HomeTab.SEARCH.route) {
                SearchScreen(onOpenConversation = { id -> nav.navigate("conversation/$id") { launchSingleTop = true } })
            }
            composable(HomeTab.SETTINGS.route) {
                SettingsScreen()
            }

            composable(
                route = "conversation/{id}",
                arguments = listOf(navArgument("id") { type = NavType.IntType }),
            ) {
                SessionDetailScreen(
                    onBack = { nav.popBackStack() },
                    onOpenSession = { folderId -> nav.navigate("$ROUTE_NEW_TASK?folderId=$folderId") },
                    onOpenFolders = { nav.navigate(HomeTab.FOLDERS.route) { launchSingleTop = true } },
                )
            }
            composable(
                route = "$ROUTE_NEW_TASK?folderId={folderId}",
                arguments = listOf(
                    navArgument("folderId") {
                        type = NavType.IntType
                        defaultValue = -1
                    },
                ),
            ) {
                SessionDetailScreen(
                    onBack = { nav.popBackStack() },
                    onOpenSession = { folderId -> nav.navigate("$ROUTE_NEW_TASK?folderId=$folderId") },
                    onOpenFolders = { nav.navigate(HomeTab.FOLDERS.route) { launchSingleTop = true } },
                )
            }

            composable(
                route = "project/{folderId}",
                arguments = listOf(navArgument("folderId") { type = NavType.IntType }),
            ) {
                ProjectDetailScreen(onBack = { nav.popBackStack() })
            }

            composable(ROUTE_SERVERS) {
                ServerListScreen(
                    servers = servers,
                    selectedId = selectedProfile?.id,
                    onSelect = appViewModel::selectServer,
                    onDelete = appViewModel::deleteServer,
                    onAdd = { nav.navigate(ROUTE_EDITOR) },
                    onEdit = { id -> nav.navigate("$ROUTE_EDITOR?serverId=$id") },
                    onBack = { nav.popBackStack() },
                )
            }
            composable(
                route = "$ROUTE_EDITOR?serverId={serverId}",
                arguments = listOf(
                    navArgument("serverId") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) {
                ServerEditorScreen(onDone = { nav.popBackStack() })
            }
            }
        }
    }
}

@Composable
private fun CodegNavRail(currentRoute: String?, onSelect: (HomeTab) -> Unit) {
    val colors = CodegTheme.colors
    // The wide-screen analogue of the bottom bar: the same frosted glass material,
    // delineated from the content by a trailing hairline rather than a top one.
    androidx.compose.foundation.layout.Row {
        androidx.compose.material3.NavigationRail(
            containerColor = Color.Transparent,
        ) {
            HomeTab.entries.forEach { tab ->
                val selected = currentRoute == tab.route
                androidx.compose.material3.NavigationRailItem(
                    selected = selected,
                    onClick = { onSelect(tab) },
                    icon = {
                        Icon(
                            if (selected) tab.selectedIcon else tab.icon,
                            contentDescription = stringResource(tab.label),
                        )
                    },
                    label = { Text(stringResource(tab.label)) },
                    alwaysShowLabel = true,
                    colors = androidx.compose.material3.NavigationRailItemDefaults.colors(
                        selectedIconColor = colors.accent,
                        selectedTextColor = colors.accent,
                        unselectedIconColor = colors.textTertiary,
                        unselectedTextColor = colors.textTertiary,
                        // Tint-only selection, matching the bottom bar.
                        indicatorColor = Color.Transparent,
                    ),
                )
            }
        }
        androidx.compose.material3.VerticalDivider(
            thickness = androidx.compose.ui.unit.Dp.Hairline,
            color = colors.surfaceStroke,
        )
    }
}

@Composable
private fun CodegBottomBar(
    currentRoute: String?,
    onSelect: (HomeTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CodegTheme.colors
    androidx.compose.foundation.layout.Column(modifier) {
        // iOS tab bars are a translucent material with a hairline on top; the content
        // scrolls *under* them rather than stopping above. A translucent elevated fill
        // plus that hairline is the whole effect — no blur pass, which a scrolling
        // LazyColumn behind it would make expensive on a phone.
        HorizontalDivider(thickness = androidx.compose.ui.unit.Dp.Hairline, color = colors.hairline)
        NavigationBar(
            // The page colour, not a lifted surface: in the Codex client the bar and the
            // list are one plane, divided only by the hairline. `bgElevated` at 94% made
            // a faintly brighter strip that read as a separate shelf.
            containerColor = colors.bg.copy(alpha = 0.96f),
            tonalElevation = 0.dp,
            // Compact: the default 80dp is a Material tablet height. 64dp keeps the
            // touch target legal while giving the list two more rows of breathing room.
            modifier = Modifier.height(64.dp),
        ) {
            HomeTab.entries.forEach { tab ->
                val selected = currentRoute == tab.route
                NavigationBarItem(
                    selected = selected,
                    onClick = { onSelect(tab) },
                    icon = {
                        Icon(
                            if (selected) tab.selectedIcon else tab.icon,
                            contentDescription = stringResource(tab.label),
                        )
                    },
                    label = {
                        Text(
                            stringResource(tab.label),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                    alwaysShowLabel = true,
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = colors.accent,
                        selectedTextColor = colors.accent,
                        unselectedIconColor = colors.textTertiary,
                        unselectedTextColor = colors.textTertiary,
                        // iOS marks the selected tab by tint alone — the filled pill
                        // behind the icon is Material's idiom, and it is the single
                        // detail that makes a bottom bar read as "Android app".
                        indicatorColor = Color.Transparent,
                    ),
                )
            }
        }
    }
}
