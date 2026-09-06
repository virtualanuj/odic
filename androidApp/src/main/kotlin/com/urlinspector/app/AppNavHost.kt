package com.urlinspector.app

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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

@Composable
fun AppNavHost(
    onOpenLink: (String) -> Unit,
    navController: NavHostController = rememberNavController(),
    scanViewModel: ScanViewModel = koinViewModel(),
) {
    val uiState by scanViewModel.uiState.collectAsState()

    NavHost(navController = navController, startDestination = ROUTE_PASTE) {
        composable(ROUTE_PASTE) {
            PasteScreen(
                uiState = uiState,
                onScan = { url -> scanViewModel.scan(url) },
                onOpenHistory = { navController.navigate(ROUTE_HISTORY) },
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
    }
}
