package ru.dragonfly.zapret.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.dragonfly.zapret.core.Strategy
import ru.dragonfly.zapret.ui.MainViewModel
import ru.dragonfly.zapret.ui.StrategyDetails

@Composable
fun StrategiesScreen(viewModel: MainViewModel) {
    val strategies by viewModel.strategies.collectAsStateWithLifecycle()
    val selectedId by viewModel.selectedStrategyId.collectAsStateWithLifecycle()

    var details by remember { mutableStateOf<Pair<Strategy, StrategyDetails>?>(null) }
    var showCreate by remember { mutableStateOf(false) }

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showCreate = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Своя стратегия") }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Text(
                    "Каждый .bat-файл с ПК — это отдельная стратегия. Выберите одну и запустите на главном экране.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            items(strategies, key = { it.id }) { strategy ->
                StrategyCard(
                    strategy = strategy,
                    selected = strategy.id == selectedId,
                    onSelect = { viewModel.selectStrategy(strategy.id) },
                    onDetails = { details = strategy to viewModel.strategyDetails(strategy) },
                    onDelete = if (strategy.builtIn) null else {
                        { viewModel.deleteStrategy(strategy) }
                    }
                )
            }

            if (strategies.isEmpty()) {
                item { Text("Стратегии не найдены. Откройте Настройки → Восстановить встроенные файлы.") }
            }
        }
    }

    details?.let { (strategy, info) ->
        DetailsDialog(strategy, info) { details = null }
    }

    if (showCreate) {
        CreateStrategyDialog(
            onDismiss = { showCreate = false },
            onSave = { name, command ->
                viewModel.saveCustomStrategy(name, command)
                showCreate = false
            }
        )
    }
}

@Composable
private fun StrategyCard(
    strategy: Strategy,
    selected: Boolean,
    onSelect: () -> Unit,
    onDetails: () -> Unit,
    onDelete: (() -> Unit)?
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = selected, onClick = onSelect)
            Column(Modifier.weight(1f)) {
                Text(strategy.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    strategy.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "профилей: ${strategy.profileCount}" +
                        if (strategy.notRecommended) " · не рекомендуется" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (strategy.notRecommended) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onDetails) {
                Icon(Icons.Filled.Info, contentDescription = "Подробнее")
            }
            if (onDelete != null) {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "Удалить")
                }
            }
        }
    }
}

@Composable
private fun DetailsDialog(strategy: Strategy, details: StrategyDetails, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
        title = { Text(strategy.name) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (strategy.comments.isNotEmpty()) {
                    Text(strategy.comments.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                }
                Text("Порты TCP: ${details.tcpPorts}", style = MaterialTheme.typography.bodySmall)
                Text("Порты UDP: ${details.udpPorts}", style = MaterialTheme.typography.bodySmall)

                Text("Команда в root-режиме", style = MaterialTheme.typography.titleSmall)
                Text(
                    details.nfqwsCommand,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )

                Text("Команда в режиме без root", style = MaterialTheme.typography.titleSmall)
                Text(
                    details.byeDpiCommand,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )

                if (details.notes.isNotEmpty()) {
                    Text("Что изменится без root", style = MaterialTheme.typography.titleSmall)
                    details.notes.forEach {
                        Text("• $it", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (details.missingFiles.isNotEmpty()) {
                    Text("Отсутствуют файлы", style = MaterialTheme.typography.titleSmall)
                    details.missingFiles.forEach {
                        Text("• $it", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    )
}

@Composable
private fun CreateStrategyDialog(onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var command by remember {
        mutableStateOf(
            "--filter-tcp=80,443 --dpi-desync=multisplit --dpi-desync-split-pos=1"
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { onSave(name.ifBlank { "моя стратегия" }, command) },
                enabled = command.isNotBlank()
            ) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
        title = { Text("Своя стратегия") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Вставьте аргументы winws/nfqws (всё, что идёт после winws.exe). " +
                        "Поддерживаются %BIN% и %LISTS%.",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Название") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it },
                    label = { Text("Аргументы") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 140.dp)
                )
                Spacer(Modifier.size(2.dp))
            }
        }
    )
}
