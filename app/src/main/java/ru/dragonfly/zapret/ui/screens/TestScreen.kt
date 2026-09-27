package ru.dragonfly.zapret.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.dragonfly.zapret.testing.StrategyReport
import ru.dragonfly.zapret.testing.StrategyTester
import ru.dragonfly.zapret.ui.BestStrategyInfo
import ru.dragonfly.zapret.ui.MainViewModel

@Composable
fun TestScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val strategies by viewModel.strategies.collectAsStateWithLifecycle()
    val targets by viewModel.targets.collectAsStateWithLifecycle()
    val state by viewModel.testState.collectAsStateWithLifecycle()

    val selection by viewModel.testSelection.collectAsStateWithLifecycle()
    val best by viewModel.bestStrategy.collectAsStateWithLifecycle()
    val selectedId by viewModel.selectedStrategyId.collectAsStateWithLifecycle()
    var showTargets by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Проверка стратегий", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Приложение по очереди включает каждую стратегию и проверяет ${targets.size} адресов " +
                        "(TLS-рукопожатие + время отклика), затем показывает лучшую.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        best?.let { info ->
            if (!state.running) {
                item {
                    BestStrategyCard(
                        info = info,
                        applied = info.id == selectedId,
                        onApply = { viewModel.applyBest() }
                    )
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        if (state.running) viewModel.cancelTest(context)
                        else viewModel.runTest(context, selection)
                    },
                    modifier = Modifier.weight(1f)
                ) { Text(if (state.running) "Остановить" else "Проверить (${selection.size})") }

                OutlinedButton(onClick = { showTargets = true }) { Text("Адреса") }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { viewModel.setTestSelection(strategies.map { it.id }.toSet()) },
                    enabled = !state.running
                ) { Text("Все") }
                OutlinedButton(
                    onClick = { viewModel.setTestSelection(emptySet()) },
                    enabled = !state.running
                ) { Text("Снять") }
                OutlinedButton(
                    onClick = {
                        viewModel.setTestSelection(
                            strategies.filter { !it.notRecommended }.map { it.id }.toSet()
                        )
                    },
                    enabled = !state.running
                ) { Text("Рекомендуемые") }
            }
        }

        state.progress?.let { progress ->
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "${progress.strategyIndex + 1} / ${progress.strategyCount} · ${progress.strategyName}",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(progress.stage, style = MaterialTheme.typography.bodySmall)
                        LinearProgressIndicator(
                            progress = {
                                if (progress.strategyCount == 0) 0f
                                else progress.strategyIndex.toFloat() / progress.strategyCount
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        if (state.reports.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Результаты", style = MaterialTheme.typography.titleMedium)
                }
            }
            items(state.reports, key = { it.strategyId + it.strategyName }) { report ->
                ReportCard(report, best = report.strategyId == state.bestStrategyId)
            }
            state.reportFile?.let {
                item {
                    Text(
                        "Отчёт сохранён: test_results/$it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item { Text("Что проверять", style = MaterialTheme.typography.titleMedium) }

        items(strategies, key = { it.id }) { strategy ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = selection.contains(strategy.id),
                    enabled = !state.running,
                    onCheckedChange = { checked -> viewModel.toggleTestSelection(strategy.id, checked) }
                )
                Column {
                    Text(strategy.name, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        strategy.summary,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    if (showTargets) {
        TargetsDialog(
            initial = viewModel.readTargetsRaw(),
            onDismiss = { showTargets = false },
            onSave = {
                viewModel.saveTargets(it)
                showTargets = false
            }
        )
    }
}

@Composable
private fun BestStrategyCard(
    info: BestStrategyInfo,
    applied: Boolean,
    onApply: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Лучшая стратегия", style = MaterialTheme.typography.labelLarge)
            Text(info.name, style = MaterialTheme.typography.headlineSmall)
            Text(
                "Открылось сайтов: ${info.scoreText} · среднее время ${info.latencyText}" +
                    if (info.dateText.isEmpty()) "" else " · проверено ${info.dateText}",
                style = MaterialTheme.typography.bodySmall
            )
            if (applied) {
                Text(
                    "Выбрана — можно сразу нажимать «Запустить» на главной",
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                Button(onClick = onApply) { Text("Сделать активной") }
            }
        }
    }
}

@Composable
private fun ReportCard(report: StrategyReport, best: Boolean) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (best) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    report.strategyName + if (best) "  ★" else "",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                Text("${report.okCount}/${report.total}", style = MaterialTheme.typography.titleSmall)
            }
            if (report.error != null) {
                Text(report.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            } else {
                val latency = if (report.avgLatencyMs == Long.MAX_VALUE) "—" else "${report.avgLatencyMs} мс"
                Text(
                    "Среднее время: $latency" +
                        if (report.strategyId == StrategyTester.BASELINE) " · без обхода" else "",
                    style = MaterialTheme.typography.bodySmall
                )
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Свернуть" else "Подробно")
                }
                if (expanded) {
                    report.results.forEach { site ->
                        Text(
                            "${if (site.ok) "OK   " else "FAIL "} ${site.target.name} · " +
                                (site.latencyMs?.let { "$it мс" } ?: site.detail),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TargetsDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
        title = { Text("Адреса для проверки") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    "Формат: Имя = \"https://host\" или Имя = \"PING:1.1.1.1\"",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.size(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 260.dp),
                    textStyle = MaterialTheme.typography.bodySmall
                )
            }
        }
    )
}
