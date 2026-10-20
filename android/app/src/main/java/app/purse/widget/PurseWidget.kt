package app.purse.widget

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import app.purse.MainActivity
import app.purse.container
import app.purse.core.Money
import app.purse.data.AccountEntity
import app.purse.data.DarkMode
import app.purse.data.Settings
import app.purse.data.TxnEntity
import app.purse.ui.monthRange
import app.purse.ui.monthStats
import app.purse.ui.theme.Palette
import app.purse.ui.theme.Palettes
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

private val VIEW = stringPreferencesKey("view")

/** Views the widget cycles through when its title is tapped. */
private enum class WidgetView(val title: String) { OVERVIEW("Overview"), ACCOUNTS("Accounts"), TODAY("Today") }

class PurseWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val c = context.container
        val (from, to) = monthRange(YearMonth.now())
        val monthFlow = c.repo.txnsBetween(from, to)
        val night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        provideContent {
            val settings by c.settings.flow.collectAsState(initial = Settings())
            val accounts by c.repo.accounts.collectAsState()
            val balances by c.repo.balances.collectAsState()
            val month by remember { monthFlow }.collectAsState(initial = emptyList())
            val view = WidgetView.entries.firstOrNull { it.name == currentState<Preferences>()[VIEW] } ?: WidgetView.OVERVIEW
            val dark = when (settings.darkMode) {
                DarkMode.DARK -> true
                DarkMode.LIGHT -> false
                DarkMode.SYSTEM -> night
            }
            Content(view, Palettes.resolve(settings.theme, dark), settings, accounts, balances, month)
        }
    }

    companion object {
        suspend fun refresh(context: Context) {
            val manager = GlanceAppWidgetManager(context)
            manager.getGlanceIds(PurseWidget::class.java).forEach { PurseWidget().update(context, it) }
        }
    }
}

class PurseWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PurseWidget()
}

class CycleViewAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        updateAppWidgetState(context, glanceId) { prefs ->
            val current = WidgetView.entries.firstOrNull { it.name == prefs[VIEW] } ?: WidgetView.OVERVIEW
            prefs[VIEW] = WidgetView.entries[(current.ordinal + 1) % WidgetView.entries.size].name
        }
        PurseWidget().update(context, glanceId)
    }
}

@Composable
private fun Content(
    view: WidgetView,
    p: Palette,
    s: Settings,
    accounts: List<AccountEntity>,
    balances: Map<String, Long>,
    month: List<TxnEntity>,
) {
    fun fmt(v: Long) = Money.format(v, s.currency, s.indianGrouping)
    val fg = ColorProvider(p.fg)
    val muted = ColorProvider(p.muted)
    val context = androidx.glance.LocalContext.current
    val addIntent = Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_ADD)

    Column(
        modifier = GlanceModifier.fillMaxSize().background(p.bg).cornerRadius(20.dp).padding(14.dp),
    ) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${s.appName} · ${view.title} ›",
                style = TextStyle(color = muted, fontSize = 13.sp),
                modifier = GlanceModifier.defaultWeight().clickable(actionRunCallback<CycleViewAction>()),
            )
            Text(
                text = "  ＋  ",
                style = TextStyle(color = ColorProvider(p.bg), fontSize = 16.sp, fontWeight = FontWeight.Bold),
                modifier = GlanceModifier.background(p.primary).cornerRadius(14.dp).padding(4.dp)
                    .clickable(actionStartActivity(addIntent)),
            )
        }
        Spacer(GlanceModifier.height(8.dp))
        val open = GlanceModifier.clickable(actionStartActivity(Intent(context, MainActivity::class.java)))
        when (view) {
            WidgetView.OVERVIEW -> {
                val net = accounts.filter { !it.excludeFromTotal && !it.archived }.sumOf { balances[it.id] ?: 0L }
                val stats = monthStats(month)
                Column(modifier = open) {
                    Text("Balance", style = TextStyle(color = muted, fontSize = 12.sp))
                    Text(fmt(net), style = TextStyle(color = fg, fontSize = 26.sp, fontWeight = FontWeight.Bold))
                    Spacer(GlanceModifier.height(6.dp))
                    Row {
                        Stat("Spent", fmt(stats.spent), p.red, muted)
                        Spacer(GlanceModifier.width(16.dp))
                        Stat("Earned", fmt(stats.earned), p.green, muted)
                        if (s.monthlyBudget > 0) {
                            Spacer(GlanceModifier.width(16.dp))
                            Stat("Left", fmt(s.monthlyBudget - stats.spent), p.yellow, muted)
                        }
                    }
                }
            }
            WidgetView.ACCOUNTS -> Column(modifier = open) {
                accounts.filter { !it.archived }.take(5).forEach { a ->
                    Row(modifier = GlanceModifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Text(
                            "${a.type.emoji} ${a.name}" + if (a.excludeFromTotal) " ·" else "",
                            style = TextStyle(color = fg, fontSize = 14.sp),
                            modifier = GlanceModifier.defaultWeight(),
                        )
                        val b = balances[a.id] ?: 0L
                        Text(fmt(b), style = TextStyle(color = ColorProvider(if (b < 0) p.red else p.fg), fontSize = 14.sp))
                    }
                }
            }
            WidgetView.TODAY -> {
                val start = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                val today = month.filter { it.timestamp >= start }
                val stats = monthStats(today)
                Column(modifier = open) {
                    Text("Spent today", style = TextStyle(color = muted, fontSize = 12.sp))
                    Text(fmt(stats.spent), style = TextStyle(color = ColorProvider(p.red), fontSize = 24.sp, fontWeight = FontWeight.Bold))
                    today.take(3).forEach { t ->
                        Text(
                            "${fmt(t.amount)}  ${t.note}".trim(),
                            style = TextStyle(color = muted, fontSize = 12.sp),
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, color: Color, muted: ColorProvider) {
    Column {
        Text(label, style = TextStyle(color = muted, fontSize = 11.sp))
        Text(value, style = TextStyle(color = ColorProvider(color), fontSize = 15.sp, fontWeight = FontWeight.Medium))
    }
}
