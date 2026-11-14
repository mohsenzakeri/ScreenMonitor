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
                onSave = { selectedDate, minutes, note ->
                    scope.launch {
                        db.entries().upsert(DailyEntry(selectedDate.toString(), minutes, note))
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
    val today = LocalDate.now()
    val todayEntry = entries.firstOrNull { it.date == today.toString() }
    val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val weekEntries = entries.filter { runCatching { LocalDate.parse(it.date) }.getOrNull() in weekStart..today }
    val average = if (weekEntries.isEmpty()) 0 else weekEntries.sumOf { it.minutes } / weekEntries.size
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF202B52))) {
                Column(Modifier.fillMaxWidth().padding(20.dp)) {
                    Text("TODAY", color = Color(0xFFB9C5F5), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(todayEntry?.let { formatDuration(it.minutes) } ?: "Not entered yet", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    Text(if (todayEntry == null) "Add today's screen time to get started" else "Your daily screen-time total", color = Color(0xFFD8DDF2))
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { onAdd(today) }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7D91FF))) {
                        Text(if (todayEntry == null) "Add today's entry" else "Edit today's entry")
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
                (0..6).map { today.minusDays((6 - it).toLong()) }.forEach { day ->
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
    val today = LocalDate.now()
    val days = (6 downTo 0).map { today.minusDays(it.toLong()) }
    val values = days.map { d -> entries.firstOrNull { it.date == d.toString() }?.minutes ?: 0 }
    val maxVal = max(60, values.maxOrNull() ?: 60)
    Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(Modifier.fillMaxWidth().height(130.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                values.forEachIndexed { i, v ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                        Text(if (v == 0) "–" else "${v / 60}h", fontSize = 10.sp, color = Color.Gray)
                        Box(
                            Modifier.padding(top = 5.dp).width(20.dp)
                                .height((if (v == 0) 3f else (v.toFloat() / maxVal * 90f).coerceAtLeast(5f)).dp)
                                .background(if (i == 6) Color(0xFF536DFE) else Color(0xFFAEBBFF), RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                        )
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
            "Day" -> parsed.map { Pair(it.first.format(DateTimeFormatter.ofPattern("MMM d")), it.second.minutes) }
            "Week" -> parsed.groupBy { it.first.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) }.toSortedMap()
                .map { (d, list) -> Pair(d.format(DateTimeFormatter.ofPattern("MMM d")), list.sumOf { it.second.minutes }) }
            else -> parsed.groupBy { YearMonth.from(it.first) }.toSortedMap()
                .map { (m, list) -> Pair(m.format(DateTimeFormatter.ofPattern("MMM yy")), list.sumOf { it.second.minutes }) }
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
                        TrendChart(trend)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(trend.first().first, color = Color.Gray, fontSize = 10.sp)
                            Text(trend.size.toString() + " " + resolution.lowercase() + " periods", color = Color.Gray, fontSize = 10.sp)
                            Text(trend.last().first, color = Color.Gray, fontSize = 10.sp)
                        }
                        Text("All available history is included. Each bar represents total minutes per " + resolution.lowercase() + ".", color = Color.Gray, fontSize = 11.sp)
                    }
                }
            }
        }
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
private fun TrendChart(points: List<Pair<String, Int>>) {
    val maxValue = max(60, points.maxOfOrNull { it.second } ?: 60).toFloat()
    Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFFF7F8FC))) {
        Canvas(Modifier.fillMaxWidth().height(180.dp).padding(8.dp)) {
            val left = 8.dp.toPx()
            val right = size.width - 8.dp.toPx()
            val top = 10.dp.toPx()
            val bottom = size.height - 10.dp.toPx()
            repeat(4) { i ->
                val y = top + (bottom - top) * i / 3f
                drawLine(Color(0xFFE1E5F0), Offset(left, y), Offset(right, y), strokeWidth = 1.dp.toPx())
            }
            if (points.isNotEmpty()) {
                val step = if (points.size == 1) 0f else (right - left) / (points.size - 1)
                val coords = points.mapIndexed { index, item -> Offset(left + index * step, bottom - (item.second / maxValue) * (bottom - top)) }
                coords.zipWithNext().forEach { (a, b) -> drawLine(Color(0xFF536DFE), a, b, strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round) }
                coords.forEach { drawCircle(Color(0xFF536DFE), radius = 3.dp.toPx(), center = it) }
            }
        }
    }
}

