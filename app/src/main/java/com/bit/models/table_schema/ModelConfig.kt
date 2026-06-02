package com.bit.models.table_schema

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "model_config",
    foreignKeys = [
        ForeignKey(
            entity = Model::class,
            parentColumns = ["id"],
            childColumns = ["model_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["model_id"], unique = true)]
)
data class ModelConfig(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),

    @ColumnInfo(name = "model_id")
    val modelId: String,

    @ColumnInfo(name = "model_loading_params")
    val modelLoadingParams: String?,

    @ColumnInfo(name = "model_inference_params")
    val modelInferenceParams: String?
)