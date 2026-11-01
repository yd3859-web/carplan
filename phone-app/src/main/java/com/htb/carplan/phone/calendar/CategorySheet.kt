package com.htb.carplan.phone.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.htb.carplan.phone.ui.CenterSheet
import com.htb.carplan.phone.ui.Corner
import com.htb.carplan.phone.ui.Palette
import com.htb.carplan.phone.ui.argb
import com.htb.carplan.shared.calendar.CategoryColors
import com.htb.carplan.shared.model.BuiltInCategory
import com.htb.carplan.shared.model.CustomCategory

/** "카테고리 선택" sheet from the example: color dot + name cards, [편집] adds a category. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CategorySheet(
    selectedId: String,
    custom: List<CustomCategory>,
    onPick: (String) -> Unit,
    onAddCategory: (name: String, color: Long, onAdded: (String) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    CenterSheet(onDismiss = onDismiss) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("카테고리 선택", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = { editing = !editing }) { Text(if (editing) "완료" else "편집", fontSize = 18.sp, color = Palette.Navy) }
            }
            Spacer(Modifier.height(16.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                BuiltInCategory.entries.forEach { c ->
                    CategoryCard(c.label, argb(CategoryColors.BUILT_IN.getValue(c.id)), c.id == selectedId) { onPick(c.id) }
                }
                custom.forEach { c -> CategoryCard(c.name, argb(c.color), c.id == selectedId) { onPick(c.id) } }
                if (editing) CategoryCard("+ 추가", Palette.TextSoft, selected = false) { adding = true }
            }
        }
    }
    if (adding) {
        NewCategoryDialog(
            onConfirm = { name, color -> adding = false; onAddCategory(name, color) { id -> onPick(id) } },
            onDismiss = { adding = false },
        )
    }
}

@Composable
private fun CategoryCard(name: String, color: Color, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.size(width = 132.dp, height = 104.dp).clip(Corner).background(Palette.Background)
            .border(if (selected) 2.dp else 1.dp, if (selected) Palette.TextStrong else Palette.SheetEdge, Corner)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(16.dp).clip(Corner).background(color))
        Spacer(Modifier.height(12.dp))
        Text(name, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Palette.TextStrong)
    }
}

private fun Color.compositeOverDark(): Color = Color(red * 0.75f, green * 0.75f, blue * 0.75f, 1f)

/** Name + one of the 8 palette colors (Q03). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NewCategoryDialog(onConfirm: (String, Long) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var color by remember { mutableStateOf(CategoryColors.PALETTE.first()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = Corner,
        containerColor = Palette.Background,
        title = { Text("새 카테고리") },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, singleLine = true, placeholder = { Text("예: 출퇴근, 여가") }, shape = Corner)
                Spacer(Modifier.height(16.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    CategoryColors.PALETTE.forEach { c ->
                        Box(
                            Modifier.size(32.dp).clip(CircleShape).background(argb(c))
                                .then(if (c == color) Modifier.border(3.dp, Palette.TextStrong, CircleShape) else Modifier)
                                .clickable { color = c },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(name.trim(), color) }, enabled = name.isNotBlank()) { Text("추가") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}
