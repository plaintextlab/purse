package app.purse.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import app.purse.core.SmsPattern
import app.purse.data.CategoryKind
import app.purse.data.Settings
import app.purse.data.SmsTemplateEntity
import app.purse.data.TxnType
import app.purse.sms.SmsCandidate
import app.purse.ui.EmptyHint
import app.purse.ui.LocalContainer
import app.purse.ui.LocalMoney
import app.purse.ui.PurseScaffold
import app.purse.ui.SectionCard
import app.purse.ui.accounts.SwitchRow
import app.purse.ui.label
import app.purse.ui.toLocalDate
import kotlinx.coroutines.launch
import java.time.LocalDate

private fun smsPermissions(): Array<String> = buildList {
    add(Manifest.permission.RECEIVE_SMS)
    add(Manifest.permission.READ_SMS)
    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()

@Composable
fun SmsScreen(nav: NavHostController, settings: Settings) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val templates by container.repo.templates.collectAsStateWithLifecycle()
    var denied by remember { mutableStateOf(false) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val ok = result[Manifest.permission.RECEIVE_SMS] == true
        denied = !ok
        container.scope.launch { container.settings.update { it.copy(smsEnabled = ok) } }
    }

    PurseScaffold(
        title = "SMS tracking",
        onBack = { nav.popBackStack() },
        floating = { FloatingActionButton(onClick = { nav.navigate("template") }) { Icon(Icons.Filled.Add, "Add template") } },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                SwitchRow(
                    "Read bank SMS",
                    "New messages matching a template become transactions automatically",
                    settings.smsEnabled,
                ) { on ->
                    if (on) {
                        permissions.launch(smsPermissions())
                    } else {
                        container.scope.launch { container.settings.update { it.copy(smsEnabled = false) } }
                    }
                }
                SwitchRow("Notify when added", null, settings.smsNotify) { v ->
                    container.scope.launch { container.settings.update { it.copy(smsNotify = v) } }
                }
                if (denied) Text("SMS permission was denied. Enable it in system settings.", color = MaterialTheme.colorScheme.error)
                OutlinedButton(
                    onClick = {
                        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
                        if (granted) nav.navigate("smsscan") else permissions.launch(smsPermissions())
                    },
                    enabled = templates.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Import from inbox") }
                Header("Templates")
                if (templates.isEmpty()) {
                    EmptyHint("Add a template from a sample SMS of your bank, card or UPI app. Tap + to start.")
                }
            }
            items(templates, key = { it.id }) { t ->
                SectionCard(Modifier.clickable { nav.navigate("template?id=${t.id}") }) {
                    Text(t.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${t.senderPattern} · ${t.txnType.name.lowercase()}" + if (!t.enabled) " · disabled" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item { TemplateTester() }
            item { Spacer(Modifier.height(96.dp)) }
        }
    }
}

/** Paste any SMS to see which template (if any) picks it up. */
@Composable
private fun TemplateTester() {
    val container = LocalContainer.current
    val money = LocalMoney.current
    var sender by rememberSaveable { mutableStateOf("") }
    var body by rememberSaveable { mutableStateOf("") }
    var result by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    SectionCard(Modifier.padding(top = 16.dp), title = "Test a message") {
        OutlinedTextField(sender, { sender = it }, label = { Text("Sender, e.g. AD-HDFCBK-T") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(body, { body = it }, label = { Text("Message") }, minLines = 3, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Button(onClick = {
            scope.launch {
                val c = container.sms.evaluate(sender, body, System.currentTimeMillis())
                result = if (c == null) {
                    "No template matched"
                } else {
                    val account = c.accountId?.let { container.repo.account(it)?.name } ?: "no account"
                    "${c.template.name}: ${money.format(c.match.amountMinor)} · $account" + (c.match.merchant?.let { " · $it" } ?: "")
                }
            }
        }) { Text("Test") }
        result?.let { Text(it, modifier = Modifier.padding(top = 8.dp)) }
    }
}

@Composable
fun TemplateEditScreen(nav: NavHostController, templateId: String?) {
    val container = LocalContainer.current
    val repo = container.repo
    val money = LocalMoney.current
    val accounts by repo.accounts.collectAsStateWithLifecycle()
    val categories by repo.categories.collectAsStateWithLifecycle()

    var existing by remember { mutableStateOf<SmsTemplateEntity?>(null) }
    var loaded by rememberSaveable { mutableStateOf(templateId == null) }
    var name by rememberSaveable { mutableStateOf("") }
    var sender by rememberSaveable { mutableStateOf("") }
    var sample by rememberSaveable { mutableStateOf("") }
    var amount by rememberSaveable { mutableStateOf("") }
    var account by rememberSaveable { mutableStateOf("") }
    var merchant by rememberSaveable { mutableStateOf("") }
    var pattern by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableStateOf(TxnType.EXPENSE.name) }
    var accountId by rememberSaveable { mutableStateOf<String?>(null) }
    var categoryId by rememberSaveable { mutableStateOf<String?>(null) }
    var enabled by rememberSaveable { mutableStateOf(true) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(templateId) {
        if (templateId != null) {
            val t = repo.template(templateId)
            existing = t
            if (t != null && !loaded) {
                name = t.name; sender = t.senderPattern; sample = t.sample; pattern = t.pattern
                type = t.txnType.name; accountId = t.accountId; categoryId = t.categoryId; enabled = t.enabled
                loaded = true
            }
        }
    }

    val compiled = remember(pattern) { SmsPattern.compileOrNull(pattern) }
    val preview = remember(compiled, sample) { compiled?.match(sample) }

    fun save() {
        message = when {
            name.isBlank() -> "Give the template a name"
            sender.isBlank() -> "Enter a sender pattern such as *HDFC*"
            compiled == null -> "The pattern needs an {amount} placeholder"
            else -> null
        }
        if (message != null) return
        val t = (existing ?: SmsTemplateEntity(name = "", senderPattern = "", pattern = "")).copy(
            name = name.trim(),
            senderPattern = sender.trim(),
            pattern = pattern.trim(),
            sample = sample,
            txnType = TxnType.valueOf(type),
            accountId = accountId,
            categoryId = categoryId,
            enabled = enabled,
        )
        container.scope.launch { repo.saveTemplate(t) }
        nav.popBackStack()
    }

    PurseScaffold(
        title = if (templateId == null) "New SMS template" else "Edit SMS template",
        onBack = { nav.popBackStack() },
        actions = {
            existing?.let { t ->
                IconButton(onClick = {
                    container.scope.launch { repo.deleteTemplate(t) }
                    nav.popBackStack()
                }) { Icon(Icons.Filled.Delete, "Delete") }
            }
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(name, { name = it }, label = { Text("Name, e.g. HDFC UPI debit") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                sender, { sender = it },
                label = { Text("Sender") },
                supportingText = { Text("Wildcards: *HDFC* matches AD-HDFCBK-T. Separate several with commas.") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                sample, { sample = it },
                label = { Text("Sample SMS") },
                minLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
            Text("Copy the values exactly as they appear in the sample:", style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(amount, { amount = it }, label = { Text("Amount, e.g. 2135.00") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(account, { account = it }, label = { Text("Account digits (optional), e.g. 6254") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(merchant, { merchant = it }, label = { Text("Merchant / payee (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val (a, acc, m) = SmsPattern.guess(sample)
                    if (amount.isBlank()) amount = a.orEmpty()
                    if (account.isBlank()) account = acc.orEmpty()
                    if (merchant.isBlank()) merchant = m.orEmpty()
                    if (sender.isBlank()) message = "Tip: fill in the sender too"
                }) { Text("Guess") }
                Button(onClick = {
                    SmsPattern.fromSample(sample, amount, account, merchant).fold(
                        onSuccess = { pattern = it; message = null },
                        onFailure = { message = it.message },
                    )
                }) { Text("Build pattern") }
            }
            OutlinedTextField(
                pattern, { pattern = it },
                label = { Text("Pattern") },
                supportingText = { Text("{amount} {account} {merchant} mark values, {*} skips anything. Edit freely.") },
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            SectionCard(title = "Preview on sample") {
                when {
                    pattern.isBlank() -> Text("Build or type a pattern to preview")
                    compiled == null -> Text("Pattern needs {amount}", color = MaterialTheme.colorScheme.error)
                    preview == null -> Text("Pattern does not match the sample", color = MaterialTheme.colorScheme.error)
                    else -> {
                        Text("Amount: ${money.format(preview.amountMinor)}")
                        Text("Account: ${preview.account ?: "—"}")
                        Text("Merchant: ${preview.merchant ?: "—"}")
                    }
                }
            }

            Header("Creates")
            val options = listOf(TxnType.EXPENSE, TxnType.INCOME)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                options.forEachIndexed { i, o ->
                    SegmentedButton(
                        selected = type == o.name,
                        onClick = { type = o.name; categoryId = null },
                        shape = SegmentedButtonDefaults.itemShape(i, options.size),
                    ) { Text(if (o == TxnType.EXPENSE) "Expense" else "Income") }
                }
            }
            Text("Account", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = accountId == null, onClick = { accountId = null }, label = { Text("Match by digits") })
                accounts.forEach { a ->
                    FilterChip(selected = accountId == a.id, onClick = { accountId = a.id }, label = { Text("${a.type.emoji} ${a.name}") })
                }
            }
            if (accountId == null) {
                Text(
                    "Uses the account whose digits match {account}; set them on the account screen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text("Category", style = MaterialTheme.typography.labelLarge)
            val kind = if (type == TxnType.INCOME.name) CategoryKind.INCOME else CategoryKind.EXPENSE
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                categories.filter { !it.deleted && it.kind == kind }.forEach { c ->
                    FilterChip(
                        selected = categoryId == c.id,
                        onClick = { categoryId = if (categoryId == c.id) null else c.id },
                        label = { Text("${c.icon} ${c.name}") },
                    )
                }
            }
            SwitchRow("Enabled", null, enabled) { enabled = it }
            message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = ::save, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Save") }
        }
    }
}

@Composable
fun SmsScanScreen(nav: NavHostController) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val money = LocalMoney.current
    var days by rememberSaveable { mutableStateOf(30) }
    var scanning by remember { mutableStateOf(false) }
    val found = remember { mutableStateListOf<SmsCandidate>() }
    val chosen = remember { mutableStateListOf<String>() }
    val accounts by container.repo.accounts.collectAsStateWithLifecycle()

    LaunchedEffect(days) {
        scanning = true
        val since = System.currentTimeMillis() - days * 86_400_000L
        val result = runCatching { container.sms.scanInbox(context, since) }.getOrDefault(emptyList())
        found.clear(); found.addAll(result)
        chosen.clear(); chosen.addAll(result.filter { it.accountId != null }.map { it.hash })
        scanning = false
    }

    PurseScaffold(title = "Import from inbox", onBack = { nav.popBackStack() }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(7, 30, 90, 365).forEach { d ->
                    FilterChip(selected = days == d, onClick = { days = d }, label = { Text("$d days") })
                }
            }
            when {
                scanning -> EmptyHint("Scanning…")
                found.isEmpty() -> EmptyHint("No new matching messages")
            }
            LazyColumn(Modifier.weight(1f)) {
                items(found, key = { it.hash }) { c ->
                    val on = c.hash in chosen
                    Row(
                        Modifier.fillMaxWidth().clickable { if (on) chosen.remove(c.hash) else chosen.add(c.hash) }.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = on, onCheckedChange = null)
                        Column(Modifier.weight(1f).padding(start = 8.dp)) {
                            Text("${money.format(c.match.amountMinor)} · ${c.match.merchant ?: c.template.name}")
                            Text(
                                listOf(
                                    c.timestamp.toLocalDate().let { if (it == LocalDate.now()) "Today" else it.label() },
                                    accounts.firstOrNull { it.id == c.accountId }?.name ?: "no account",
                                    c.template.name,
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            Button(
                onClick = {
                    val picked = found.filter { it.hash in chosen }
                    container.scope.launch { picked.forEach { container.sms.import(it) } }
                    nav.popBackStack()
                },
                enabled = chosen.isNotEmpty() && !scanning,
                modifier = Modifier.fillMaxWidth().padding(16.dp).height(52.dp),
            ) { Text("Import ${chosen.size}") }
        }
    }
}
