package app.purse.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import app.purse.data.Settings
import app.purse.ui.EmptyHint
import app.purse.ui.LocalContainer
import app.purse.ui.LocalMoney
import app.purse.ui.MonthSwitcher
import app.purse.ui.PurseScaffold
import app.purse.ui.SectionCard
import app.purse.ui.TxnRow
import app.purse.ui.addTxn
import app.purse.ui.monthRange
import app.purse.ui.monthStats
import app.purse.ui.theme.LocalPurseColors
import java.time.YearMonth

@Composable
fun DashboardScreen(nav: NavHostController, settings: Settings) {
    val repo = LocalContainer.current.repo
    val money = LocalMoney.current
    val colors = LocalPurseColors.current
    var monthStr by rememberSaveable { mutableStateOf(YearMonth.now().toString()) }
    val month = YearMonth.parse(monthStr)
    val range = monthRange(month)

    val accounts by repo.accounts.collectAsStateWithLifecycle()
    val balances by repo.balances.collectAsStateWithLifecycle()
    val categories by repo.categories.collectAsStateWithLifecycle()
    val txns by remember(range) { repo.txnsBetween(range.first, range.second) }.collectAsStateWithLifecycle(emptyList())
    val recent by remember { repo.recent(5) }.collectAsStateWithLifecycle(emptyList())

    val catMap = categories.associateBy { it.id }
    val accMap = accounts.associateBy { it.id }
    val included = accounts.filter { !it.excludeFromTotal && !it.archived }
    val net = included.sumOf { balances[it.id] ?: 0L }
    val excluded = accounts.filter { it.excludeFromTotal && !it.archived }.sumOf { balances[it.id] ?: 0L }
    val stats = monthStats(txns)

    PurseScaffold(
        title = settings.appName,
        tab = true,
        actions = { IconButton(onClick = { nav.navigate("settings") }) { Icon(Icons.Filled.Settings, "Settings") } },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard {
                Text("Balance", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    money.format(net),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = if (net < 0) colors.expense else MaterialTheme.colorScheme.onSurface,
                )
                if (excluded != 0L) {
                    Text(
                        "+ ${money.format(excluded)} in excluded accounts",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            MonthSwitcher(month) { monthStr = it.toString() }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionCard(Modifier.weight(1f), title = "Spent") {
                    Text(money.format(stats.spent), style = MaterialTheme.typography.titleLarge, color = colors.expense, fontWeight = FontWeight.SemiBold)
                }
                SectionCard(Modifier.weight(1f), title = "Earned") {
                    Text(money.format(stats.earned), style = MaterialTheme.typography.titleLarge, color = colors.income, fontWeight = FontWeight.SemiBold)
                }
            }

            if (settings.monthlyBudget > 0) {
                val used = stats.spent.toFloat() / settings.monthlyBudget
                SectionCard(title = "Budget") {
                    LinearProgressIndicator(
                        progress = { used.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(8.dp),
                        color = if (used > 1f) colors.expense else if (used > 0.8f) colors.warning else MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    )
                    Spacer(Modifier.height(6.dp))
                    val left = settings.monthlyBudget - stats.spent
                    Text(
                        if (left >= 0) "${money.format(left)} left of ${money.format(settings.monthlyBudget)}"
                        else "${money.format(-left)} over ${money.format(settings.monthlyBudget)}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            if (stats.spent > 0) {
                SectionCard(title = "Daily spending") {
                    DailyBars(month, stats.byDay, colors.expense)
                }
                SectionCard(title = "Where it went") {
                    stats.byCategory.take(6).forEach { (catId, amount) ->
                        val cat = catId?.let { catMap[it] }
                        CategoryBar(
                            label = (cat?.icon?.let { "$it " } ?: "") + (cat?.name ?: "Uncategorised"),
                            amount = money.format(amount),
                            fraction = amount.toFloat() / stats.spent,
                            color = colors.expense,
                        )
                    }
                }
            }

            SectionCard(title = "Recent") {
                if (recent.isEmpty()) EmptyHint("No transactions yet. Tap + to add one.")
                recent.forEach { t ->
                    TxnRow(t, catMap, accMap, onClick = { nav.addTxn(id = t.id) }, showDate = true, horizontalPadding = 0.dp)
                }
                TextButton(onClick = { nav.navigate("txns") }, modifier = Modifier.align(Alignment.End)) { Text("See all") }
            }
            Spacer(Modifier.height(80.dp))
        }
    }
}

/** One thin bar per day of the month in a single hue, scaled to the busiest day. */
@Composable
private fun DailyBars(month: YearMonth, byDay: Map<Int, Long>, color: Color) {
    val days = month.lengthOfMonth()
    val max = (byDay.values.maxOrNull() ?: 0L).coerceAtLeast(1L)
    val money = LocalMoney.current
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Column {
        Text(
            "Peak ${money.format(max)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Canvas(Modifier.fillMaxWidth().height(72.dp).padding(top = 4.dp)) {
            val slot = size.width / days
            val barW = (slot - 2.dp.toPx()).coerceAtLeast(1f)
            for (d in 1..days) {
                val v = byDay[d] ?: 0L
                val x = (d - 1) * slot
                drawLine(track, Offset(x, size.height), Offset(x + barW, size.height), strokeWidth = 1.dp.toPx())
                if (v > 0) {
                    val h = (v.toFloat() / max * size.height).coerceAtLeast(2.dp.toPx())
                    drawRoundRect(
                        color = color,
                        topLeft = Offset(x, size.height - h),
                        size = Size(barW, h),
                        cornerRadius = CornerRadius(2.dp.toPx()),
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf(1, (days + 1) / 2, days).forEach {
                Text("$it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun CategoryBar(label: String, amount: String, fraction: Float, color: Color) {
    Column(Modifier.padding(vertical = 5.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(amount, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                "  ${(fraction * 100).toInt()}%",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.size(4.dp))
        Box(
            Modifier.fillMaxWidth().height(6.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(3.dp)),
        ) {
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0.01f, 1f)).fillMaxHeight().background(color, RoundedCornerShape(3.dp)))
        }
    }
}
