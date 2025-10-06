/*
 * Copyright (c) 2020 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.util

import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Replacement for deprecated AsyncTask.THREAD_POOL_EXECUTOR.
 * Uses a cached thread pool similar to AsyncTask's behavior.
 */
val backgroundThreadPoolExecutor: Executor = Executors.newCachedThreadPool()
