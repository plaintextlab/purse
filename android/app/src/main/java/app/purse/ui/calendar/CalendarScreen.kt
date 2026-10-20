package app.purse.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import app.purse.ui.EmptyHint
import app.purse.ui.LocalContainer
import app.purse.ui.LocalMoney
import app.purse.ui.MonthSwitcher
import app.purse.ui.PurseScaffold
import app.purse.ui.TxnRow
import app.purse.ui.addTxn
import app.purse.ui.label
import app.purse.ui.monthRange
import app.purse.ui.monthStats
import app.purse.ui.theme.LocalPurseColors
import app.purse.ui.toLocalDate
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/** Month grid; each day shows what was spent, shaded by how heavy the day was. */
@Composable
fun CalendarScreen(nav: NavHostController) {
    val repo = LocalContainer.current.repo
    val money = LocalMoney.current
    val colors = LocalPurseColors.current
    var monthStr by rememberSaveable { mutableStateOf(YearMonth.now().toString()) }
    var selectedStr by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    val month = YearMonth.parse(monthStr)
    val selected = LocalDate.parse(selectedStr)
    val range = monthRange(month)

    val accounts by repo.accounts.collectAsStateWithLifecycle()
    val categories by repo.categories.collectAsStateWithLifecycle()
    val txns by remember(range) { repo.txnsBetween(range.first, range.second) }.collectAsStateWithLifecycle(emptyList())
    val stats = monthStats(txns)
    val max = (stats.byDay.values.maxOrNull() ?: 0L).coerceAtLeast(1L)
    val dayTxns = txns.filter { it.timestamp.toLocalDate() == selected }
    val catMap = categories.associateBy { it.id }
    val accMap = accounts.associateBy { it.id }

    PurseScaffold(title = "Calendar", tab = true) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad)) {
            item {
                MonthSwitcher(month) {
                    monthStr = it.toString()
                    selectedStr = (if (it == YearMonth.now()) LocalDate.now() else it.atDay(1)).toString()
                }
                Text(
                    "Spent ${money.format(stats.spent)} · Earned ${money.format(stats.earned)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    textAlign = TextAlign.Center,
                )
                Row(Modifier.padding(horizontal = 8.dp)) {
                    DayOfWeek.values().forEach { d ->
                        Text(
                            d.name.take(1),
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                val lead = month.atDay(1).dayOfWeek.value - 1 // Monday first
                val cells = lead + month.lengthOfMonth()
                val rows = (cells + 6) / 7
                Column(Modifier.padding(horizontal = 8.dp)) {
                    for (r in 0 until rows) {
                        Row {
                            for (col in 0 until 7) {
                                val day = r * 7 + col - lead + 1
                                Box(Modifier.weight(1f).aspectRatio(0.8f).padding(2.dp)) {
                                    if (day in 1..month.lengthOfMonth()) {
                                        val date = month.atDay(day)
                                        val spent = stats.byDay[day] ?: 0L
                                        val intensity = if (spent > 0) 0.15f + 0.6f * (spent.toFloat() / max) else 0f
                                        val shape = RoundedCornerShape(8.dp)
                                        Column(
                                            Modifier.fillMaxSize()
                                                .background(colors.expense.copy(alpha = intensity), shape)
                                                .then(
                                                    if (date == selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape)
                                                    else Modifier,
                                                )
                                                .clickable { selectedStr = date.toString() }
                                                .padding(4.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                        ) {
                                            Text(
                                                "$day",
                                                style = MaterialTheme.typography.labelLarge,
                                                fontWeight = if (date == LocalDate.now()) FontWeight.Bold else FontWeight.Normal,
                                            )
                                            Spacer(Modifier.weight(1f))
                                            if (spent > 0) {
                                                Text(money.compact(spent), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(selected.label(), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { nav.addTxn(date = selected.toString()) }) { Text("Add on this day") }
                }
                if (dayTxns.isEmpty()) EmptyHint("Nothing on this day")
            }
            items(dayTxns, key = { it.id }) { t -> TxnRow(t, catMap, accMap, onClick = { nav.addTxn(id = t.id) }) }
            item { Spacer(Modifier.height(96.dp)) }
        }
    }
}
