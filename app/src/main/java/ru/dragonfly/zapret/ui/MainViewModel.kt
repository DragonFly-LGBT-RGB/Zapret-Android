package ru.dragonfly.zapret.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.dragonfly.zapret.core.AssetInstaller
import ru.dragonfly.zapret.core.EngineMode
import ru.dragonfly.zapret.core.GameFilter
import ru.dragonfly.zapret.core.Logger
import ru.dragonfly.zapret.core.Prefs
import ru.dragonfly.zapret.core.Shell
import ru.dragonfly.zapret.core.Strategy
import ru.dragonfly.zapret.core.StrategyRepository
import ru.dragonfly.zapret.core.Workspace
import ru.dragonfly.zapret.engine.ByeDpiTranslator
import ru.dragonfly.zapret.engine.EngineController
import ru.dragonfly.zapret.engine.RunState
import ru.dragonfly.zapret.testing.StrategyReport
import ru.dragonfly.zapret.testing.StrategyTester
import ru.dragonfly.zapret.testing.Target
import ru.dragonfly.zapret.testing.Targets
import ru.dragonfly.zapret.testing.TestProgress
import java.io.File

data class ListFileInfo(
    val file: File,
    val title: String,
    val description: String,
    val entries: Int,
    val user: Boolean
)

data class TestUiState(
    val running: Boolean = false,
    val progress: TestProgress? = null,
    val reports: List<StrategyReport> = emptyList(),
    val bestStrategyId: String? = null,
    val reportFile: String? = null
)

/** Победитель последнего теста — сохраняется между запусками. */
data class BestStrategyInfo(
    val id: String,
    val name: String,
    val okCount: Int,
    val total: Int,
    val avgLatencyMs: Long,
    val testedAt: Long
) {
    val scoreText: String get() = "$okCount из $total"
    val latencyText: String
        get() = if (avgLatencyMs <= 0L || avgLatencyMs == Long.MAX_VALUE) "—" else "$avgLatencyMs мс"
    val dateText: String
        get() = if (testedAt <= 0L) ""
        else java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(testedAt))
}

data class SettingsState(
    val engineMode: EngineMode = EngineMode.AUTO,
    val gameFilter: GameFilter = GameFilter.OFF,
    val queueNumber: Int = 200,
    val socksPort: Int = 10080,
    val autoStart: Boolean = false,
    val vpnDns: String = "1.1.1.1",
    val vpnIpv6: Boolean = false,
    val byeDpiHostlists: Boolean = false,
    val customByeDpiArgs: String = "",
    val testTimeoutSec: Int = 5,
    val autoApplyBest: Boolean = true,
    val rootAvailable: Boolean = false,
    val nfqwsAvailable: Boolean = false,
    val byeDpiAvailable: Boolean = false
)

sealed interface UiEvent {
    data class RequestVpnPermission(val intent: Intent) : UiEvent
    data class Message(val text: String) : UiEvent
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val workspace = Workspace(application)
    private val repository = StrategyRepository(workspace)
    private val tester = StrategyTester(application, workspace, repository)

    private val _strategies = MutableStateFlow<List<Strategy>>(emptyList())
    val strategies = _strategies.asStateFlow()

    private val _selectedStrategyId = MutableStateFlow(Prefs.strategyId)
    val selectedStrategyId = _selectedStrategyId.asStateFlow()

    private val _lists = MutableStateFlow<List<ListFileInfo>>(emptyList())
    val lists = _lists.asStateFlow()

    private val _targets = MutableStateFlow<List<Target>>(emptyList())
    val targets = _targets.asStateFlow()

    private val _testState = MutableStateFlow(TestUiState())
    val testState = _testState.asStateFlow()

    private val _settings = MutableStateFlow(SettingsState())
    val settings = _settings.asStateFlow()

    private val _bestStrategy = MutableStateFlow(readBestStrategy())
    val bestStrategy = _bestStrategy.asStateFlow()

    /** Отмеченные для проверки стратегии; живут в ViewModel, чтобы не сбрасываться при переходах. */
    private val _testSelection = MutableStateFlow<Set<String>>(emptySet())
    val testSelection = _testSelection.asStateFlow()

    val status = EngineController.status
    val logs = Logger.lines

