package app.purse.ui.txn

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import app.purse.data.TxnType
import app.purse.ui.EmptyHint
import app.purse.ui.LocalContainer
import app.purse.ui.LocalMoney
import app.purse.ui.MonthSwitcher
import app.purse.ui.PurseScaffold
import app.purse.ui.TxnRow
import app.purse.ui.addTxn
import app.purse.ui.label
import app.purse.ui.monthRange
import app.purse.ui.toLocalDate
import java.time.YearMonth

@Composable
fun TransactionsScreen(nav: NavHostController) {
    val repo = LocalContainer.current.repo
    val money = LocalMoney.current
    var monthStr by rememberSaveable { mutableStateOf(YearMonth.now().toString()) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    val month = YearMonth.parse(monthStr)
    val range = monthRange(month)

    val accounts by repo.accounts.collectAsStateWithLifecycle()
    val categories by repo.categories.collectAsStateWithLifecycle()
    val source = remember(range, searching, query) {
        if (searching && query.isNotBlank()) repo.search(query.trim()) else repo.txnsBetween(range.first, range.second)
    }
    val all by source.collectAsStateWithLifecycle(emptyList())
    val txns = all.filter { filter == null || it.type.name == filter }
    val catMap = categories.associateBy { it.id }
    val accMap = accounts.associateBy { it.id }
    val grouped = txns.groupBy { it.timestamp.toLocalDate() }

    PurseScaffold(
        title = "Activity",
        tab = true,
        actions = {
            IconButton(onClick = { searching = !searching; if (!searching) query = "" }) {
                Icon(if (searching) Icons.Filled.Close else Icons.Filled.Search, "Search")
            }
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            if (searching) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search notes and categories") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
            } else {
                MonthSwitcher(month) { monthStr = it.toString() }
            }
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(null to "All", "EXPENSE" to "Expense", "INCOME" to "Income", "TRANSFER" to "Transfer").forEach { (key, label) ->
                    FilterChip(selected = filter == key, onClick = { filter = key }, label = { Text(label) })
                }
            }
            if (txns.isEmpty()) {
                EmptyHint(if (searching) "Nothing matches" else "No transactions this month")
            }
            LazyColumn(Modifier.fillMaxSize()) {
                grouped.forEach { (day, list) ->
                    item(key = "h$day") {
                        val spent = list.filter { it.type == TxnType.EXPENSE }.sumOf { it.amount }
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)) {
                            Text(day.label(), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            if (spent > 0) {
                                Text(
                                    "-" + money.format(spent),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    items(list, key = { it.id }) { t ->
                        TxnRow(t, catMap, accMap, onClick = { nav.addTxn(id = t.id) })
                    }
                }
                item { Spacer(Modifier.height(96.dp)) }
            }
        }
    }
}
