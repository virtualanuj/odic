package com.urlinspector.app

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.urlinspector.app.history.HistoryScreen
import com.urlinspector.app.history.HistoryViewModel
import com.urlinspector.app.scan.PasteScreen
import com.urlinspector.app.scan.ScanUiState
import com.urlinspector.app.scan.ScanViewModel
import com.urlinspector.app.scan.VerdictScreen
import org.koin.androidx.compose.koinViewModel

private const val ROUTE_PASTE = "paste"
private const val ROUTE_VERDICT = "verdict"
private const val ROUTE_HISTORY = "history"
private const val ROUTE_SETTINGS = "settings"

@Composable
fun AppNavHost(
    onOpenLink: (String) -> Unit,
    navController: NavHostController = rememberNavController(),
    scanViewModel: ScanViewModel = koinViewModel(),
    sharedUrl: String? = null,
    prefillText: String? = null,
) {
    val uiState by scanViewModel.uiState.collectAsState()

    var didAutoScan by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(sharedUrl) {
        if (sharedUrl != null && !didAutoScan) {
            didAutoScan = true
            scanViewModel.scan(sharedUrl)
        }
    }

    NavHost(navController = navController, startDestination = ROUTE_PASTE) {
        composable(ROUTE_PASTE) {
            PasteScreen(
                uiState = uiState,
                onScan = { url -> scanViewModel.scan(url) },
                onOpenHistory = { navController.navigate(ROUTE_HISTORY) },
                onOpenSettings = { navController.navigate(ROUTE_SETTINGS) },
                initialText = prefillText ?: "",
            )
            LaunchedEffect(uiState) {
                if (uiState is ScanUiState.Success) {
                    navController.navigate(ROUTE_VERDICT)
                }
            }
        }
        composable(ROUTE_VERDICT) {
            val state = uiState
            if (state is ScanUiState.Success) {
                val onVerdictBack: () -> Unit = {
                    scanViewModel.reset()
                    navController.popBackStack(ROUTE_PASTE, inclusive = false)
                }
                BackHandler(onBack = onVerdictBack)
                VerdictScreen(
                    result = state.result,
                    onBack = onVerdictBack,
                    onOpenLink = onOpenLink,
                )
            } else {
                // Scan state does not survive process death; if this route is
                // restored without a live result, fall back to the paste screen
                // instead of rendering a blank screen.
                LaunchedEffect(Unit) {
                    navController.popBackStack(ROUTE_PASTE, inclusive = false)
                }
            }
        }
        composable(ROUTE_HISTORY) {
            val historyViewModel: HistoryViewModel = koinViewModel()
            val entries by historyViewModel.entries.collectAsState()
            HistoryScreen(
                entries = entries,
                onDelete = historyViewModel::delete,
                onClearAll = historyViewModel::clearAll,
                onBack = { navController.popBackStack() },
            )
        }
        composable(ROUTE_SETTINGS) {
            com.urlinspector.app.settings.SettingsScreen(
                onBack = { navController.popBackStack() },
            )
        }
    }
}
