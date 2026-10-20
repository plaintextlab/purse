package app.purse.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.purse.AppContainer
import app.purse.core.Money
import app.purse.data.AccountEntity
import app.purse.data.CategoryEntity
import app.purse.data.Settings
import app.purse.data.TxnEntity
import app.purse.data.TxnType
import app.purse.ui.theme.LocalPurseColors
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

val LocalContainer = staticCompositionLocalOf<AppContainer> { error("No container") }

/** Formats money with the user's currency settings. */
class MoneyFormat(private val symbol: String, private val indian: Boolean) {
    fun format(minor: Long, signed: Boolean = false) = Money.format(minor, symbol, indian, signed)
    fun compact(minor: Long) = Money.compact(minor, indian)
}

val LocalMoney = staticCompositionLocalOf { MoneyFormat("₹", true) }

fun Settings.moneyFormat() = MoneyFormat(currency, indianGrouping)

// ---- dates ----

val zone: ZoneId get() = ZoneId.systemDefault()

/** [from, to) epoch millis of a month in the local zone. */
fun monthRange(ym: YearMonth): Pair<Long, Long> =
    ym.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli() to
        ym.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()

fun Long.toLocalDate(): LocalDate = Instant.ofEpochMilli(this).atZone(zone).toLocalDate()

private val monthFmt = DateTimeFormatter.ofPattern("MMMM yyyy")
private val dayFmt = DateTimeFormatter.ofPattern("EEE, d MMM")
private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")

fun YearMonth.label(): String = format(monthFmt)

fun LocalDate.label(): String = when (this) {
    LocalDate.now() -> "Today"
    LocalDate.now().minusDays(1) -> "Yesterday"
    else -> format(dayFmt)
}

fun Long.timeLabel(): String = Instant.ofEpochMilli(this).atZone(zone).format(timeFmt)

// ---- stats ----

data class MonthStats(
    val spent: Long,
    val earned: Long,
    /** categoryId (null = uncategorised) to amount, largest first. */
    val byCategory: List<Pair<String?, Long>>,
    /** day of month to amount spent. */
    val byDay: Map<Int, Long>,
)

/** Transfers and balance adjustments are movements, not spending, so they are left out. */
fun monthStats(txns: List<TxnEntity>): MonthStats {
    val expenses = txns.filter { it.type == TxnType.EXPENSE }
    return MonthStats(
        spent = expenses.sumOf { it.amount },
        earned = txns.filter { it.type == TxnType.INCOME }.sumOf { it.amount },
        byCategory = expenses.groupBy { it.categoryId }.map { (k, v) -> k to v.sumOf { it.amount } }.sortedByDescending { it.second },
        byDay = expenses.groupBy { it.timestamp.toLocalDate().dayOfMonth }.mapValues { (_, v) -> v.sumOf { it.amount } },
    )
}

// ---- building blocks ----

/**
 * Screen frame with a top bar. Tab screens sit above the bottom navigation bar,
 * which already handles the system navigation inset.
 */
@Composable
fun PurseScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    tab: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
    floating: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    }
                },
                actions = actions,
            )
        },
        floatingActionButton = floating,
        contentWindowInsets = if (tab) WindowInsets(0, 0, 0, 0) else WindowInsets.navigationBars.only(WindowInsetsSides.Bottom),
        content = content,
    )
}

@Composable
fun SectionCard(modifier: Modifier = Modifier, title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            if (title != null) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
            }
            content()
        }
    }
}

@Composable
fun EmojiBadge(emoji: String, tint: Color = MaterialTheme.colorScheme.secondaryContainer) {
    Box(Modifier.size(40.dp).background(tint, CircleShape), contentAlignment = Alignment.Center) {
        Text(emoji.ifBlank { "•" }, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
fun MonthSwitcher(month: YearMonth, onChange: (YearMonth) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        IconButton(onClick = { onChange(month.minusMonths(1)) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous month") }
        Text(
            month.label(),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.clickable { onChange(YearMonth.now()) },
        )
        IconButton(onClick = { onChange(month.plusMonths(1)) }, enabled = month < YearMonth.now()) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next month")
        }
    }
}

@Composable
fun amountColor(t: TxnEntity): Color {
    val c = LocalPurseColors.current
    return when (t.type) {
        TxnType.EXPENSE -> c.expense
        TxnType.INCOME -> c.income
        TxnType.TRANSFER -> c.transfer
        TxnType.ADJUSTMENT -> if (t.amount < 0) c.expense else c.income
    }
}

@Composable
fun TxnRow(
    t: TxnEntity,
    categories: Map<String, CategoryEntity>,
    accounts: Map<String, AccountEntity>,
    onClick: () -> Unit,
    showDate: Boolean = false,
    horizontalPadding: Dp = 16.dp,
) {
    val money = LocalMoney.current
    val cat = t.categoryId?.let { categories[it] }
    val account = accounts[t.accountId]?.name ?: "?"
    val (emoji, title, subtitle) = when (t.type) {
        TxnType.TRANSFER -> Triple("🔄", "Transfer", "$account → ${accounts[t.toAccountId]?.name ?: "?"}")
        TxnType.ADJUSTMENT -> Triple("⚖️", "Balance adjustment", account)
        else -> Triple(cat?.icon ?: "❔", cat?.name ?: "Uncategorised", account)
    }
    val sign = when (t.type) {
        TxnType.EXPENSE -> -t.amount
        TxnType.INCOME, TxnType.ADJUSTMENT -> t.amount
        TxnType.TRANSFER -> t.amount
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = horizontalPadding, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EmojiBadge(emoji)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(
                t.note.ifBlank { title },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val detail = buildList {
                if (t.note.isNotBlank()) add(title)
                add(subtitle)
                add(if (showDate) t.timestamp.toLocalDate().label() else t.timestamp.timeLabel())
                if (t.source.name == "SMS") add("SMS")
            }.joinToString(" · ")
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            money.format(sign, signed = t.type != TxnType.TRANSFER),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = amountColor(t),
        )
    }
}

@Composable
fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.fillMaxWidth().padding(32.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}
