package ru.dragonfly.zapret.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.dragonfly.zapret.ui.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListEditorScreen(viewModel: MainViewModel, fileName: String, onBack: () -> Unit) {
    val lists by viewModel.lists.collectAsStateWithLifecycle()
    val info = lists.firstOrNull { it.file.name == fileName }
    val clipboard = LocalClipboardManager.current

    var content by remember(fileName, info) {
        mutableStateOf(info?.let { viewModel.readList(it.file) } ?: "")
    }
    var newEntry by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(fileName, style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                }
            )
        }
    ) { padding ->
        if (info == null) {
            Text("Файл не найден", modifier = Modifier.padding(padding).padding(16.dp))
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(info.description, style = MaterialTheme.typography.bodyMedium)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = newEntry,
                    onValueChange = { newEntry = it },
                    label = { Text("Добавить домен / IP") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = {
                        viewModel.appendToList(info.file, newEntry)
                        val added = newEntry.split(Regex("[\\s,;]+")).filter { it.isNotBlank() }
                        content = (content.trimEnd() + "\n" + added.joinToString("\n")).trim() + "\n"
                        newEntry = ""
                    },
                    enabled = newEntry.isNotBlank()
                ) { Text("+") }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    clipboard.getText()?.text?.let { pasted ->
                        content = (content.trimEnd() + "\n" + pasted.trim()).trim() + "\n"
                    }
                }) { Text("Вставить из буфера") }

                OutlinedButton(onClick = {
                    clipboard.setText(AnnotatedString(content))
                }) { Text("Копировать") }
            }

            OutlinedTextField(
                value = content,
                onValueChange = { content = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 320.dp),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                label = { Text("Содержимое файла") }
            )

            Button(
                onClick = { viewModel.saveList(info.file, content) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Сохранить") }

            Text(
                "Изменения применяются при следующем запуске стратегии.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
