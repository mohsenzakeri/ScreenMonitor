package com.example.screentrack

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.room.Room
import com.example.screentrack.data.DailyEntry
import com.example.screentrack.data.ScreenTimeDatabase
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import kotlin.math.max

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val db = ScreenTimeDatabase.get(this)
        setContent { ScreenTrackApp(this, db) }
    }
}

private enum class Tab { HOME, ANALYTICS, HISTORY }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScreenTrackApp(context: Context, db: ScreenTimeDatabase) {
    val entries by db.entries().observeAll().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(Tab.HOME) }
    var showEditor by remember { mutableStateOf(false) }
    var editDate by remember { mutableStateOf(LocalDate.now()) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri: Uri? ->
        if (uri != null) scope.launch { exportCsv(context, uri, entries) }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) scope.launch {
            runCatching { importCsv(context, uri, db) }
                .onFailure { android.widget.Toast.makeText(context, "Import failed: " + (it.message ?: "Invalid CSV"), android.widget.Toast.LENGTH_LONG).show() }
                .onSuccess { count -> android.widget.Toast.makeText(context, "Imported " + count + " entries", android.widget.Toast.LENGTH_LONG).show() }
        }
    }

    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF536DFE),
            secondary = Color(0xFF13A89E),
            background = Color(0xFFF7F8FC),
            surface = Color.White
        )
    ) {
        Scaffold(
            containerColor = Color(0xFFF7F8FC),
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("ScreenTrack", fontWeight = FontWeight.Bold)
                            Text("Your screen-time journal", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    actions = {
                        IconButton(onClick = { importLauncher.launch(arrayOf("text/*", "application/csv")) }) {
                            Icon(Icons.Default.Add, contentDescription = "Import CSV")
                        }
                        IconButton(onClick = { exportLauncher.launch("screentrack-${LocalDate.now()}.csv") }) {
                            Icon(Icons.Default.SaveAlt, contentDescription = "Export CSV")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFFF7F8FC))
                )
            },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(selected = tab == Tab.HOME, onClick = { tab = Tab.HOME }, icon = { Icon(Icons.Default.Add, null) }, label = { Text("Entry") })
                    NavigationBarItem(selected = tab == Tab.ANALYTICS, onClick = { tab = Tab.ANALYTICS }, icon = { Icon(Icons.Default.BarChart, null) }, label = { Text("Analytics") })
                    NavigationBarItem(selected = tab == Tab.HISTORY, onClick = { tab = Tab.HISTORY }, icon = { Icon(Icons.Default.History, null) }, label = { Text("History") })
                }
            },
            floatingActionButton = {
                if (tab == Tab.HOME || tab == Tab.HISTORY) {
                    FloatingActionButton(onClick = { editDate = LocalDate.now(); showEditor = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Add entry")
                    }
                }
            }
        ) { padding ->
            when (tab) {
                Tab.HOME -> HomeScreen(
                    entries = entries,
                    modifier = Modifier.padding(padding),
                    onAdd = { date -> editDate = date; showEditor = true }
                )
                Tab.ANALYTICS -> AnalyticsScreen(entries, Modifier.padding(padding))
                Tab.HISTORY -> HistoryScreen(entries, Modifier.padding(padding), onEdit = { date -> editDate = date; showEditor = true })
            }
        }

        if (showEditor) {
            EntryDialog(
                date = editDate,
                existing = entries.firstOrNull { it.date == editDate.toString() },
                onDismiss = { showEditor = false },
                onSave = { selectedDate, minutes, mood, note ->
                    scope.launch {
                        db.entries().upsert(DailyEntry(selectedDate.toString(), minutes, note, mood))
                        showEditor = false
                    }
                },
                onDelete = {
                    scope.launch { db.entries().delete(editDate.toString()); showEditor = false }
                }
            )
        }
    }
}

