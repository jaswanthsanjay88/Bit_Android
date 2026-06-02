package com.bit.ui.screen.home

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bit.models.table_schema.Model
import com.bit.ui.components.ActionButton
import com.bit.ui.components.ActionTextButton
import com.bit.ui.components.ModelListItem
import com.bit.ui.components.ModelList
import com.bit.viewmodel.ChatViewModel
import com.bit.viewmodel.LLMModelViewModel
import com.bit.ui.icons.TnIcons
import com.bit.global.Standards

// ── Models Tab ──

@Composable
internal fun ModelsTabContent(
    installedModels: List<Model>,
    currentModelID: String,
    modelViewModel: LLMModelViewModel,
    chatViewModel: ChatViewModel,
    onModelSelectedNavigate: (Model) -> Unit = {}
) {
    ModelList(
        installedModels = installedModels,
        currentModelID = currentModelID,
        onClickListener = { selectedModel ->
            if (currentModelID == selectedModel.id) {
                modelViewModel.unloadModel()
            } else {
                modelViewModel.loadModel(selectedModel)
                onModelSelectedNavigate(selectedModel)
            }
            chatViewModel.hideDynamicWindow()
        },
        onDeleteListener = { modelToDelete ->
            modelViewModel.deleteModel(modelToDelete)
        },
        maxHeight = 240.dp
    )
}


