package app.purse.ui.accounts

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import app.purse.core.Money
import app.purse.data.AccountEntity
import app.purse.data.AccountType
import app.purse.ui.EmojiBadge
import app.purse.ui.LocalContainer
import app.purse.ui.LocalMoney
import app.purse.ui.PurseScaffold
import app.purse.ui.SectionCard
import app.purse.ui.addTxn
import app.purse.ui.theme.LocalPurseColors
import kotlinx.coroutines.launch

@Composable
fun AccountsScreen(nav: NavHostController) {
    val repo = LocalContainer.current.repo
    val money = LocalMoney.current
    val colors = LocalPurseColors.current
    val accounts by repo.accounts.collectAsStateWithLifecycle()
    val balances by repo.balances.collectAsStateWithLifecycle()
    val included = accounts.filter { !it.excludeFromTotal && !it.archived }
    val excluded = accounts.filter { it.excludeFromTotal || it.archived }

    PurseScaffold(
        title = "Accounts",
        tab = true,
        actions = {
            IconButton(onClick = { nav.addTxn(type = "TRANSFER") }) { Icon(Icons.Filled.SwapHoriz, "Transfer") }
            IconButton(onClick = { nav.navigate("account") }) { Icon(Icons.Filled.Add, "Add account") }
        },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad)) {
            item {
                SectionCard(Modifier.padding(16.dp)) {
                    Text("Counted in total", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        money.format(included.sumOf { balances[it.id] ?: 0L }),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            items(included, key = { it.id }) { a -> AccountRow(a, balances[a.id] ?: 0L, colors.expense) { nav.navigate("account?id=${a.id}") } }
            if (excluded.isNotEmpty()) {
                item {
                    Text(
                        "Not counted in total",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                    )
                }
                items(excluded, key = { it.id }) { a -> AccountRow(a, balances[a.id] ?: 0L, colors.expense) { nav.navigate("account?id=${a.id}") } }
            }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

@Composable
private fun AccountRow(a: AccountEntity, balance: Long, negative: Color, onClick: () -> Unit) {
    val money = LocalMoney.current
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { EmojiBadge(a.type.emoji) },
        headlineContent = { Text(a.name) },
        supportingContent = {
            Text(listOfNotNull(a.type.label, a.numberHint.takeIf { it.isNotBlank() }?.let { "•• $it" }, "archived".takeIf { a.archived }).joinToString(" · "))
        },
        trailingContent = {
            Text(
                money.format(balance),
                style = MaterialTheme.typography.titleMedium,
                color = if (balance < 0) negative else MaterialTheme.colorScheme.onSurface,
            )
        },
    )
}

@Composable
fun AccountEditScreen(nav: NavHostController, accountId: String?) {
    val container = LocalContainer.current
    val repo = container.repo
    val balances by repo.balances.collectAsStateWithLifecycle()

    var existing by remember { mutableStateOf<AccountEntity?>(null) }
    var loaded by rememberSaveable { mutableStateOf(accountId == null) }
    var name by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableStateOf(AccountType.SAVINGS.name) }
    var hint by rememberSaveable { mutableStateOf("") }
    var exclude by rememberSaveable { mutableStateOf(false) }
    var archived by rememberSaveable { mutableStateOf(false) }
    var balance by rememberSaveable { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(accountId) {
        if (accountId != null) {
            val a = repo.account(accountId)
            existing = a
            if (a != null && !loaded) {
                name = a.name
                type = a.type.name
                hint = a.numberHint
                exclude = a.excludeFromTotal
                archived = a.archived
                balance = Money.toPlain(repo.balances.value[a.id] ?: 0L)
                loaded = true
            }
        }
    }

    fun save() {
        if (name.isBlank()) {
            error = "Name is required"
            return
        }
        val target = if (balance.isBlank()) null else Money.parseMinor(balance.replace(" ", ""))
        if (balance.isNotBlank() && target == null) {
            error = "Balance is not a number"
            return
        }
        val a = (existing ?: AccountEntity(name = name, type = AccountType.SAVINGS, sortOrder = repo.accounts.value.size)).copy(
            name = name.trim(),
            type = AccountType.valueOf(type),
            numberHint = hint.trim(),
            excludeFromTotal = exclude,
            archived = archived,
        )
        container.scope.launch {
            repo.saveAccount(a)
            if (target != null) repo.setBalance(a.id, target, if (existing == null) "Opening balance" else "Balance set")
        }
        nav.popBackStack()
    }

    PurseScaffold(
        title = if (accountId == null) "New account" else "Edit account",
        onBack = { nav.popBackStack() },
        actions = {
            if (existing != null) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Delete") }
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("Type", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AccountType.entries.forEach { t ->
                    FilterChip(
                        selected = type == t.name,
                        onClick = {
                            type = t.name
                            // Investments are usually not "money in hand"; suggest excluding them.
                            if (existing == null) exclude = t in setOf(AccountType.MUTUAL_FUND, AccountType.STOCKS, AccountType.FD, AccountType.RD)
                        },
                        label = { Text("${t.emoji} ${t.label}") },
                    )
                }
            }
            OutlinedTextField(
                value = balance,
                onValueChange = { v -> balance = v.filter { it.isDigit() || it == '.' || it == '-' || it == ',' } },
                label = { Text(if (existing == null) "Current balance" else "Current balance (edit to correct)") },
                supportingText = {
                    val current = existing?.let { balances[it.id] } ?: 0L
                    Text(
                        if (existing == null) "Use a negative number for credit card dues or loans."
                        else "Now ${LocalMoney.current.format(current)}. Changing it records an adjustment.",
                    )
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = hint,
                onValueChange = { hint = it },
                label = { Text("Account / card digits (optional)") },
                supportingText = { Text("Last digits as they appear in SMS, e.g. 6254. Separate several with commas.") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            SwitchRow("Exclude from total", "Keep this account out of your balance (e.g. investments)", exclude) { exclude = it }
            SwitchRow("Archived", "Hide from pickers; history is kept", archived) { archived = it }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = ::save, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Save") }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${existing?.name}?") },
            text = { Text("All transactions of this account are deleted too. Archive it instead to keep history.") },
            confirmButton = {
                TextButton(onClick = {
                    existing?.let { a -> container.scope.launch { repo.deleteAccount(a.id) } }
                    confirmDelete = false
                    nav.popBackStack()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