@Composable
private fun HomeScreen(entries: List<DailyEntry>, modifier: Modifier = Modifier, onAdd: (LocalDate) -> Unit) {
    val yesterday = LocalDate.now().minusDays(1)
    val yesterdayEntry = entries.firstOrNull { it.date == yesterday.toString() }
    val sevenDayStart = yesterday.minusDays(6)
    val weekEntries = entries.filter { runCatching { LocalDate.parse(it.date) }.getOrNull() in sevenDayStart..yesterday }
    val average = if (weekEntries.isEmpty()) 0 else weekEntries.sumOf { it.minutes } / weekEntries.size
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF202B52))) {
                Column(Modifier.fillMaxWidth().padding(20.dp)) {
                    Text("YESTERDAY", color = Color(0xFFB9C5F5), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(yesterdayEntry?.let { formatDuration(it.minutes) } ?: "Not entered yet", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    Text(if (yesterdayEntry == null) "Add yesterday's screen time to get started" else "Your screen-time total for yesterday", color = Color(0xFFD8DDF2))
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { onAdd(yesterday) }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7D91FF))) {
                        Text(if (yesterdayEntry == null) "Add yesterday's entry" else "Edit yesterday's entry")
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard("7-day average", formatDuration(average), Modifier.weight(1f))
                StatCard("Days recorded", "${entries.size}", Modifier.weight(1f))
            }
        }
        item { Text("Last 7 days", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
        item { WeeklyBars(entries) }
        item {
            Text("Quick entry", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (0..6).map { yesterday.minusDays((6 - it).toLong()) }.forEach { day ->
                    val exists = entries.any { it.date == day.toString() }
                    Surface(
                        modifier = Modifier.weight(1f).clickable { onAdd(day) },
                        shape = RoundedCornerShape(12.dp),
                        color = if (exists) Color(0xFFE3E8FF) else Color.White
                    ) {
                        Column(Modifier.padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(day.dayOfWeek.name.take(1), fontSize = 11.sp)
                            Text(day.dayOfMonth.toString(), fontWeight = FontWeight.Bold)
                            if (exists) Box(Modifier.padding(top = 3.dp).size(5.dp).background(Color(0xFF536DFE), CircleShape))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier, shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(16.dp)) {
            Text(label, fontSize = 12.sp, color = Color.Gray)
            Spacer(Modifier.height(6.dp))
            Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun WeeklyBars(entries: List<DailyEntry>) {
    val yesterday = LocalDate.now().minusDays(1)
    val days = (6 downTo 0).map { yesterday.minusDays(it.toLong()) }
    val values = days.map { d -> entries.firstOrNull { it.date == d.toString() }?.minutes ?: 0 }
    val maxVal = max(60, values.maxOrNull() ?: 60)
    Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth().height(130.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                values.forEachIndexed { i, v ->
                    Column(
                        Modifier.weight(1f).fillMaxHeight(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Reserve a separate chart area for the value and bar.
                        // The weekday label is outside it, so tall bars cannot
                        // overflow the row or shift the baseline.
                        Box(
                            Modifier.weight(1f).fillMaxWidth(),
                            contentAlignment = Alignment.BottomCenter
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(if (v == 0) "–" else "${v / 60}h", fontSize = 10.sp, color = Color.Gray)
                                Spacer(Modifier.height(5.dp))
                                Box(
                                    Modifier.width(20.dp)
                                        .height((if (v == 0) 3f else (v.toFloat() / maxVal * 75f).coerceAtLeast(5f)).dp)
                                        .background(
                                            if (i == 6) Color(0xFF536DFE) else Color(0xFFAEBBFF),
                                            RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp)
                                        )
                                )
                            }
                        }
                        Spacer(Modifier.height(7.dp))
                        Text(daysLabel(days[i]), fontSize = 10.sp, color = Color.Gray)
                    }
                }
            }
        }
    }
}

private fun daysLabel(date: LocalDate) = date.dayOfWeek.name.take(1)

@Composable
private fun AnalyticsScreen(entries: List<DailyEntry>, modifier: Modifier = Modifier) {
    var month by remember { mutableStateOf(YearMonth.now()) }
    var resolution by remember { mutableStateOf("Week") }
    var weekStart by remember { mutableStateOf(LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))) }
    val parsed = entries.mapNotNull { e -> runCatching { LocalDate.parse(e.date) to e }.getOrNull() }.sortedBy { it.first }
    val total = parsed.sumOf { it.second.minutes }
    val avg = if (parsed.isEmpty()) 0 else total / parsed.size
    val highest = parsed.maxByOrNull { it.second.minutes }
    val trend = remember(parsed, resolution) {
        when (resolution) {
            "Day" -> parsed.map { it.first to it.second.minutes }
            "Week" -> parsed.groupBy { it.first.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) }.toSortedMap()
                .map { (d, list) -> d to (list.sumOf { it.second.minutes } / list.size) }
            else -> parsed.groupBy { YearMonth.from(it.first) }.toSortedMap()
                .map { (m, list) -> m.atDay(1) to (list.sumOf { it.second.minutes } / list.size) }
        }
    }
    val monthEntries = parsed.filter { YearMonth.from(it.first) == month }.map { it.second }
    val prevMonth = parsed.filter { YearMonth.from(it.first) == month.minusMonths(1) }.map { it.second }
    val monthAvg = if (monthEntries.isEmpty()) 0 else monthEntries.sumOf { it.minutes } / monthEntries.size
    val prevMonthAvg = if (prevMonth.isEmpty()) 0 else prevMonth.sumOf { it.minutes } / prevMonth.size
    val weekEnd = weekStart.plusDays(6)
    val thisWeek = parsed.filter { it.first in weekStart..weekEnd }.map { it.second }
    val lastWeek = parsed.filter { it.first in weekStart.minusDays(7)..weekStart.minusDays(1) }.map { it.second }
    val weekAvg = if (thisWeek.isEmpty()) 0 else thisWeek.sumOf { it.minutes } / thisWeek.size
    val lastWeekAvg = if (lastWeek.isEmpty()) 0 else lastWeek.sumOf { it.minutes } / lastWeek.size

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text("Analytics", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text("Your screen-time patterns across all recorded dates", color = Color.Gray, fontSize = 13.sp)
        }
        item {
            Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Overall trend", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("Day", "Week", "Month").forEach { option ->
                            FilterChip(selected = resolution == option, onClick = { resolution = option }, label = { Text(option) })
                        }
                    }
                    if (trend.isEmpty()) Text("Add entries to see your trend.", color = Color.Gray)
                    else {
                        TrendChart(trend, resolution)
                        Text("All available history is included. Each point represents average screen time per recorded day in the selected " + resolution.lowercase() + " period; missing dates are excluded.", color = Color.Gray, fontSize = 11.sp)
                    }
                }
            }
        }
        item { AllTimeDailyAverageChart(parsed) }
        item {
            Text("All-time overview", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard("Total screen time", formatDuration(total), Modifier.weight(1f))
                StatCard("Daily average", formatDuration(avg), Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard("Recorded days", parsed.size.toString(), Modifier.weight(1f))
                StatCard("Highest day", highest?.let { formatDuration(it.second.minutes) } ?: "—", Modifier.weight(1f))
            }
            if (parsed.isNotEmpty()) {
                Text("Recorded from " + parsed.first().first.format(DateTimeFormatter.ofPattern("MMM d, yyyy")) + " to " + parsed.last().first.format(DateTimeFormatter.ofPattern("MMM d, yyyy")), color = Color.Gray, fontSize = 12.sp)
            }
        }
        item { WeekdayAnalysis(parsed) }
        item { MoodCorrelation(parsed) }
        item {
            Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Week by week", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { weekStart = weekStart.minusWeeks(1) }) { Text("‹ Previous") }
                        Text(weekStart.format(DateTimeFormatter.ofPattern("MMM d")) + " – " + weekEnd.format(DateTimeFormatter.ofPattern("MMM d")), fontWeight = FontWeight.SemiBold)
                        TextButton(onClick = { if (weekStart.plusWeeks(1) <= LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))) weekStart = weekStart.plusWeeks(1) }) { Text("Next ›") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatCard("Weekly total", formatDuration(thisWeek.sumOf { it.minutes }), Modifier.weight(1f))
                        StatCard("Daily average", formatDuration(weekAvg), Modifier.weight(1f))
                    }
                    WeeklyBarsForRange(weekStart, thisWeek)
                    val delta = weekAvg - lastWeekAvg
                    Text(if (thisWeek.isEmpty()) "No entries recorded for this week." else if (lastWeek.isEmpty()) "Add entries for the previous week to compare." else formatDuration(kotlin.math.abs(delta)) + " " + (if (delta > 0) "higher" else if (delta < 0) "lower" else "the same") + " than the previous week's average per recorded day.", color = Color.Gray, fontSize = 12.sp)
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text("Monthly detail", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { month = month.minusMonths(1) }) { Text("‹") }
                    Text(month.format(DateTimeFormatter.ofPattern("MMM yyyy")), fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = { if (month < YearMonth.now()) month = month.plusMonths(1) }) { Text("›") }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard("Monthly average", formatDuration(monthAvg), Modifier.weight(1f))
                StatCard("Recorded days", monthEntries.size.toString(), Modifier.weight(1f))
            }
        }
        item {
            val delta = monthAvg - prevMonthAvg
            Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Compared with previous month", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(if (prevMonth.isEmpty() || monthEntries.isEmpty()) "Add more entries to see a comparison." else formatDuration(kotlin.math.abs(delta)) + " " + (if (delta > 0) "higher" else if (delta < 0) "lower" else "the same") + " than " + month.minusMonths(1).format(DateTimeFormatter.ofPattern("MMM")) + " (average per recorded day).", color = Color.Gray)
                }
            }
        }
        item { Text("Daily minutes", fontWeight = FontWeight.Bold, fontSize = 18.sp) }
        item { MonthBars(month, monthEntries) }
        item { Text("Monthly calendar", fontWeight = FontWeight.Bold, fontSize = 18.sp) }
        item { Heatmap(month, monthEntries) }
    }
}

