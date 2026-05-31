package com.verifyhub.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "code_records",
    indices = [Index(value = ["timestamp"], orders = [Index.Order.DESC])],
)
data class CodeRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** The captured code or verification URL. */
    @ColumnInfo(name = "value") val value: String,
    /** Either CODE or LINK; stored as the enum name. */
    @ColumnInfo(name = "kind") val kind: String,
    /** Originating package family. */
    @ColumnInfo(name = "source") val source: String,
    /** Sender phone number or email "From" header. */
    @ColumnInfo(name = "sender") val sender: String?,
    /** Truncated raw body for context in history view. */
    @ColumnInfo(name = "preview") val preview: String,
    @ColumnInfo(name = "timestamp") val timestamp: Long,
    /** True once we've performed any side-effects (clipboard write, fill...) */
    @ColumnInfo(name = "handled") val handled: Boolean = false,
)
