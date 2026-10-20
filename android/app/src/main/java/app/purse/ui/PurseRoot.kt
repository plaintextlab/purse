package app.purse.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.purse.data.Settings
import app.purse.ui.accounts.AccountEditScreen
import app.purse.ui.accounts.AccountsScreen
import app.purse.ui.calendar.CalendarScreen
import app.purse.ui.home.DashboardScreen
import app.purse.ui.settings.CategoriesScreen
import app.purse.ui.settings.SettingsScreen
import app.purse.ui.settings.SmsScanScreen
import app.purse.ui.settings.SmsScreen
import app.purse.ui.settings.SyncScreen
import app.purse.ui.settings.TemplateEditScreen
import app.purse.ui.txn.AddEditTxnScreen
import app.purse.ui.txn.TransactionsScreen
import kotlinx.coroutines.flow.MutableStateFlow

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("home", "Home", Icons.Filled.Home),
    Tab("txns", "Activity", Icons.AutoMirrored.Filled.List),
    Tab("calendar", "Calendar", Icons.Filled.DateRange),
    Tab("accounts", "Accounts", Icons.Filled.AccountBalance),
)

/** Routes: add?type=&id=&account=&date=, account?id=, template?id= */
fun NavHostController.addTxn(type: String? = null, id: String? = null, date: String? = null) {
    val args = listOfNotNull(type?.let { "type=$it" }, id?.let { "id=$it" }, date?.let { "date=$it" })
    navigate("add" + if (args.isEmpty()) "" else "?" + args.joinToString("&"))
}

@Composable
fun PurseRoot(settings: Settings, pendingRoute: MutableStateFlow<String?>) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val onTab = tabs.any { it.route == route }

    val pending by pendingRoute.collectAsStateWithLifecycle()
    LaunchedEffect(pending) {
        pending?.let {
            nav.navigate(it)
            pendingRoute.value = null
        }
    }

    CompositionLocalProvider(LocalMoney provides settings.moneyFormat()) {
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                if (onTab) {
                    NavigationBar {
                        tabs.forEach { tab ->
                            NavigationBarItem(
                                selected = route == tab.route,
                                onClick = {
                                    nav.navigate(tab.route) {
                                        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = { Icon(tab.icon, null) },
                                label = { Text(tab.label) },
                            )
                        }
                    }
                }
            },
            floatingActionButton = {
                if (onTab && route != "accounts") {
                    FloatingActionButton(onClick = { nav.addTxn() }) { Icon(Icons.Filled.Add, "Add transaction") }
                }
            },
        ) { padding ->
            NavHost(nav, startDestination = "home", modifier = Modifier.padding(padding)) {
                composable("home") { DashboardScreen(nav, settings) }
                composable("txns") { TransactionsScreen(nav) }
                composable("calendar") { CalendarScreen(nav) }
                composable("accounts") { AccountsScreen(nav) }
                composable(
                    "add?type={type}&id={id}&date={date}",
                    arguments = listOf(
                        navArgument("type") { type = NavType.StringType; nullable = true },
                        navArgument("id") { type = NavType.StringType; nullable = true },
                        navArgument("date") { type = NavType.StringType; nullable = true },
                    ),
                ) { e ->
                    AddEditTxnScreen(
                        nav = nav,
                        settings = settings,
                        initialType = e.arguments?.getString("type"),
                        txnId = e.arguments?.getString("id"),
                        initialDate = e.arguments?.getString("date"),
                    )
                }
                composable(
                    "account?id={id}",
                    arguments = listOf(navArgument("id") { type = NavType.StringType; nullable = true }),
                ) { e -> AccountEditScreen(nav, e.arguments?.getString("id")) }
                composable("settings") { SettingsScreen(nav, settings) }
                composable("categories") { CategoriesScreen(nav) }
                composable("sms") { SmsScreen(nav, settings) }
                composable(
                    "template?id={id}",
                    arguments = listOf(navArgument("id") { type = NavType.StringType; nullable = true }),
                ) { e -> TemplateEditScreen(nav, e.arguments?.getString("id")) }
                composable("smsscan") { SmsScanScreen(nav) }
                composable("sync") { SyncScreen(nav, settings) }
            }
        }
    }
}