@Composable
private fun MoodCorrelation(parsed: List<Pair<LocalDate, DailyEntry>>) {
    val points = parsed.map { it.second }.filter { it.mood != null }.map { it.minutes.toDouble() to it.mood!!.toDouble() }
    val r = pearsonCorrelation(points)
    Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Mood vs. screen time", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text("Each point represents one day with both values recorded.", color = Color.Gray, fontSize = 12.sp)
            if (points.size < 3) Text("Record mood for at least 3 days to begin. You have ${points.size} paired days.", color = Color.Gray, fontSize = 13.sp)
            else if (r == null) Text("Not enough variation in the recorded values to calculate a correlation yet.", color = Color.Gray, fontSize = 13.sp)
            else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column { Text("Pearson correlation", color = Color.Gray, fontSize = 11.sp); Text("%+.2f".format(java.util.Locale.US, r), fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color(0xFF536DFE)) }
                    Text("${points.size} paired days", color = Color.Gray, fontSize = 12.sp)
                }
                MoodScatterPlot(points)
                Text(when { r <= -0.6 -> "Strong negative association."; r <= -0.2 -> "Moderate negative association."; r < 0.2 -> "Little linear association in the current entries."; r < 0.6 -> "Moderate positive association."; else -> "Strong positive association." }, color = Color.Gray, fontSize = 12.sp)
            }
            Text("Correlation shows association, not cause and effect. More recorded days can make the pattern easier to interpret.", color = Color.Gray, fontSize = 11.sp)
        }
    }
}
private fun pearsonCorrelation(points: List<Pair<Double, Double>>): Double? {
    if (points.size < 2) return null
    val mx = points.map { it.first }.average(); val my = points.map { it.second }.average()
    val num = points.sumOf { (x, y) -> (x - mx) * (y - my) }
    val sx = points.sumOf { (x, _) -> (x - mx) * (x - mx) }; val sy = points.sumOf { (_, y) -> (y - my) * (y - my) }
    val den = kotlin.math.sqrt(sx * sy)
    return if (den == 0.0) null else (num / den).coerceIn(-1.0, 1.0)
}
@Composable
private fun MoodScatterPlot(points: List<Pair<Double, Double>>) {
    val minX = points.minOf { it.first }; val maxX = points.maxOf { it.first }
    val mx = points.map { it.first }.average(); val my = points.map { it.second }.average()
    val vx = points.sumOf { (x, _) -> (x - mx) * (x - mx) }
    val slope = if (vx == 0.0) 0.0 else points.sumOf { (x, y) -> (x - mx) * (y - my) } / vx
    val intercept = my - slope * mx
    Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFFF7F8FC))) {
        Column(Modifier.fillMaxWidth().padding(10.dp)) {
            Canvas(Modifier.fillMaxWidth().height(160.dp)) {
                val l = 10.dp.toPx(); val rr = size.width - 8.dp.toPx(); val t = 8.dp.toPx(); val b = size.height - 8.dp.toPx()
                fun xp(x: Double) = l + ((x - minX) / (maxX - minX).coerceAtLeast(1.0)).toFloat() * (rr - l)
                fun yp(y: Double) = b - ((y - 1.0) / 4.0).toFloat() * (b - t)
                repeat(5) { i -> val y = t + (b - t) * i / 4f; drawLine(Color(0xFFE1E5F0), Offset(l, y), Offset(rr, y), strokeWidth = 1.dp.toPx()) }
                drawLine(Color(0xFF536DFE), Offset(xp(minX), yp((intercept + slope * minX).coerceIn(1.0, 5.0))), Offset(xp(maxX), yp((intercept + slope * maxX).coerceIn(1.0, 5.0))), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                points.forEach { (x, y) -> drawCircle(Color(0xFF536DFE), radius = 4.dp.toPx(), center = Offset(xp(x), yp(y))) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Less screen time", fontSize = 10.sp, color = Color.Gray); Text("More screen time", fontSize = 10.sp, color = Color.Gray) }
            Text("Mood score: 1 😞 to 5 😄", fontSize = 10.sp, color = Color.Gray)
        }
    }
}

@Composable
private fun WeekdayAnalysis(parsed: List<Pair<LocalDate, DailyEntry>>) {
    val averages = DayOfWeek.values().map { day ->
        val matching = parsed.filter { it.first.dayOfWeek == day }
        day to (if (matching.isEmpty()) null else matching.sumOf { it.second.minutes } / matching.size)
    }
    val available = averages.mapNotNull { it.second }
    val maxAverage = max(60, available.maxOrNull() ?: 60)
    val highest = averages.filter { it.second != null }.maxByOrNull { it.second!! }
    Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Day-of-week analysis", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text("Average screen time for each weekday, using only days with recorded entries.", color = Color.Gray, fontSize = 12.sp)
            if (parsed.isEmpty()) {
                Text("Add entries to compare weekdays.", color = Color.Gray)
            } else {
                Row(Modifier.fillMaxWidth().height(142.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    averages.forEach { (day, average) ->
                        Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(average?.let { "${it / 60}h" } ?: "—", fontSize = 9.sp, color = Color.Gray)
                                    Spacer(Modifier.height(4.dp))
                                    Box(
                                        Modifier.width(18.dp).height((average?.let { (it.toFloat() / maxAverage * 82f).coerceAtLeast(4f) } ?: 2f).dp)
                                            .background(if (highest?.first == day) Color(0xFF536DFE) else Color(0xFFAEBBFF), RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp))
                                    )
                                }
                            }
                            Spacer(Modifier.height(5.dp))
                            Text(day.name.take(1), fontSize = 10.sp, color = Color.Gray)
                        }
                    }
                }
                averages.forEach { (day, average) ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(day.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault()), fontSize = 12.sp)
                        Text(average?.let { formatDuration(it) } ?: "No data", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF536DFE))
                    }
                }
                Text("Average is calculated per recorded occurrence of each weekday; missing dates are excluded.", color = Color.Gray, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun AllTimeDailyAverageChart(parsed: List<Pair<LocalDate, DailyEntry>>) {
    val sorted = parsed.sortedBy { it.first }
    var runningTotal = 0L
    val cumulative = sorted.mapIndexed { index, (date, entry) ->
        runningTotal += entry.minutes
        date to (runningTotal.toDouble() / (index + 1))
    }
    Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("All-time daily average", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text("Cumulative average screen time per recorded day, from your first entry through each date.", color = Color.Gray, fontSize = 12.sp)
            if (cumulative.isEmpty()) {
                Text("Add entries to see how your daily average changes over time.", color = Color.Gray)
            } else {
                val maxValue = kotlin.math.max(60.0, cumulative.maxOf { it.second })
                val minDay = cumulative.first().first.toEpochDay()
                val maxDay = cumulative.last().first.toEpochDay()
                val span = (maxDay - minDay).coerceAtLeast(1L).toFloat()
                Text("Current average: " + formatDuration((runningTotal / cumulative.size).toInt()), fontWeight = FontWeight.SemiBold, color = Color(0xFF536DFE))
                Text("Average daily screen time (hours/day)", fontSize = 11.sp, color = Color.Gray)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.height(160.dp).width(42.dp), verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.End) {
                        (0..4).forEach { i ->
                            Text("%.1fh".format(java.util.Locale.US, maxValue * (4 - i) / 4 / 60.0), fontSize = 9.sp, color = Color.Gray)
                        }
                    }
                    Canvas(Modifier.weight(1f).height(168.dp).padding(start = 6.dp, top = 4.dp, bottom = 4.dp)) {
                        val left = 3.dp.toPx()
                        val right = size.width - 4.dp.toPx()
                        val top = 2.dp.toPx()
                        val bottom = size.height - 2.dp.toPx()
                        repeat(5) { i ->
                            val y = top + (bottom - top) * i / 4f
                            drawLine(Color(0xFFE1E5F0), Offset(left, y), Offset(right, y), strokeWidth = 1.dp.toPx())
                        }
                        val coords = cumulative.map { (date, avg) ->
                            Offset(
                                left + (date.toEpochDay() - minDay).toFloat() / span * (right - left),
                                bottom - (avg / maxValue).toFloat() * (bottom - top)
                            )
                        }
                        coords.zipWithNext().forEach { (a, b) ->
                            drawLine(Color(0xFF536DFE), a, b, strokeWidth = 2.5.dp.toPx(), cap = StrokeCap.Round)
                        }
                        coords.forEach { drawCircle(Color(0xFF536DFE), radius = 2.5.dp.toPx(), center = it) }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(start = 46.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(cumulative.first().first.format(DateTimeFormatter.ofPattern("MMM d, yy")), fontSize = 10.sp, color = Color.Gray)
                    Text(cumulative.last().first.format(DateTimeFormatter.ofPattern("MMM d, yy")), fontSize = 10.sp, color = Color.Gray)
                }
                Text("Date", modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center, fontSize = 11.sp, color = Color.Gray)
                Text("Only recorded days contribute to the average. Missing dates are excluded.", fontSize = 11.sp, color = Color.Gray)
            }
        }
    }
}

