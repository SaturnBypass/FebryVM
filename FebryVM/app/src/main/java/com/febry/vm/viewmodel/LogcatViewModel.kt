package com.febry.vm.viewmodel

import androidx.lifecycle.ViewModel
import com.febry.vm.AppConfig
import com.febry.vm.AppConfig.ANG_PACKAGE
import com.febry.vm.util.LogUtil
import java.io.IOException

class LogcatViewModel : ViewModel() {
    private val logsetsAll: MutableList<String> = mutableListOf()
    private var filteredLogs: List<String> = emptyList()
    private var currentFilter: String = ""
    private var currentLevel: String = ""

    fun getAll(): List<String> = filteredLogs

    fun loadLogcat() {
        try {
            val lst = LinkedHashSet<String>()
            lst.add("logcat")
            lst.add("-d")
            lst.add("-v")
            lst.add("time")
            lst.add("-s")
            lst.add("GoLog,${ANG_PACKAGE},AndroidRuntime,System.err")
            val process = Runtime.getRuntime().exec(lst.toTypedArray())
            val allText = process.inputStream.bufferedReader().use { it.readLines() }.reversed()

            logsetsAll.clear()
            logsetsAll.addAll(allText)
            applyFilter()
        } catch (e: IOException) {
            LogUtil.e(AppConfig.TAG, "Failed to get logcat", e)
        }
    }

    fun clearLogcat() {
        try {
            val lst = LinkedHashSet<String>()
            lst.add("logcat")
            lst.add("-c")
            val process = Runtime.getRuntime().exec(lst.toTypedArray())
            process.waitFor()

            logsetsAll.clear()
            filteredLogs = emptyList()
        } catch (e: IOException) {
            LogUtil.e(AppConfig.TAG, "Failed to clear logcat", e)
        }
    }

    fun filter(content: String?) {
        currentFilter = content?.trim() ?: ""
        applyFilter()
    }

    fun filterLevel(level: String) {
        currentLevel = level
        applyFilter()
    }

    private fun applyFilter() {
        var logs = logsetsAll.toList()
        if (currentLevel.isNotEmpty()) {
            logs = logs.filter { 
                it.contains(" $currentLevel/", ignoreCase = true) || 
                it.contains("[$currentLevel]", ignoreCase = true) 
            }
        }
        filteredLogs = if (currentFilter.isEmpty()) {
            logs
        } else {
            logs.filter { it.contains(currentFilter, ignoreCase = true) }
        }
    }
}
