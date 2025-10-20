@file:JvmName("MathCompat")
package com.handmadeapp.ui.importer

import kotlin.math.pow as kpow

/**
 * Компактные pow() для вызовов без импортов.
 * Делаем топ-левел функции в том же пакете, что и ImportActivity, чтобы
 * существующие вызовы pow(...) скомпилировались без правок импорта.
 */
fun pow(a: Double, b: Double): Double = a.kpow(b)
fun pow(a: Float, b: Float): Float = a.toDouble().kpow(b.toDouble()).toFloat()
fun pow(a: Int, b: Int): Double = a.toDouble().kpow(b.toDouble())
fun pow(a: Long, b: Long): Double = a.toDouble().kpow(b.toDouble())