@Composable
private fun TrendChart(points: List<Pair<LocalDate, Int>>, resolution: String) {
    val maxValue = max(60, points.maxOfOrNull { it.second } ?: 60)
    val firstDay = points.first().first.toEpochDay()
    val lastDay = points.last().first.toEpochDay()
    val dateSpan = (lastDay - firstDay).coerceAtLeast(1L).toFloat()
    val dateFormat = DateTimeFormatter.ofPattern(if (resolution == "Month") "MMM yy" else "MMM d")
    val tickIndices = if (points.size == 1) listOf(0) else (0..4).map { i -> i * (points.size - 1) / 4 }.distinct()
    Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFFF7F8FC))) {
        Column(Modifier.fillMaxWidth().padding(10.dp)) {
            Text("Average daily screen time (hours/day)", fontSize = 11.sp, color = Color.Gray)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(
                    Modifier.height(166.dp).width(39.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.End
                ) {
                    (0..4).forEach { i ->
                        val minutes = maxValue * (4 - i) / 4
                        Text("%.1fh".format(java.util.Locale.US, minutes / 60.0), fontSize = 9.sp, color = Color.Gray)
                    }
                }
                Canvas(Modifier.weight(1f).height(174.dp).padding(start = 6.dp, top = 4.dp, bottom = 4.dp)) {
                    val left = 4.dp.toPx()
                    val right = size.width - 5.dp.toPx()
                    val top = 2.dp.toPx()
                    val bottom = size.height - 2.dp.toPx()
                    repeat(5) { i ->
                        val y = top + (bottom - top) * i / 4f
                        drawLine(Color(0xFFE1E5F0), Offset(left, y), Offset(right, y), strokeWidth = 1.dp.toPx())
                    }
                    val coords = points.map { (date, minutes) ->
                        val x = left + ((date.toEpochDay() - firstDay).toFloat() / dateSpan) * (right - left)
                        val y = bottom - (minutes.toFloat() / maxValue) * (bottom - top)
                        Offset(x, y)
                    }
                    coords.zipWithNext().forEach { (a, b) ->
                        drawLine(Color(0xFF536DFE), a, b, strokeWidth = 2.5.dp.toPx(), cap = StrokeCap.Round)
                    }
                    coords.forEach { drawCircle(Color(0xFF536DFE), radius = 3.dp.toPx(), center = it) }
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth().padding(start = 45.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                tickIndices.forEach { i ->
                    Text(points[i].first.format(dateFormat), fontSize = 9.sp, color = Color.Gray)
                }
            }
            Text("Date", modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center, fontSize = 11.sp, color = Color.Gray)
        }
    }
}

@Composable
private fun WeeklyBarsForRange(start: LocalDate, entries: List<DailyEntry>) {
    val map = entries.associate { LocalDate.parse(it.date) to it.minutes }
    val days = (0..6).map { start.plusDays(it.toLong()) }
    val values = days.map { map[it] ?: 0 }
    val maxVal = max(60, values.maxOrNull() ?: 60)
    val ticks = (0..3).map { i -> maxVal * (3 - i) / 3 }

    Row(Modifier.fillMaxWidth().height(120.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Column(
            Modifier.width(30.dp).height(92.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.End
        ) {
            ticks.forEach { Text("${it / 60}h", fontSize = 9.sp, color = Color.Gray) }
        }
        Row(
            Modifier.weight(1f).fillMaxHeight(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            values.forEachIndexed { index, value ->
                Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(if (value == 0) "–" else (value / 60).toString() + "h", fontSize = 9.sp, color = Color.Gray)
                            Spacer(Modifier.height(4.dp))
                            Box(
                                Modifier.fillMaxWidth(0.65f)
                                    .height((if (value == 0) 3f else (value.toFloat() / maxVal * 65f).coerceAtLeast(4f)).dp)
                                    .background(if (value == 0) Color(0xFFEDEFF5) else Color(0xFF8294FF), RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp))
                            )
                        }
                    }
                    Spacer(Modifier.height(5.dp))
                    Text(days[index].dayOfWeek.name.take(1), fontSize = 10.sp, color = Color.Gray)
                }
            }
        }
    }
}

