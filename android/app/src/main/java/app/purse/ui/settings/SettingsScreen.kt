package app.purse.ui.settings

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.navigation.NavHostController
import app.purse.BuildConfig
import app.purse.MainActivity
import app.purse.R
import app.purse.core.Money
import app.purse.core.SyncCodec
import app.purse.data.DarkMode
import app.purse.data.Settings
import app.purse.ui.LocalContainer
import app.purse.ui.PurseScaffold
import app.purse.ui.accounts.SwitchRow
import app.purse.ui.theme.Palettes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(nav: NavHostController, settings: Settings) {
    val container = LocalContainer.current
    val context = LocalContext.current
    fun update(f: (Settings) -> Settings) {
        container.scope.launch { container.settings.update(f) }
    }
    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_LONG).show()

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            container.scope.launch {
                val result = runCatching {
                    val text = SyncCodec.encodeBundle(container.repo.snapshot())
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                    }
                }
                withContext(Dispatchers.Main) { toast(if (result.isSuccess) "Backup saved" else "Export failed: ${result.exceptionOrNull()?.message}") }
            }
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            container.scope.launch {
                val result = runCatching {
                    val text = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                    } ?: error("Could not read file")
                    container.repo.applyIncoming(SyncCodec.decodeBundle(text))
                }
                withContext(Dispatchers.Main) {
                    toast(result.fold({ "Imported $it records" }, { "Import failed: ${it.message}" }))
                }
            }
        }
    }

    PurseScaffold(title = "Settings", onBack = { nav.popBackStack() }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Header("Appearance")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val dark = settings.darkMode != DarkMode.LIGHT
                Palettes.all.forEach { f ->
                    ThemeSwatch(
                        name = f.name,
                        colors = (if (dark) f.dark else f.light ?: f.dark).let { listOf(it.bg, it.primary, it.red, it.green) },
                        selected = settings.theme == f.id,
                    ) { update { it.copy(theme = f.id) } }
                }
                if (android.os.Build.VERSION.SDK_INT >= 31) {
                    ThemeSwatch(
                        name = "Material You",
                        colors = listOf(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary, MaterialTheme.colorScheme.tertiary),
                        selected = settings.theme == Palettes.DYNAMIC,
                    ) { update { it.copy(theme = Palettes.DYNAMIC) } }
                }
            }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                DarkMode.entries.forEachIndexed { i, m ->
                    SegmentedButton(
                        selected = settings.darkMode == m,
                        onClick = { update { it.copy(darkMode = m) } },
                        shape = SegmentedButtonDefaults.itemShape(i, DarkMode.entries.size),
                    ) { Text(m.name.lowercase().replaceFirstChar { it.uppercase() }) }
                }
            }

            Header("General")
            var appName by rememberSaveable { mutableStateOf(settings.appName) }
            OutlinedTextField(
                value = appName,
                onValueChange = { appName = it; if (it.isNotBlank()) update { s -> s.copy(appName = it.trim()) } },
                label = { Text("App name") },
                supportingText = { Text("Shown in the app and widget. Pin a home-screen shortcut with this name below.") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(onClick = {
                if (ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
                    val label = settings.appName.ifBlank { "Purse" }
                    val info = ShortcutInfoCompat.Builder(context, "launcher-$label")
                        .setShortLabel(label)
                        .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
                        .setIntent(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN))
                        .build()
                    ShortcutManagerCompat.requestPinShortcut(context, info, null)
                } else {
                    toast("Your launcher does not support pinned shortcuts")
                }
            }) { Text("Add \"${settings.appName}\" to home screen") }

            var currency by rememberSaveable { mutableStateOf(settings.currency) }
            OutlinedTextField(
                value = currency,
                onValueChange = { currency = it.take(4); update { s -> s.copy(currency = currency) } },
                label = { Text("Currency symbol") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            SwitchRow("Indian number grouping", "12,34,567 instead of 1,234,567", settings.indianGrouping) { v ->
                update { it.copy(indianGrouping = v) }
            }
            var budget by rememberSaveable { mutableStateOf(if (settings.monthlyBudget > 0) Money.toPlain(settings.monthlyBudget) else "") }
            OutlinedTextField(
                value = budget,
                onValueChange = { v ->
                    budget = v.filter { it.isDigit() || it == '.' }
                    update { it.copy(monthlyBudget = Money.parseMinor(budget) ?: 0) }
                },
                label = { Text("Monthly budget (optional)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )

            Header("Data")
            NavRow("Categories", "Add, rename or remove categories") { nav.navigate("categories") }
            NavRow("SMS tracking", if (settings.smsEnabled) "On · ${container.repo.templates.value.size} templates" else "Off") { nav.navigate("sms") }
            NavRow("Git sync", if (settings.sync.configured) settings.sync.lastStatus.ifBlank { "Configured" } else "Not set up") { nav.navigate("sync") }
            NavRow("Export backup", "Save everything to a JSON file") { exporter.launch("purse-backup.json") }
            NavRow("Import backup", "Merge a JSON backup (newer edits win)") { importer.launch(arrayOf("application/json", "*/*")) }

            Text(
                "${settings.appName} ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 24.dp),
            )
        }
    }
}

@Composable
fun Header(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp))
}

@Composable
fun NavRow(title: String, subtitle: String, onClick: () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
        }
        HorizontalDivider()
    }
}

@Composable
private fun ThemeSwatch(name: String, colors: List<Color>, selected: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(76.dp).clickable(onClick = onClick)) {
        Box(
            Modifier.size(64.dp)
                .background(colors[0], RoundedCornerShape(16.dp))
                .border(
                    if (selected) 3.dp else 1.dp,
                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    RoundedCornerShape(16.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                colors.drop(1).forEach { Box(Modifier.size(12.dp).background(it, CircleShape)) }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(name, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}
