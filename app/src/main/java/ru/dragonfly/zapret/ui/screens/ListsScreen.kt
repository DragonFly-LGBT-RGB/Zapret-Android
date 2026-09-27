package ru.dragonfly.zapret.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.dragonfly.zapret.ui.MainViewModel

@Composable
fun ListsScreen(viewModel: MainViewModel, onOpenFile: (String) -> Unit) {
    val lists by viewModel.lists.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Списки", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Те же файлы, что и в папке lists на ПК. В файлы с пометкой «ваш» можно вставлять " +
                        "свои домены и адреса — они не перезаписываются при обновлении.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        items(lists, key = { it.file.absolutePath }) { info ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenFile(info.file.name) },
                colors = CardDefaults.cardColors(
                    containerColor = if (info.user) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(info.title, style = MaterialTheme.typography.titleSmall)
                        Text(
                            info.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text("${info.entries}", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}