@Composable
private fun MonthBars(month: YearMonth, entries: List<DailyEntry>) {
    val map = entries.associate { LocalDate.parse(it.date).dayOfMonth to it.minutes }
    val maxVal = max(60, map.values.maxOrNull() ?: 60)
    val ticks = (0..3).map { i -> maxVal * (3 - i) / 3 }
    Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth().height(115.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Column(
                    Modifier.width(30.dp).fillMaxHeight(),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.End
                ) {
                    ticks.forEach { Text("${it / 60}h", fontSize = 9.sp, color = Color.Gray) }
                }
                Row(
                    Modifier.weight(1f).fillMaxHeight(),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    (1..month.lengthOfMonth()).forEach { d ->
                        val v = map[d] ?: 0
                        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.BottomCenter) {
                            Box(
                                Modifier.fillMaxWidth().padding(horizontal = 1.dp)
                                    .height((if (v == 0) 2f else (v.toFloat() / maxVal * 100f).coerceAtLeast(3f)).dp)
                                    .background(if (v == 0) Color(0xFFEDEFF5) else Color(0xFF8294FF), RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("Day of month  ·  Each bar represents one day", fontSize = 11.sp, color = Color.Gray)
        }
    }
}

@Composable
private fun Heatmap(month: YearMonth, entries: List<DailyEntry>) {
    val map = entries.associate { LocalDate.parse(it.date).dayOfMonth to it.minutes }
    val maxVal = max(1, map.values.maxOrNull() ?: 1)
    val offset = month.atDay(1).dayOfWeek.value - 1
    Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                listOf("M","T","W","T","F","S","S").forEach { Text(it, Modifier.weight(1f), fontSize = 11.sp, color = Color.Gray) }
            }
            val cells = List(offset) { 0 } + (1..month.lengthOfMonth()).toList()
            cells.chunked(7).forEach { week ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    week.forEach { day ->
                        val v = map[day] ?: 0
                        val alpha = if (v == 0) 0.10f else (0.2f + 0.8f * v.toFloat() / maxVal).coerceIn(0.2f, 1f)
                        Box(Modifier.weight(1f).aspectRatio(1f).background(Color(0xFF536DFE).copy(alpha), RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
                            if (day > 0) Text("$day", fontSize = 10.sp, color = if (v > maxVal * .55) Color.White else Color(0xFF35406A))
                        }
                    }
                    repeat(7 - week.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            Text("Darker days indicate higher recorded screen time.", fontSize = 11.sp, color = Color.Gray)
        }
    }
}

@Composable
private fun HistoryScreen(entries: List<DailyEntry>, modifier: Modifier = Modifier, onEdit: (LocalDate) -> Unit) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("History", fontSize = 24.sp, fontWeight = FontWeight.Bold) }
        if (entries.isEmpty()) item { Text("No entries yet. Add your first daily total.") }
        items(entries, key = { it.date }) { entry ->
            val date = LocalDate.parse(entry.date)
            Card(Modifier.fillMaxWidth().clickable { onEdit(date) }, shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(date.format(DateTimeFormatter.ofPattern("EEE, MMM d, yyyy")), fontWeight = FontWeight.SemiBold)
                        Text((entry.mood?.let { moodEmoji(it) + "  " + moodLabel(it) } ?: "Mood not recorded") + (if (entry.note.isNotBlank()) " · " + entry.note else ""), color = Color.Gray, fontSize = 12.sp)
                    }
                    Text(formatDuration(entry.minutes), fontWeight = FontWeight.Bold, color = Color(0xFF536DFE))
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Default.Edit, contentDescription = "Edit", tint = Color.Gray)
                }
            }
        }
    }
}

