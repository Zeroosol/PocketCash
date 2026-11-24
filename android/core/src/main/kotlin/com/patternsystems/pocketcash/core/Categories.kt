package com.patternsystems.pocketcash.core

/** A spending category (docs/spec.md section 6). The app draws the icon for [key]. */
data class Category(val key: String, val label: String)

object Categories {
    val all = listOf(
        Category("food", "Food & drinks"),
        Category("transit", "Transit"),
        Category("parking", "Parking"),
        Category("groceries", "Groceries"),
        Category("errand", "Errands"),
        Category("tip", "Tips"),
        Category("bills", "Bills"),
        Category("fun", "Fun"),
        Category("other", "Other"),
    )

    fun label(key: String?): String = all.firstOrNull { it.key == key }?.label ?: "Untagged"
}
