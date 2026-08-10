package com.bit.models.table_schema

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.bit.models.enums.PathType
import com.bit.models.enums.ProviderType
import java.util.UUID

@Entity(tableName = "models")
data class Model(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),

    @ColumnInfo(name = "model_name")
    val modelName: String,

    @ColumnInfo(name = "model_path")
    val modelPath: String,

    @ColumnInfo(name = "path_type")
    val pathType: PathType,

    @ColumnInfo(name = "provider_type")
    val providerType: ProviderType,

    @ColumnInfo(name = "file_size")
    val fileSize: Long?,

    @ColumnInfo(name = "is_active")
    val isActive: Boolean = true
)