private fun moodEmoji(mood: Int): String = when (mood) { 1 -> "😞"; 2 -> "🙁"; 3 -> "😐"; 4 -> "🙂"; else -> "😄" }
private fun moodLabel(mood: Int): String = when (mood) { 1 -> "Very bad"; 2 -> "Bad"; 3 -> "Okay"; 4 -> "Good"; else -> "Very good" }
@Composable
private fun MoodSelector(selectedMood: Int?, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        (1..5).forEach { value ->
            Surface(Modifier.weight(1f).clickable { onSelect(value) }, shape = RoundedCornerShape(12.dp), color = if (selectedMood == value) Color(0xFFE4E9FF) else Color(0xFFF7F8FC), border = if (selectedMood == value) androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFF536DFE)) else null) {
                Column(Modifier.padding(vertical = 9.dp), horizontalAlignment = Alignment.CenterHorizontally) { Text(moodEmoji(value), fontSize = 23.sp); Text(value.toString(), fontSize = 11.sp, fontWeight = FontWeight.Bold) }
            }
        }
    }
    Text(selectedMood?.let { moodLabel(it) } ?: "Select one face (1 = very bad, 5 = very good)", fontSize = 11.sp, color = Color.Gray)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EntryDialog(
    date: LocalDate,
    existing: DailyEntry?,
    onDismiss: () -> Unit,
    onSave: (LocalDate, Int, Int, String) -> Unit,
    onDelete: () -> Unit
) {
    var dateText by remember(date) { mutableStateOf(date.toString()) }
    var minutesText by remember(date, existing) { mutableStateOf(existing?.minutes?.toString() ?: "") }
    var note by remember(date, existing) { mutableStateOf(existing?.note ?: "") }
    var mood by remember(date, existing) { mutableStateOf(existing?.mood) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add screen time" else "Edit screen time") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Date (YYYY-MM-DD)", fontSize = 12.sp, color = Color.Gray)
                OutlinedTextField(value = dateText, onValueChange = { dateText = it }, singleLine = true)
                Text("How was your mood today?", fontSize = 12.sp, color = Color.Gray)
                MoodSelector(selectedMood = mood, onSelect = { mood = it })
                Text("Total screen time in minutes", fontSize = 12.sp, color = Color.Gray)
                OutlinedTextField(value = minutesText, onValueChange = { minutesText = it.filter(Char::isDigit) }, singleLine = true, placeholder = { Text("e.g. 272") })
                OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("Note (optional)") }, minLines = 2)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val parsedDate = runCatching { LocalDate.parse(dateText) }.getOrNull()
                val minutes = minutesText.toIntOrNull()
                when {
                    parsedDate == null -> error = "Enter a valid date in YYYY-MM-DD format."
                    minutes == null || minutes < 0 || minutes > 1440 -> error = "Enter minutes between 0 and 1440."
                    mood == null -> error = "Choose a mood from 1 to 5."
                    else -> onSave(parsedDate, minutes, mood!!, note)
                }
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (existing != null) TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}



