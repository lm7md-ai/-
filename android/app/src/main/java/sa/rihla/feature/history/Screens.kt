package sa.rihla.feature.history

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import sa.rihla.R
import sa.rihla.core.*
import sa.rihla.core.Period
import sa.rihla.ui.*

@Composable
fun History(data: ScreenData, onOpen: (Long) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var favorite by rememberSaveable { mutableStateOf(false) }
    var recent by rememberSaveable { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var sort by rememberSaveable { mutableStateOf("RECENT") }
    val from = if (recent) periodBounds(Period.MONTH).first else 0L
    val list =
        remember(data.outings, data.details, query, favorite, from, sort) {
            filterOutings(data.outings, data.details, query, favorite, from, sort)
        }
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            SectionTitle(
                stringResource(R.string.atlas_log),
                stringResource(R.string.atlas_log_hint),
            )
            OutlinedTextField(
                query,
                { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                placeholder = { Text(tr("search_journeys")) },
                leadingIcon = { Icon(Icons.Default.Search, null) },
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(favorite, { favorite = !favorite }, label = { Text(tr("favorites")) })
                FilterChip(recent, { recent = !recent }, label = { Text(tr("this_month")) })
                Spacer(Modifier.weight(1f))
                Box {
                    IconButton(onClick = { sortMenu = true }) {
                        Icon(Icons.Default.Sort, stringResource(R.string.atlas_sort))
                    }
                    DropdownMenu(sortMenu, { sortMenu = false }) {
                        listOf(
                                "RECENT" to "sort_recent",
                                "DISTANCE" to "sort_distance",
                                "DURATION" to "sort_duration",
                            )
                            .forEach { (value, label) ->
                                DropdownMenuItem(
                                    text = { Text(tr(label)) },
                                    trailingIcon = {
                                        if (sort == value) Icon(Icons.Default.Check, null)
                                    },
                                    onClick = {
                                        sort = value
                                        sortMenu = false
                                    },
                                )
                            }
                    }
                }
            }
            Text(
                num(list.size.toDouble(), 0) + " " + tr("journeys_found"),
                style = MaterialTheme.typography.labelMedium,
            )
        }
        if (list.isEmpty()) item { EmptyPanel("no_results", "search_hint") }
        items(list, key = { it.id }) { outing ->
            OutingCard(outing, data.now, data.details.find { it.outingId == outing.id }) {
                onOpen(outing.id)
            }
        }
    }
}
