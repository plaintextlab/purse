package app.purse.ui.txn

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import app.purse.core.Money
import app.purse.data.AccountEntity
import app.purse.data.CategoryKind
import app.purse.data.Settings
import app.purse.data.TxnEntity
import app.purse.data.TxnSource
import app.purse.data.TxnType
import app.purse.ui.LocalContainer
import app.purse.ui.PurseScaffold
import app.purse.ui.label
import app.purse.ui.zone
import app.purse.ui.theme.LocalPurseColors
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Add or edit a transaction. Built for speed: the amount field is focused on open,
 * date/time default to now and the last used account is preselected, so a typical
 * entry is "type amount, tap category, save".
 */
@Composable
fun AddEditTxnScreen(
    nav: NavHostController,
    settings: Settings,
    initialType: String?,
    txnId: String?,
    initialDate: String?,
) {
    val container = LocalContainer.current
    val repo = container.repo
    val colors = LocalPurseColors.current
    val accounts by repo.accounts.collectAsStateWithLifecycle()
    val allCategories by repo.categories.collectAsStateWithLifecycle()

    var loaded by rememberSaveable { mutableStateOf(txnId == null) }
    var existing by remember { mutableStateOf<TxnEntity?>(null) }
    var type by rememberSaveable { mutableStateOf(initialType ?: TxnType.EXPENSE.name) }
    var amount by rememberSaveable { mutableStateOf("") }
    var categoryId by rememberSaveable { mutableStateOf<String?>(null) }
    var accountId by rememberSaveable { mutableStateOf<String?>(null) }
    var toAccountId by rememberSaveable { mutableStateOf<String?>(null) }
    var note by rememberSaveable { mutableStateOf("") }
    var dateTime by rememberSaveable {
        val now = LocalDateTime.now()
        mutableStateOf(initialDate?.let { LocalDate.parse(it).atTime(now.toLocalTime()) }?.toString() ?: now.toString())
    }
    var showDate by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(txnId) {
        if (txnId != null) {
            val t = repo.txn(txnId)
            existing = t
            if (t != null && !loaded) {
                type = t.type.name
                amount = Money.toPlain(t.amount)
                categoryId = t.categoryId
                accountId = t.accountId
                toAccountId = t.toAccountId
                note = t.note
                dateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(t.timestamp), zone).toString()
                loaded = true
            }
        }
    }
    // Preselect the last used account once accounts are available.
    LaunchedEffect(accounts, loaded) {
        if (loaded && accountId == null && accounts.isNotEmpty()) {
            accountId = accounts.firstOrNull { it.id == settings.defaultAccountId }?.id ?: accounts.first().id
        }
    }

    val txnType = TxnType.valueOf(type)
    val when_ = LocalDateTime.parse(dateTime)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (txnId == null) runCatching { focus.requestFocus() } }

    fun save() {
        val minor = Money.parseMinor(amount)
        val acc = accountId
        error = when {
            minor == null || minor == 0L -> "Enter an amount"
            acc == null -> "Pick an account"
            txnType == TxnType.TRANSFER && (toAccountId == null || toAccountId == acc) -> "Pick a different destination account"
            else -> null
        }
        if (error != null || minor == null || acc == null) return
        val base = existing ?: TxnEntity(type = txnType, amount = 0, accountId = acc, timestamp = 0, source = TxnSource.MANUAL)
        val amountValue = if (txnType == TxnType.ADJUSTMENT) minor else kotlin.math.abs(minor)
        val txn = base.copy(
            type = txnType,
            amount = amountValue,
            accountId = acc,
            toAccountId = if (txnType == TxnType.TRANSFER) toAccountId else null,
            categoryId = if (txnType == TxnType.EXPENSE || txnType == TxnType.INCOME) categoryId else null,
            note = note.trim(),
            timestamp = when_.atZone(zone).toInstant().toEpochMilli(),
        )
        container.scope.launch {
            repo.saveTxn(txn)
            container.settings.update { it.copy(defaultAccountId = acc) }
        }
        nav.popBackStack()
    }

    PurseScaffold(
        title = if (txnId == null) "New transaction" else "Edit transaction",
        onBack = { nav.popBackStack() },
        actions = {
            if (existing != null) {
                IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Delete") }
            }
            IconButton(onClick = ::save) { Icon(Icons.Filled.Check, "Save") }
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (txnType != TxnType.ADJUSTMENT) {
                val options = listOf(TxnType.EXPENSE, TxnType.INCOME, TxnType.TRANSFER)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    options.forEachIndexed { i, opt ->
                        SegmentedButton(
                            selected = txnType == opt,
                            onClick = { type = opt.name; categoryId = null },
                            shape = SegmentedButtonDefaults.itemShape(index = i, count = options.size),
                        ) { Text(opt.name.lowercase().replaceFirstChar { it.uppercase() }) }
                    }
                }
            }

            OutlinedTextField(
                value = amount,
                onValueChange = { v -> amount = v.filter { it.isDigit() || it == '.' || it == ',' || (txnType == TxnType.ADJUSTMENT && it == '-') } },
                label = { Text("Amount (${settings.currency})") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                textStyle = TextStyle(
                    fontSize = 32.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = when (txnType) {
                        TxnType.EXPENSE -> colors.expense
                        TxnType.INCOME -> colors.income
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                ),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )

            when (txnType) {
                TxnType.EXPENSE, TxnType.INCOME -> {
                    val kind = if (txnType == TxnType.INCOME) CategoryKind.INCOME else CategoryKind.EXPENSE
                    Label("Category")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        allCategories.filter { it.kind == kind && (!it.deleted || it.id == categoryId) }.forEach { c ->
                            FilterChip(
                                selected = categoryId == c.id,
                                onClick = { categoryId = if (categoryId == c.id) null else c.id },
                                label = { Text("${c.icon} ${c.name}".trim()) },
                            )
                        }
                    }
                    Label("Account")
                    AccountPicker(accounts, accountId) { accountId = it }
                }
                TxnType.TRANSFER -> {
                    Label("From")
                    AccountPicker(accounts, accountId) { accountId = it }
                    Label("To")
                    AccountPicker(accounts.filter { it.id != accountId }, toAccountId) { toAccountId = it }
                }
                TxnType.ADJUSTMENT -> {
                    Label("Account")
                    AccountPicker(accounts, accountId) { accountId = it }
                }
            }

            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("Details (optional)") },
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = { showDate = true }, label = { Text(when_.toLocalDate().label()) })
                AssistChip(onClick = { showTime = true }, label = { Text(when_.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"))) })
            }

            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = ::save, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Save") }
        }
    }

    if (showDate) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = when_.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        val d = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                        dateTime = d.atTime(when_.toLocalTime()).toString()
                    }
                    showDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showDate = false }) { Text("Cancel") } },
        ) { DatePicker(state) }
    }

    if (showTime) {
        val state = rememberTimePickerState(initialHour = when_.hour, initialMinute = when_.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { showTime = false },
            confirmButton = {
                TextButton(onClick = {
                    dateTime = when_.toLocalDate().atTime(LocalTime.of(state.hour, state.minute)).toString()
                    showTime = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showTime = false }) { Text("Cancel") } },
            text = { TimeInput(state) },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete transaction?") },
            confirmButton = {
                TextButton(onClick = {
                    val id = existing?.id
                    if (id != null) container.scope.launch { repo.deleteTxn(id) }
                    confirmDelete = false
                    nav.popBackStack()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun AccountPicker(accounts: List<AccountEntity>, selected: String?, onSelect: (String) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        accounts.filter { !it.archived || it.id == selected }.forEach { a ->
            FilterChip(selected = selected == a.id, onClick = { onSelect(a.id) }, label = { Text("${a.type.emoji} ${a.name}") })
        }
    }
}
