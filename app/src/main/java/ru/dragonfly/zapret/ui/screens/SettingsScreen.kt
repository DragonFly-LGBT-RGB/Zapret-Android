package ru.dragonfly.zapret.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.dragonfly.zapret.core.EngineMode
import ru.dragonfly.zapret.core.GameFilter
import ru.dragonfly.zapret.ui.MainViewModel

@Composable
fun SettingsScreen(viewModel: MainViewModel) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    var customArgs by remember(settings.customByeDpiArgs) { mutableStateOf(settings.customByeDpiArgs) }
    var dns by remember(settings.vpnDns) { mutableStateOf(settings.vpnDns) }
    var qnum by remember(settings.queueNumber) { mutableStateOf(settings.queueNumber.toString()) }
    var port by remember(settings.socksPort) { mutableStateOf(settings.socksPort.toString()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Настройки", style = MaterialTheme.typography.headlineSmall)

        SettingsCard("Движок") {
            Column(Modifier.selectableGroup()) {
                RadioRow(
                    "Автоматически",
                    "Root, если он есть, иначе VPN",
                    settings.engineMode == EngineMode.AUTO
                ) { viewModel.updateSettings { it.copy(engineMode = EngineMode.AUTO) } }
                RadioRow(
                    "Только root (nfqws)",
                    "Полная совместимость со стратегиями .bat",
                    settings.engineMode == EngineMode.ROOT
                ) { viewModel.updateSettings { it.copy(engineMode = EngineMode.ROOT) } }
                RadioRow(
                    "Только VPN (ByeDPI)",
                    "Работает без root, стратегия переводится автоматически",
                    settings.engineMode == EngineMode.VPN
                ) { viewModel.updateSettings { it.copy(engineMode = EngineMode.VPN) } }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { viewModel.checkRoot() }) { Text("Проверить root") }
                Text(
                    if (settings.rootAvailable) "root: есть" else "root: нет",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 14.dp)
                )
            }
        }

        SettingsCard("Игровой фильтр") {
            Text(
                "Аналог пункта Game Filter в service.bat: расширяет фильтр на порты 1024-65535.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Column(Modifier.selectableGroup()) {
                GameFilter.entries.forEach { filter ->
                    RadioRow(
                        when (filter) {
                            GameFilter.OFF -> "Выключен"
                            GameFilter.TCP -> "TCP"
                            GameFilter.UDP -> "UDP"
                            GameFilter.ALL -> "TCP и UDP"
                        },
                        "",
                        settings.gameFilter == filter
                    ) { viewModel.updateSettings { it.copy(gameFilter = filter) } }
                }
            }
        }

        SettingsCard("Режим без root (ByeDPI)") {
            SwitchRow(
                "Ограничивать обход списками доменов",
                "Использовать list-general вместо применения ко всем сайтам",
                settings.byeDpiHostlists
            ) { value -> viewModel.updateSettings { it.copy(byeDpiHostlists = value) } }

            SwitchRow("IPv6 в туннеле", "Включайте, только если IPv6 реально работает", settings.vpnIpv6) { value ->
                viewModel.updateSettings { it.copy(vpnIpv6 = value) }
            }

            OutlinedTextField(
                value = dns,
                onValueChange = {
                    dns = it
                    viewModel.updateSettings { state -> state.copy(vpnDns = it) }
                },
                label = { Text("DNS в туннеле") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = customArgs,
                onValueChange = {
                    customArgs = it
                    viewModel.updateSettings { state -> state.copy(customByeDpiArgs = it) }
                },
                label = { Text("Свои аргументы ByeDPI (перекрывают перевод стратегии)") },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 90.dp)
            )
            Text(
                "Пример: --split 1+s --disorder 1 --auto=torst --tlsrec 1+s",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        SettingsCard("Дополнительно") {
            OutlinedTextField(
                value = qnum,
                onValueChange = {
                    qnum = it
                    it.toIntOrNull()?.let { value ->
                        viewModel.updateSettings { state -> state.copy(queueNumber = value) }
                    }
                },
                label = { Text("Номер очереди NFQUEUE") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = port,
                onValueChange = {
                    port = it
                    it.toIntOrNull()?.let { value ->
                        viewModel.updateSettings { state -> state.copy(socksPort = value) }
                    }
                },
                label = { Text("Порт локального SOCKS5") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            SwitchRow(
                "Лучшая стратегия при запуске",
                "Приложение само выбирает победителя последнего теста",
                settings.autoApplyBest
            ) { value ->
                viewModel.updateSettings { it.copy(autoApplyBest = value) }
            }
            SwitchRow("Автозапуск при загрузке", "Требует разрешения на автозапуск", settings.autoStart) { value ->
                viewModel.updateSettings { it.copy(autoStart = value) }
            }
            OutlinedButton(onClick = { viewModel.resetAssets() }, modifier = Modifier.fillMaxWidth()) {
                Text("Восстановить встроенные стратегии и списки")
            }
        }

        SettingsCard("О приложении") {
            Text(
                "Движок root — nfqws из проекта bol-van/zapret. Движок без root — byedpi (hufrea) " +
                    "плюс hev-socks5-tunnel. Стратегии берутся из .bat-файлов этого репозитория.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun RadioRow(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Column {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (subtitle.isNotEmpty()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (subtitle.isNotEmpty()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
