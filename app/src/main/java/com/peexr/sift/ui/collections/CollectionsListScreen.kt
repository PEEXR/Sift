package com.peexr.sift.ui.collections

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.peexr.sift.ui.navigation.FloatingNavClearance
import com.peexr.sift.ui.theme.SiftTheme

@Composable
fun CollectionsListScreen(
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp, vertical = 28.dp)
            .padding(bottom = FloatingNavClearance)
    ) {
        Column {
            Text(
                text = "Collections",
                style = MaterialTheme.typography.displayMedium.copy(
                    fontSize = 58.sp,
                    lineHeight = 56.sp,
                    letterSpacing = (-2.5).sp
                ),
                color = SiftTheme.extended.displayInk
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Coming next",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
