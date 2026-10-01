package com.cherret.zaprett.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.Update
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cherret.zaprett.R
import com.cherret.zaprett.data.RepoItemFull
import com.cherret.zaprett.data.StorageData
import com.cherret.zaprett.data.StrategyCheckResult
import com.cherret.zaprett.data.StrategyTestingStatus
import com.cherret.zaprett.ui.viewmodel.BaseRepoViewModel

@Composable
fun ListSwitchItem(item: StorageData, isChecked: Boolean, isUsing: Boolean, onCheckedChange: (Boolean) -> Unit, onDeleteClick: () -> Unit) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    ElevatedCard(
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 10.dp, top = 25.dp, end = 10.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(text = item.name)
                Text(text = stringResource(R.string.title_author, item.author))
                Text(text = item.description)
            }
            Switch(
                checked = isChecked || isUsing,
                onCheckedChange = onCheckedChange,
                enabled = !isUsing,
                thumbContent = if (isChecked || isUsing) {
                    {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null,
                            modifier = Modifier.size(SwitchDefaults.IconSize)
                        )
                    }
                } else {
                    {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = null,
                            modifier = Modifier.size(SwitchDefaults.IconSize)
                        )
                    }
                }
            )
        }
        HorizontalDivider(thickness = Dp.Hairline)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            FilledTonalButton(
                onClick = { showDeleteDialog = true },
                modifier = Modifier.padding(horizontal = 5.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = stringResource(R.string.btn_remove_host),
                    modifier = Modifier.size(20.dp)
                )
                Text(stringResource(R.string.btn_remove_host))
            }
        }
    }
    if (showDeleteDialog) {
        AlertDialog(
            title = { Text(stringResource(R.string.title_sure)) },
            text = { Text(stringResource(R.string.description_delete_item)) },
            onDismissRequest = { showDeleteDialog = false },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false} ) {
                    Text(stringResource(R.string.btn_dismiss))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteClick()
                    showDeleteDialog = false
                }) {
                    Text(stringResource(R.string.btn_continue))
                }
            }
        )
    }
}

@Composable
fun RepoItem(
    item: RepoItemFull,
    viewModel: BaseRepoViewModel,
    isInstalling: Map<String, Boolean>,
    isUpdateInstalling: Map<String, Boolean>,
    isUpdate: Map<String, Boolean>,
    modifier: Modifier = Modifier
) {
    val manifest = item.manifest
    val isInstalled = viewModel.isItemInstalled(item)
    val installing = isInstalling[manifest.id] == true
    val updating = isUpdateInstalling[manifest.id] == true

    ElevatedCard(
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 10.dp, top = 25.dp, end = 10.dp)
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text(text = manifest.name, modifier = Modifier.weight(1f))
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp)
            ) {
                Text(
                    text = stringResource(R.string.title_author, manifest.author),
                    modifier = Modifier.weight(1f)
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp)
            ) {
                Text(
                    text = manifest.description,
                    modifier = Modifier.weight(1f)
                )
            }

            HorizontalDivider(thickness = Dp.Hairline)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                if (isUpdate[manifest.id] == true && isInstalled) {
                    FilledTonalButton(
                        onClick = { viewModel.update(item) },
                        enabled = !updating,
                        modifier = Modifier.padding(horizontal = 5.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Update,
                            contentDescription = stringResource(R.string.btn_remove_host),
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            if (updating) stringResource(R.string.btn_updating_host)
                            else stringResource(R.string.btn_update_host)
                        )
                    }
                }

                FilledTonalButton(
                    onClick = { viewModel.install(item) },
                    enabled = !installing && !isInstalled,
                    modifier = Modifier.padding(horizontal = 5.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.InstallMobile,
                        contentDescription = stringResource(R.string.btn_remove_host),
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        when {
                            installing -> stringResource(R.string.btn_installing_host)
                            isInstalled -> stringResource(R.string.btn_installed_host)
                            else -> stringResource(R.string.btn_install_host)
                        }
                    )
                }
            }
        }
    }
}


@Composable
fun StrategySelectionItem(strategy: StrategyCheckResult, isTesting: Boolean, isActive: Boolean, onApply: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ElevatedCard (
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        onClick = {
            if (strategy.status == StrategyTestingStatus.Completed && strategy.domains.isNotEmpty()) {
                expanded = !expanded
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 10.dp, end = 10.dp, top = 25.dp, bottom = 0.dp)
    ) {
        Column (
            Modifier
                .fillMaxWidth()
                .padding(16.dp)
        )
        {
            Row {
                Text(
                    text = strategy.name,
                    modifier = Modifier
                       .weight(1f)
                )
                FilledTonalButton(
                    onClick = onApply,
                    enabled = !isTesting && strategy.status == StrategyTestingStatus.Completed && !isActive
                ) {
                    Text(stringResource(if (isActive) R.string.selection_active else R.string.selection_try_strategy))
                }
            }
            Row {
                Text(
                    text = stringResource(strategy.status.resId),
                    modifier = Modifier
                        .weight(1f),
                    fontSize = 12.sp,
                )
            }
            if (strategy.problem.isNotEmpty()) {
                Text(text = strategy.problem, style = MaterialTheme.typography.bodySmall)
            }
            if (strategy.status == StrategyTestingStatus.Completed && strategy.checkedDomains > 0) {
                Text(
                    text = stringResource(
                        R.string.selection_reachability,
                        strategy.domains.size,
                        strategy.checkedDomains,
                        (strategy.progress * 100).toInt()
                    ),
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Card (
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.selection_available_domains)
                )
                Column {
                    strategy.domains.forEach { item ->
                        Card(
                            elevation = CardDefaults.cardElevation(4.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            Text(
                                text = item,
                                modifier = Modifier.padding(16.dp),
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
            }
        }
    }
}