    val events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 16)

    private var testJob: Job? = null
    private var startupSelectionDone = false

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { AssetInstaller(getApplication<Application>(), workspace).installIfNeeded() }
            val loaded = repository.load()
            _strategies.value = loaded
            _bestStrategy.value = readBestStrategy()

            val best = _bestStrategy.value?.takeIf { info -> loaded.any { it.id == info.id } }
            if (!startupSelectionDone && Prefs.autoApplyBest && best != null) {
                // при запуске автоматически ставим стратегию, победившую в тесте
                if (_selectedStrategyId.value != best.id) {
                    Logger.i("Автовыбор лучшей стратегии: ${best.name} (${best.scoreText})")
                }
                _selectedStrategyId.value = best.id
                Prefs.strategyId = best.id
            } else if (_selectedStrategyId.value == null || loaded.none { it.id == _selectedStrategyId.value }) {
                val fallback = loaded.firstOrNull { it.id == "general.bat" } ?: loaded.firstOrNull()
                _selectedStrategyId.value = fallback?.id
                Prefs.strategyId = fallback?.id
            }
            startupSelectionDone = true
            val known = loaded.map { it.id }.toSet()
            val current = _testSelection.value.filter { known.contains(it) }.toSet()
            _testSelection.value = current.ifEmpty {
                loaded.filter { !it.notRecommended }.map { it.id }.toSet()
            }

            _lists.value = loadLists()
            _targets.value = Targets.parse(workspace.targetsFile)
            collectSettings()
        }
    }

    fun reloadSettings() {
        viewModelScope.launch(Dispatchers.IO) { collectSettings() }
    }

    private fun collectSettings() {
        _settings.value = SettingsState(
            engineMode = Prefs.engineMode,
            gameFilter = Prefs.gameFilter,
            queueNumber = Prefs.queueNumber,
            socksPort = Prefs.socksPort,
            autoStart = Prefs.autoStart,
            vpnDns = Prefs.vpnDns,
            vpnIpv6 = Prefs.vpnIpv6,
            byeDpiHostlists = Prefs.byeDpiUseHostlists,
            customByeDpiArgs = Prefs.customByeDpiArgs,
            testTimeoutSec = Prefs.testTimeoutSec,
            autoApplyBest = Prefs.autoApplyBest,
            rootAvailable = Shell.isRootAvailable(),
            nfqwsAvailable = workspace.nfqwsBinary.exists(),
            byeDpiAvailable = ru.dragonfly.zapret.core.ByeDpiNative.isUsable()
        )
    }

    // ------------------------------------------------------------------ strategies

    fun selectStrategy(id: String) {
        _selectedStrategyId.value = id
        Prefs.strategyId = id
    }

    fun strategyDetails(strategy: Strategy): StrategyDetails {
        val resolved = repository.resolve(strategy)
        val translation = ByeDpiTranslator.translate(resolved, Prefs.socksPort, Prefs.byeDpiUseHostlists)
        return StrategyDetails(
            nfqwsCommand = "nfqws --qnum=${Prefs.queueNumber} " + resolved.commandLine,
            byeDpiCommand = "ciadpi " + translation.commandLine,
            notes = translation.notes,
            tcpPorts = resolved.tcpPorts,
            udpPorts = resolved.udpPorts,
            missingFiles = repository.missingFiles(resolved)
        )
    }

    fun saveCustomStrategy(name: String, commandLine: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val created = repository.saveCustom(name, commandLine)
            _strategies.value = repository.load()
            created?.let { selectStrategy(it.id) }
            events.tryEmit(UiEvent.Message("Стратегия сохранена"))
        }
    }

    fun importStrategyFile(name: String, content: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val safe = name.substringAfterLast('/').ifEmpty { "imported.bat" }
            val file = File(workspace.strategies, if (safe.endsWith(".bat", true)) safe else "$safe.bat")
            file.writeText(content)
            _strategies.value = repository.load()
            events.tryEmit(UiEvent.Message("Импортировано: ${file.name}"))
        }
    }

    fun deleteStrategy(strategy: Strategy) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.delete(strategy)
            _strategies.value = repository.load()
            if (_selectedStrategyId.value == strategy.id) {
                _selectedStrategyId.value = _strategies.value.firstOrNull()?.id
                Prefs.strategyId = _selectedStrategyId.value
            }
        }
    }

    // ------------------------------------------------------------------ engine

    fun toggleEngine(context: Context) {
        val running = status.value.state == RunState.RUNNING || status.value.state == RunState.STARTING
        if (running) {
            EngineController.stop(context)
            return
        }
        val id = _selectedStrategyId.value
        if (id == null) {
            events.tryEmit(UiEvent.Message("Сначала выберите стратегию"))
            return
        }
        when (val result = EngineController.start(context, id)) {
            is EngineController.StartResult.NeedsVpnPermission ->
                events.tryEmit(UiEvent.RequestVpnPermission(result.intent))

            is EngineController.StartResult.Failed -> events.tryEmit(UiEvent.Message(result.reason))
            else -> Unit
        }
    }

    fun startAfterVpnPermission(context: Context) {
        val id = _selectedStrategyId.value ?: return
        EngineController.start(context, id)
    }

    fun effectiveMode(context: Context): EngineMode = EngineController.effectiveMode(context)

    // ------------------------------------------------------------------ tester

    fun runTest(context: Context, strategyIds: Set<String>) {
        if (_testState.value.running) return
        val selected = _strategies.value.filter { strategyIds.contains(it.id) }
        if (selected.isEmpty()) {
            events.tryEmit(UiEvent.Message("Выберите хотя бы одну стратегию"))
            return
        }
        val targetList = _targets.value
        if (targetList.isEmpty()) {
            events.tryEmit(UiEvent.Message("Список адресов пуст"))
            return
        }

        EngineController.stop(context)
        _testState.value = TestUiState(running = true)

        testJob = viewModelScope.launch(Dispatchers.IO) {
            val mode = EngineController.effectiveMode(context)
            Logger.i("Тест ${selected.size} стратегий, режим $mode")
            val reports = tester.run(
                strategies = selected,
                targets = targetList,
                mode = mode,
                onProgress = { progress ->
                    _testState.value = _testState.value.copy(
                        progress = progress,
                        reports = progress.finished
                    )
                }
            )
            val best = tester.best(reports)
            val file = runCatching { tester.saveReport(reports) }.getOrNull()

            if (best != null) {
                val info = BestStrategyInfo(
                    id = best.strategyId,
                    name = best.strategyName,
                    okCount = best.okCount,
                    total = best.total,
                    avgLatencyMs = if (best.avgLatencyMs == Long.MAX_VALUE) 0L else best.avgLatencyMs,
                    testedAt = System.currentTimeMillis()
                )
                storeBestStrategy(info)
                // сразу делаем победителя активной стратегией
                _selectedStrategyId.value = info.id
                Prefs.strategyId = info.id
                Logger.i("Лучшая стратегия: ${info.name} (${info.scoreText}, ${info.latencyText})")
                events.tryEmit(
                    UiEvent.Message("Лучшая стратегия: ${info.name} — выбрана автоматически")
                )
            } else {
                events.tryEmit(
                    UiEvent.Message("Ни одна стратегия не пробила блокировку — проверьте адреса и интернет")
                )
            }

            _testState.value = TestUiState(
                running = false,
                progress = null,
                reports = reports.sortedWith(
                    compareByDescending<StrategyReport> { it.okCount }.thenBy { it.avgLatencyMs }
                ),
                bestStrategyId = best?.strategyId,
                reportFile = file?.name
            )
        }
    }

    fun setTestSelection(ids: Set<String>) {
        _testSelection.value = ids
    }

    fun toggleTestSelection(id: String, checked: Boolean) {
        _testSelection.value = if (checked) _testSelection.value + id else _testSelection.value - id
    }

    fun cancelTest(context: Context) {
        testJob?.cancel()
        testJob = null
        EngineController.stop(context)
        _testState.value = _testState.value.copy(running = false)
    }

    fun applyBest() {
        val info = _bestStrategy.value
        val id = info?.id ?: _testState.value.bestStrategyId ?: return
        selectStrategy(id)
        events.tryEmit(UiEvent.Message("Стратегия применена: ${info?.name ?: id}"))
    }

    private fun readBestStrategy(): BestStrategyInfo? {
        val id = Prefs.lastBestStrategy ?: return null
        return BestStrategyInfo(
            id = id,
            name = Prefs.lastBestName ?: id,
            okCount = Prefs.lastBestOk,
            total = Prefs.lastBestTotal,
            avgLatencyMs = Prefs.lastBestLatency,
            testedAt = Prefs.lastBestTime
        )
    }

    private fun storeBestStrategy(info: BestStrategyInfo) {
        Prefs.lastBestStrategy = info.id
        Prefs.lastBestName = info.name
        Prefs.lastBestOk = info.okCount
        Prefs.lastBestTotal = info.total
        Prefs.lastBestLatency = info.avgLatencyMs
        Prefs.lastBestTime = info.testedAt
        _bestStrategy.value = info
    }

    // ------------------------------------------------------------------ lists

    private fun loadLists(): List<ListFileInfo> = workspace.listFiles().map { file ->
        val entries = runCatching {
            file.readLines().count { it.isNotBlank() && !it.trimStart().startsWith("#") }
        }.getOrDefault(0)
        ListFileInfo(
            file = file,
            title = file.name,
            description = describeList(file.name),
            entries = entries,
            user = workspace.isUserList(file.name)
        )
    }

    fun readList(file: File): String = runCatching { file.readText() }.getOrDefault("")

    fun saveList(file: File, content: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                file.writeText(if (content.endsWith("\n")) content else content + "\n")
            }.onFailure { events.tryEmit(UiEvent.Message("Не удалось сохранить: ${it.message}")) }
            _lists.value = loadLists()
            events.tryEmit(UiEvent.Message("Сохранено: ${file.name}"))
        }
    }

    fun appendToList(file: File, entries: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val existing = runCatching { file.readLines() }.getOrDefault(emptyList())
                .map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
            val added = entries.split(Regex("[\\s,;]+")).map { it.trim() }.filter { it.isNotEmpty() }
            var count = 0
            added.forEach { candidate ->
                if (!existing.contains(candidate)) {
                    existing.add(candidate)
                    count++
                }
            }
            file.writeText(existing.joinToString("\n") + "\n")
            _lists.value = loadLists()
            events.tryEmit(UiEvent.Message("Добавлено записей: $count"))
        }
    }

    fun saveTargets(text: String) {
        viewModelScope.launch(Dispatchers.IO) {
            workspace.targetsFile.writeText(text)
            _targets.value = Targets.parse(workspace.targetsFile)
            events.tryEmit(UiEvent.Message("Список адресов обновлён"))
        }
    }

    fun readTargetsRaw(): String = runCatching { workspace.targetsFile.readText() }.getOrDefault("")

    fun resetAssets() {
        viewModelScope.launch(Dispatchers.IO) {
            AssetInstaller(getApplication<Application>(), workspace).installIfNeeded(force = true)
            _strategies.value = repository.load()
            _lists.value = loadLists()
            events.tryEmit(UiEvent.Message("Встроенные файлы восстановлены"))
        }
    }

    // ------------------------------------------------------------------ settings

    fun updateSettings(update: (SettingsState) -> SettingsState) {
        val next = update(_settings.value)
        Prefs.engineMode = next.engineMode
        Prefs.gameFilter = next.gameFilter
        Prefs.queueNumber = next.queueNumber
        Prefs.socksPort = next.socksPort
        Prefs.autoStart = next.autoStart
        Prefs.vpnDns = next.vpnDns
        Prefs.vpnIpv6 = next.vpnIpv6
        Prefs.byeDpiUseHostlists = next.byeDpiHostlists
        Prefs.customByeDpiArgs = next.customByeDpiArgs
        Prefs.testTimeoutSec = next.testTimeoutSec
        Prefs.autoApplyBest = next.autoApplyBest
        _settings.value = next
    }

    fun checkRoot() {
        viewModelScope.launch(Dispatchers.IO) {
            val available = Shell.isRootAvailable(refresh = true)
            _settings.value = _settings.value.copy(rootAvailable = available)
            events.tryEmit(
                UiEvent.Message(if (available) "Root доступен" else "Root недоступен")
            )
        }
    }

    suspend fun logSnapshot(): String = withContext(Dispatchers.IO) {
        buildString {
            appendLine(Logger.dump())
            val engineLog = runCatching { workspace.nfqwsLog.readText() }.getOrDefault("")
            if (engineLog.isNotBlank()) {
                appendLine()
                appendLine("--- nfqws ---")
                appendLine(engineLog)
            }
        }
    }

    private fun describeList(name: String): String = when (name) {
        "list-general.txt" -> "Основной список доменов (встроенный)"
        "list-general-user.txt" -> "Ваши домены — сюда добавляйте свои сайты"
        "list-exclude.txt" -> "Исключения (встроенные)"
        "list-exclude-user.txt" -> "Ваши исключения"
        "list-google.txt" -> "Домены Google/YouTube"
        "ipset-all.txt" -> "IP-подсети для обхода"
        "ipset-exclude.txt" -> "Исключённые IP (встроенные)"
        "ipset-exclude-user.txt" -> "Ваши исключённые IP"
        else -> "Список"
    }
}

data class StrategyDetails(
    val nfqwsCommand: String,
    val byeDpiCommand: String,
    val notes: List<String>,
    val tcpPorts: String,
    val udpPorts: String,
    val missingFiles: List<String>
)
