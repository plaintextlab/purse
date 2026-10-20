package app.purse.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import app.purse.data.CategoryEntity
import app.purse.data.CategoryKind
import app.purse.ui.EmojiBadge
import app.purse.ui.LocalContainer
import app.purse.ui.PurseScaffold
import kotlinx.coroutines.launch

@Composable
fun CategoriesScreen(nav: NavHostController) {
    val container = LocalContainer.current
    val all by container.repo.categories.collectAsStateWithLifecycle()
    var kindIdx by rememberSaveable { mutableStateOf(0) }
    val kind = CategoryKind.entries[kindIdx]
    var editing by remember { mutableStateOf<CategoryEntity?>(null) }
    val list = all.filter { !it.deleted && it.kind == kind }

    PurseScaffold(
        title = "Categories",
        onBack = { nav.popBackStack() },
        floating = {
            FloatingActionButton(onClick = { editing = CategoryEntity(name = "", kind = kind, sortOrder = list.size) }) {
                Icon(Icons.Filled.Add, "Add category")
            }
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            PrimaryTabRow(selectedTabIndex = kindIdx) {
                CategoryKind.entries.forEachIndexed { i, k ->
                    Tab(selected = i == kindIdx, onClick = { kindIdx = i }, text = { Text(if (k == CategoryKind.EXPENSE) "Expense" else "Income") })
                }
            }
            LazyColumn {
                items(list, key = { it.id }) { c ->
                    ListItem(
                        modifier = Modifier.clickable { editing = c },
                        leadingContent = { EmojiBadge(c.icon) },
                        headlineContent = { Text(c.name) },
                    )
                }
            }
        }
    }

    editing?.let { c ->
        var name by remember(c.id) { mutableStateOf(c.name) }
        var icon by remember(c.id) { mutableStateOf(c.icon) }
        val isNew = all.none { it.id == c.id }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(if (isNew) "New category" else "Edit category") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(icon, { icon = it.take(4) }, label = { Text("Emoji") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    container.scope.launch { container.repo.saveCategory(c.copy(name = name.trim(), icon = icon.trim())) }
                    editing = null
                }) { Text("Save") }
            },
            dismissButton = {
                if (!isNew) {
                    TextButton(onClick = {
                        container.scope.launch { container.repo.deleteCategory(c) }
                        editing = null
                    }) { Text("Delete") }
                } else {
                    TextButton(onClick = { editing = null }) { Text("Cancel") }
                }
            },
        )
    }
}