@Composable
private fun WeeklyBarsForRange(start: LocalDate, entries: List<DailyEntry>) {
    val map = entries.associate { LocalDate.parse(it.date) to it.minutes }
    val days = (0..6).map { start.plusDays(it.toLong()) }
    val values = days.map { map[it] ?: 0 }
    val maxVal = max(60, values.maxOrNull() ?: 60)
    Row(Modifier.fillMaxWidth().height(120.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
        values.forEachIndexed { index, value ->
            Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                Text(if (value == 0) "–" else (value / 60).toString() + "h", fontSize = 9.sp, color = Color.Gray)
                Box(Modifier.padding(top = 4.dp).fillMaxWidth(0.65f).height((if (value == 0) 3f else (value.toFloat() / maxVal * 75f).coerceAtLeast(4f)).dp).background(if (value == 0) Color(0xFFEDEFF5) else Color(0xFF8294FF), RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp)))
                Spacer(Modifier.height(5.dp))
                Text(days[index].dayOfWeek.name.take(1), fontSize = 10.sp, color = Color.Gray)
            }
        }
    }
}

@Composable
private fun MonthBars(month: YearMonth, entries: List<DailyEntry>) {
    val map = entries.associate { LocalDate.parse(it.date).dayOfMonth to it.minutes }
    val maxVal = max(60, map.values.maxOrNull() ?: 60)
    Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth().height(115.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
                (1..month.lengthOfMonth()).forEach { d ->
                    val v = map[d] ?: 0
                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.BottomCenter) {
                        Box(Modifier.fillMaxWidth().padding(horizontal = 1.dp).height((if (v == 0) 2f else (v.toFloat() / maxVal * 100f).coerceAtLeast(3f)).dp).background(if (v == 0) Color(0xFFEDEFF5) else Color(0xFF8294FF), RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp)))
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
                        if (entry.note.isNotBlank()) Text(entry.note, color = Color.Gray, fontSize = 12.sp)
                    }
                    Text(formatDuration(entry.minutes), fontWeight = FontWeight.Bold, color = Color(0xFF536DFE))
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Default.Edit, contentDescription = "Edit", tint = Color.Gray)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EntryDialog(
    date: LocalDate,
    existing: DailyEntry?,
    onDismiss: () -> Unit,
    onSave: (LocalDate, Int, String) -> Unit,
    onDelete: () -> Unit
) {
    var dateText by remember(date) { mutableStateOf(date.toString()) }
    var minutesText by remember(date, existing) { mutableStateOf(existing?.minutes?.toString() ?: "") }
    var note by remember(date, existing) { mutableStateOf(existing?.note ?: "") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add screen time" else "Edit screen time") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Date (YYYY-MM-DD)", fontSize = 12.sp, color = Color.Gray)
                OutlinedTextField(value = dateText, onValueChange = { dateText = it }, singleLine = true)
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
                    else -> onSave(parsedDate, minutes, note)
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


private fun formatDuration(minutes: Int): String = "${minutes / 60}h ${minutes % 60}m"

private suspend fun exportCsv(context: Context, uri: Uri, entries: List<DailyEntry>) {
    context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
        writer.appendLine("date,minutes,hours,note")
        entries.sortedBy { it.date }.forEach { entry ->
            val escaped = entry.note.replace("\"", "\"\"")
            writer.appendLine("\"${entry.date}\",${entry.minutes},${"%.2f".format(java.util.Locale.US, entry.minutes / 60.0)},\"$escaped\"")
        }
    }
}
