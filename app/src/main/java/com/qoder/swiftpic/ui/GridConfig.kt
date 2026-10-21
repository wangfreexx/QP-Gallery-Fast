package com.qoder.swiftpic.ui

import android.content.Context

fun galleryColumns(context: Context): Int {
    val widthDp = context.resources.configuration.screenWidthDp
    return (widthDp / 110).coerceIn(4, 6)
}

fun albumColumns(context: Context): Int {
    val widthDp = context.resources.configuration.screenWidthDp
    return if (widthDp >= 600) 3 else 2
}
