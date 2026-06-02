package com.bit.models.converters

import androidx.room.TypeConverter
import com.bit.models.enums.PathType
import com.bit.models.enums.ProviderType
import org.json.JSONArray

class Converters {
    @TypeConverter
    fun fromProviderType(value: ProviderType): String = value.name

    @TypeConverter
    fun toProviderType(value: String): ProviderType = ProviderType.valueOf(value)

    @TypeConverter
    fun fromPathType(value: PathType): String = value.name

    @TypeConverter
    fun toPathType(value: String): PathType = PathType.valueOf(value)

    @TypeConverter
    fun fromStringList(value: List<String>): String = JSONArray(value).toString()

    @TypeConverter
    fun toStringList(value: String): List<String> {
        if (value.isBlank() || value == "[]") return emptyList()
        val array = JSONArray(value)
        return (0 until array.length()).map { array.getString(it) }
    }
}
