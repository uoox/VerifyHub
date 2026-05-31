package com.verifyhub.data

/** Where a captured code/link came from. Stored as the enum name in Room. */
enum class Source(val packageName: String?) {
    SMS(null),                                            // 系统短信广播
    GOOGLE_VOICE("com.google.android.apps.googlevoice"),
    GMAIL("com.google.android.gm"),
    OUTLOOK("com.microsoft.office.outlook"),
    UNKNOWN(null),
    ;

    companion object {
        fun fromPackage(pkg: String?): Source = entries
            .firstOrNull { it.packageName == pkg } ?: UNKNOWN
    }
}
