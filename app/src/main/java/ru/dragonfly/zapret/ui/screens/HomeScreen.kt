package ru.dragonfly.zapret.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.dragonfly.zapret.core.EngineMode
import ru.dragonfly.zapret.engine.RunState
import ru.dragonfly.zapret.ui.MainViewModel

@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onOpenStrategies: () -> Unit,
    onOpenTest: () -> Unit,
    onOpenLog: () -> Unit
) {
    val context = LocalContext.current
    val status by viewModel.status.collectAsStateWithLifecycle()
    val strategies by viewModel.strategies.collectAsStateWithLifecycle()
    val selectedId by viewModel.selectedStrategyId.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val best by viewModel.bestStrategy.collectAsStateWithLifecycle()
    val testState by viewModel.testState.collectAsStateWithLifecycle()

    val mode by produceState(initialValue = EngineMode.VPN, settings) {
        value = withContext(Dispatchers.IO) { viewModel.effectiveMode(context) }
    }

    val selected = strategies.firstOrNull { it.id == selectedId }
    val running = status.state == RunState.RUNNING
    val starting = status.state == RunState.STARTING

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Zapret", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Обход DPI на Android: те же стратегии, что и в .bat-файлах на ПК",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = when (status.state) {
                    RunState.RUNNING -> MaterialTheme.colorScheme.primaryContainer
                    RunState.ERROR -> MaterialTheme.colorScheme.errorContainer
                    else -> MaterialTheme.colorScheme.surfaceVariant
                }
            )
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when (status.state) {
                            RunState.RUNNING -> "Работает"
                            RunState.STARTING -> "Запуск…"
                            RunState.ERROR -> "Ошибка"
                            RunState.STOPPED -> "Остановлено"
                        },
                        style = MaterialTheme.typography.titleLarge
                    )
                    Spacer(Modifier.size(12.dp))
                    if (starting) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                }
                Text(
                    "Движок: " + when (mode) {
                        EngineMode.ROOT -> "root · nfqws + NFQUEUE (полная совместимость)"
                        else -> "без root · ByeDPI + VPN"
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "Стратегия: " + (selected?.name ?: "не выбрана"),
                    style = MaterialTheme.typography.bodyMedium
                )
                status.message?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }

        Button(
            onClick = { viewModel.toggleEngine(context) },
            modifier = Modifier
                .fillMaxWidth()
                .height(62.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (running) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
        ) {
            Icon(if (running) Icons.Filled.Stop else Icons.Filled.PlayArrow, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text(
                if (running || starting) "Остановить" else "Запустить",
                style = MaterialTheme.typography.titleMedium
            )
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (best != null) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Лучшая стратегия по тесту", style = MaterialTheme.typography.titleMedium)
                val info = best
                when {
                    testState.running -> Text(
                        "Идёт проверка стратегий…",
                        style = MaterialTheme.typography.bodyMedium
                    )

                    info == null -> {
                        Text(
                            "Тест ещё не запускался. Нажмите «Тест» — приложение переберёт все " +
                                "стратегии, выберет лучшую и запомнит её.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(onClick = onOpenTest) {
                            Icon(Icons.Filled.Speed, contentDescription = null)
                            Spacer(Modifier.size(6.dp))
                            Text("Подобрать лучшую")
                        }
                    }

                    else -> {
                        Text(info.name, style = MaterialTheme.typography.titleLarge)
                        Text(
                            "Открылось сайтов: ${info.scoreText} · ${info.latencyText}" +
                                if (info.dateText.isEmpty()) "" else " · ${info.dateText}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (info.id == selectedId) {
                            Text(
                                "Выбрана автоматически — просто нажмите «Запустить»",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        } else {
                            Button(onClick = { viewModel.applyBest() }) { Text("Применить лучшую") }
                        }
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = onOpenStrategies, modifier = Modifier.weight(1f)) {
                Text("Стратегии")
            }
            OutlinedButton(onClick = onOpenTest, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Speed, contentDescription = null)
                Spacer(Modifier.size(6.dp))
                Text("Тест")
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Состояние системы", style = MaterialTheme.typography.titleMedium)
                StatusRow("Root-доступ", settings.rootAvailable)
                StatusRow("Бинарник nfqws в APK", settings.nfqwsAvailable)
                StatusRow("Движок ByeDPI", settings.byeDpiAvailable)
                if (!settings.rootAvailable) {
                    Text(
                        "Без root используется режим VPN: стратегия переводится в набор параметров ByeDPI. " +
                            "Часть техник (syndata, seqovl, фрагментация) доступна только с root.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Журнал", style = MaterialTheme.typography.titleMedium)
                    AssistChip(
                        onClick = onOpenLog,
                        label = { Text("Открыть") },
                        leadingIcon = { Icon(Icons.Filled.Article, contentDescription = null) }
                    )
                }
                logs.takeLast(6).forEach { line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (logs.isEmpty()) {
                    Text("Пока пусто", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun StatusRow(title: String, ok: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium)
        Text(
            if (ok) "есть" else "нет",
            style = MaterialTheme.typography.bodyMedium,
            color = if (ok) MaterialTheme.colorScheme.primary else Color(0xFFFF8A65)
        )
    }
}
