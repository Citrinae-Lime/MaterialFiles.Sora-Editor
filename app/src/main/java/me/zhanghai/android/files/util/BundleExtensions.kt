/*
 * Copyright (c) 2020 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.util

import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.util.SparseArray
import me.zhanghai.android.files.app.appClassLoader

inline fun <reified T : Parcelable> Bundle.getParcelableSafe(key: String?): T? {
    classLoader = appClassLoader
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelable(key, T::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelable(key)
    }
}

fun Bundle.getParcelableArraySafe(key: String?): Array<Parcelable>? {
    classLoader = appClassLoader
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableArray(key, Parcelable::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableArray(key)
    }
}

inline fun <reified T : Parcelable> Bundle.getParcelableArrayListSafe(key: String?): ArrayList<T>? {
    classLoader = appClassLoader
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableArrayList(key, T::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableArrayList(key)
    }
}

inline fun <reified T : Parcelable> Bundle.getSparseParcelableArraySafe(key: String?): SparseArray<T>? {
    classLoader = appClassLoader
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getSparseParcelableArray(key, T::class.java)
    } else {
        @Suppress("DEPRECATION")
        getSparseParcelableArray(key)
    }
}
