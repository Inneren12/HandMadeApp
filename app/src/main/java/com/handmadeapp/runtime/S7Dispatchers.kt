package com.handmadeapp.runtime

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi

/**
 * Диспетчеры для S7: превью — один вычислительный поток, экспорт — стандартный Default.
 * Так мы исключаем конкуренцию с UI/JIT и всплески планировщика.
 */
@OptIn(ExperimentalCoroutinesApi::class)
object S7Dispatchers {
    /** Превью-путь: одна "полоса" CPU, без оверсабскрайба. */
    val preview: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)
    /** Экспорт/бэкграунд: можно шире, но оставляем как есть. */
    val export: CoroutineDispatcher = Dispatchers.Default
}
