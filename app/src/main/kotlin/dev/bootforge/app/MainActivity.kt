package dev.bootforge.app

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.bootforge.app.ui.AppViewModel
import dev.bootforge.app.ui.screens.DriveDetailScreen
import dev.bootforge.app.ui.screens.DrivesScreen
import dev.bootforge.app.ui.screens.HistoryScreen
import dev.bootforge.app.ui.screens.HomeScreen
import dev.bootforge.app.ui.screens.IsoWriterScreen
import dev.bootforge.app.ui.screens.IsoEditorScreen
import dev.bootforge.app.ui.IsoEditorViewModel
import dev.bootforge.app.ui.IsoWriterViewModel
import dev.bootforge.app.ui.screens.OperationOverlay
import dev.bootforge.app.ui.screens.SettingsScreen
import dev.bootforge.app.ui.screens.VentoyInstallScreen
import dev.bootforge.app.ui.VentoyViewModel
import dev.bootforge.app.ui.screens.ventoy.VentoyHubScreen
import dev.bootforge.app.ui.theme.BfColors
import dev.bootforge.app.ui.theme.BootForgeTheme

/** Rotas de navegação do app. Nomes de dispositivo contêm "/", por isso são codificados. */
object Routes {
    const val HOME = "home"
    const val DRIVES = "drives"
    const val DRIVE = "drive/{id}"
    const val VENTOY = "ventoy"
    const val ISO_WRITER = "iso-writer"
    const val ISO_EDITOR = "iso-editor"
    const val VENTOY_INSTALL = "ventoy/install/{id}"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
    fun drive(id: String) = "drive/${Uri.encode(id)}"
    fun ventoyInstall(id: String) = "ventoy/install/${Uri.encode(id)}"
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BootForgeTheme { BootForgeApp() }
        }
    }
}

@Composable
fun BootForgeApp(vm: AppViewModel = viewModel()) {
    val nav = rememberNavController()
    val operation by vm.operation.collectAsStateWithLifecycle()
    // Android 13+: pede permissão para mostrar a notificação de progresso das operações longas.
    val notifPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT >= 33) notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }
    Box(Modifier.fillMaxSize().background(BfColors.Background).safeDrawingPadding()) {
        NavHost(nav, startDestination = Routes.HOME) {
            composable(Routes.HOME) { HomeScreen(vm, onNavigate = { nav.navigate(it) }) }
            composable(Routes.DRIVES) {
                DrivesScreen(vm, onBack = { nav.popBackStack() }, onOpen = { nav.navigate(Routes.drive(it)) })
            }
            composable(Routes.DRIVE) { entry ->
                val id = Uri.decode(entry.arguments?.getString("id").orEmpty())
                DriveDetailScreen(vm, id, onBack = { nav.popBackStack() }, onVentoy = { nav.navigate(Routes.VENTOY) })
            }
            composable(Routes.VENTOY) {
                VentoyHubScreen(
                    viewModel<VentoyViewModel>(),
                    onBack = { nav.popBackStack() },
                    onInstall = { nav.navigate(Routes.ventoyInstall(it)) },
                    onDeviceDetails = { nav.navigate(Routes.drive(it)) },
                )
            }
            composable(Routes.VENTOY_INSTALL) { entry ->
                val id = Uri.decode(entry.arguments?.getString("id").orEmpty())
                VentoyInstallScreen(vm, id, onBack = { nav.popBackStack() })
            }
            composable(Routes.ISO_WRITER) {
                IsoWriterScreen(vm, viewModel<IsoWriterViewModel>(), onBack = { nav.popBackStack() })
            }
            composable(Routes.ISO_EDITOR) {
                IsoEditorScreen(viewModel<IsoEditorViewModel>(), onBack = { nav.popBackStack() })
            }
            composable(Routes.HISTORY) { HistoryScreen(vm, onBack = { nav.popBackStack() }) }
            composable(Routes.SETTINGS) { SettingsScreen(vm, onBack = { nav.popBackStack() }) }
        }
        // Painel de progresso por cima de qualquer tela enquanto houver operação.
        operation?.let { OperationOverlay(vm, it) }
    }
}
