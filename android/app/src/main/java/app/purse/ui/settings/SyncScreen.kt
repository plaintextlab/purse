package app.purse.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import app.purse.data.Settings
import app.purse.data.SyncConfig
import app.purse.sync.GitSync
import app.purse.sync.SyncScheduler
import app.purse.ui.PurseScaffold
import app.purse.ui.LocalContainer
import app.purse.ui.SectionCard
import app.purse.ui.accounts.SwitchRow
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
fun SyncScreen(nav: NavHostController, settings: Settings) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val s = settings.sync
    var url by rememberSaveable { mutableStateOf(s.repoUrl) }
    var branch by rememberSaveable { mutableStateOf(s.branch) }
    var user by rememberSaveable { mutableStateOf(s.username) }
    var token by rememberSaveable { mutableStateOf(s.token) }
    var folder by rememberSaveable { mutableStateOf(s.folder.ifBlank { GitSync.sanitizeFolder(settings.appName) }) }
    var authorName by rememberSaveable { mutableStateOf(s.authorName) }
    var authorEmail by rememberSaveable { mutableStateOf(s.authorEmail) }
    var auto by rememberSaveable { mutableStateOf(s.auto) }
    var busy by remember { mutableStateOf(false) }

    suspend fun persist(): SyncConfig {
        val cfg = s.copy(
            repoUrl = url.trim(), branch = branch.trim().ifBlank { "main" }, username = user.trim(), token = token.trim(),
            folder = GitSync.sanitizeFolder(folder), authorName = authorName.trim(), authorEmail = authorEmail.trim(), auto = auto,
        )
        // A different repository means the old checkout is meaningless.
        if (cfg.repoUrl != s.repoUrl || cfg.branch != s.branch) container.gitSync.reset()
        container.settings.update { it.copy(sync = cfg) }
        SyncScheduler.schedulePeriodic(context, cfg)
        return cfg
    }

    PurseScaffold(title = "Git sync", onBack = { nav.popBackStack() }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Data is stored as JSON in a folder of your sync repository. Other folders in that repository are left alone.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(url, { url = it }, label = { Text("Repository URL (https)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(branch, { branch = it }, label = { Text("Branch") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                folder, { folder = it },
                label = { Text("Folder in repository") },
                supportingText = { Text("Defaults to the app name. Renaming the app later does not move it.") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(user, { user = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                token, { token = it },
                label = { Text("Access token") },
                supportingText = { Text("A personal access token with read/write access to this repository only.") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(authorName, { authorName = it }, label = { Text("Commit author name (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(authorEmail, { authorEmail = it }, label = { Text("Commit author email (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            SwitchRow("Sync automatically", "After changes and every few hours when online", auto) { auto = it }

            OutlinedButton(onClick = { scope.launch { persist() } }, modifier = Modifier.fillMaxWidth()) { Text("Save") }
            Button(
                onClick = {
                    // App scope: leaving the screen must not cancel a sync half way.
                    container.scope.launch {
                        busy = true
                        persist()
                        container.gitSync.sync()
                        busy = false
                    }
                },
                enabled = !busy && url.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                if (busy) CircularProgressIndicator(Modifier.height(20.dp)) else Text("Save & sync now")
            }

            if (s.lastSyncAt > 0) {
                SectionCard(title = "Last sync") {
                    Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(s.lastSyncAt)))
                    Text(
                        s.lastStatus,
                        color = if (s.lastStatus.startsWith("Failed")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}
