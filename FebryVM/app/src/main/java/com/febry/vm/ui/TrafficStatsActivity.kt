package com.febry.vm.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.febry.vm.AppConfig
import com.febry.vm.R
import com.febry.vm.extension.toSpeedString
import com.febry.vm.handler.MmkvManager

class TrafficStatsActivity : BaseActivity() {

    private data class ServerStat(val name: String, val upload: Long, val download: Long)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentViewWithToolbar(R.layout.activity_traffic_stats, showHomeAsUp = true, title = getString(R.string.title_traffic_stats))

        val stats = loadStats()
        val totalUp = stats.sumOf { it.upload }
        val totalDown = stats.sumOf { it.download }

        findViewById<TextView>(R.id.tv_total_upload).text =
            "${getString(R.string.traffic_stats_upload)}: ${totalUp.toSpeedString()}"
        findViewById<TextView>(R.id.tv_total_download).text =
            "${getString(R.string.traffic_stats_download)}: ${totalDown.toSpeedString()}"

        val recycler = findViewById<RecyclerView>(R.id.recycler_traffic)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = Adapter(stats)

        findViewById<View>(R.id.btn_reset).setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage(R.string.traffic_stats_reset_confirm)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    MmkvManager.clearTrafficStats()
                    recreate()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun loadStats(): List<ServerStat> {
        val allServers = MmkvManager.decodeAllServerList()
        return allServers.mapNotNull { guid ->
            val config = MmkvManager.decodeServerConfig(guid) ?: return@mapNotNull null
            val up = MmkvManager.decodeTrafficUpload(guid)
            val down = MmkvManager.decodeTrafficDownload(guid)
            if (up == 0L && down == 0L) null
            else ServerStat(config.remarks, up, down)
        }.sortedByDescending { it.upload + it.download }
    }

    private inner class Adapter(private val items: List<ServerStat>) :
        RecyclerView.Adapter<Adapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.tv_server_name)
            val upload: TextView = view.findViewById(R.id.tv_upload)
            val download: TextView = view.findViewById(R.id.tv_download)
            val total: TextView = view.findViewById(R.id.tv_total)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_recycler_traffic_stat, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.name.text = item.name
            holder.upload.text = "↑ ${item.upload.toSpeedString()}"
            holder.download.text = "↓ ${item.download.toSpeedString()}"
            holder.total.text = "${getString(R.string.traffic_stats_total)}: ${(item.upload + item.download).toSpeedString()}"
        }
    }
}