private suspend fun importCsv(context: Context, uri: Uri, db: ScreenTimeDatabase): Int {
    val parsed = mutableListOf<DailyEntry>()
    context.contentResolver.openInputStream(uri)?.bufferedReader()?.useLines { lines ->
        lines.drop(1).forEach { line ->
            val columns = parseCsvLine(line)
            if (columns.size >= 2) {
                val date = runCatching { LocalDate.parse(columns[0].trim()) }.getOrNull()
                val minutes = columns[1].trim().toIntOrNull()
                if (date != null && minutes != null && minutes in 0..1440) {
                    val mood = columns.getOrNull(4)?.trim()?.toIntOrNull()?.takeIf { it in 1..5 }
                    parsed.add(DailyEntry(date.toString(), minutes, columns.getOrNull(3) ?: "", mood))
                }
            }
        }
    } ?: throw IllegalArgumentException("Could not open the selected CSV file")
    if (parsed.isEmpty()) throw IllegalArgumentException("No valid entries found. Choose a ScreenTrack CSV export.")
    db.entries().upsertAll(parsed)
    return parsed.size
}

private fun parseCsvLine(line: String): List<String> {
    val fields = mutableListOf<String>()
    val field = StringBuilder()
    var quoted = false
    var i = 0
    while (i < line.length) {
        val c = line[i]
        when {
            c == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> { field.append('"'); i++ }
            c == '"' -> quoted = !quoted
            c == ',' && !quoted -> { fields.add(field.toString()); field.setLength(0) }
            else -> field.append(c)
        }
        i++
    }
    fields.add(field.toString())
    return fields
}

private fun formatDuration(minutes: Int): String = "${minutes / 60}h ${minutes % 60}m"

private suspend fun exportCsv(context: Context, uri: Uri, entries: List<DailyEntry>) {
    context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
        writer.appendLine("date,minutes,hours,note,mood")
        entries.sortedBy { it.date }.forEach { entry ->
            val escaped = entry.note.replace("\"", "\"\"")
            writer.appendLine("\"${entry.date}\",${entry.minutes},${"%.2f".format(java.util.Locale.US, entry.minutes / 60.0)},\"$escaped\",${entry.mood ?: ""}")
        }
    }
}
