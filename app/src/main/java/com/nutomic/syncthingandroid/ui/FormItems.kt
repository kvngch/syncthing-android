package com.nutomic.syncthingandroid.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role

/**
 * A setting turned on or off by tapping the whole row.
 */
@Composable
internal fun SwitchItem(
    icon: ImageVector,
    @StringRes title: Int,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    @StringRes description: Int? = null,
) {
    ListItem(
        headlineContent = { Text(stringResource(title)) },
        supportingContent = if (description != null) {
            { Text(stringResource(description)) }
        } else {
            null
        },
        leadingContent = { Icon(icon, contentDescription = null) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
    )
}

@Composable
internal fun SectionTitle(@StringRes title: Int, icon: ImageVector, modifier: Modifier = Modifier) {
    ListItem(
        headlineContent = {
            Text(stringResource(title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        },
        leadingContent = { Icon(icon, contentDescription = null) },
        modifier = modifier,
    )
}
