package com.wxmsglog

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.app.NotificationManagerCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var db: MessageDb
    private lateinit var adapter: MessageAdapter
    private lateinit var tvStatus: TextView
    private lateinit var tvFooter: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var btnPermission: Button

    private val io: ExecutorService = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        db = MessageDb.get(this)

        tvStatus = findViewById(R.id.tvStatus)
        tvFooter = findViewById(R.id.tvFooter)
        tvEmpty = findViewById(R.id.tvEmpty)
        btnPermission = findViewById(R.id.btnPermission)
        val rv = findViewById<RecyclerView>(R.id.rv)
        val btnCopyAll = findViewById<Button>(R.id.btnCopyAll)
        val btnCopyToday = findViewById<Button>(R.id.btnCopyToday)
        val btnMore = findViewById<Button>(R.id.btnMore)

        adapter = MessageAdapter { m -> copySingle(m) }
        rv.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        rv.adapter = adapter

        btnPermission.setOnClickListener { openListenerSettings() }
        btnCopyAll.setOnClickListener { copyRange(todayOnly = false) }
        btnCopyToday.setOnClickListener { copyRange(todayOnly = true) }
        btnMore.setOnClickListener { anchor -> showMenu(anchor) }

        Bus.set { refresh() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        Bus.set(null)
        io.shutdown()
        super.onDestroy()
    }

    private fun hasAccess(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)

    private fun refresh() {
        if (isFinishing) return
        val allApps = Prefs.recordAll(this)
        io.execute {
            val total = db.count()
            val latest = db.queryLatest(DISPLAY_LIMIT)
            ui.post {
                if (isFinishing || isDestroyed) return@post
                adapter.submit(latest.asReversed())
                tvEmpty.visibility = if (total == 0) View.VISIBLE else View.GONE
                tvFooter.text = if (allApps) {
                    getString(R.string.footer_count_all, total, DISPLAY_LIMIT)
                } else {
                    getString(R.string.footer_count, total, DISPLAY_LIMIT)
                }
                val granted = hasAccess()
                btnPermission.visibility = if (granted) View.GONE else View.VISIBLE
                tvStatus.text = getString(if (granted) R.string.status_on else R.string.status_off)
            }
        }
    }

    private fun openListenerSettings() {
        try {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        } catch (e: Exception) {
            toast(getString(R.string.listener_missing))
        }
    }

    private fun copySingle(m: Message) {
        Exporter.copy(this, m.sender + ": " + m.body)
        toast(getString(R.string.copied_one))
    }

    private fun copyRange(todayOnly: Boolean) {
        io.execute {
            val list = if (todayOnly) db.querySince(todayStart()) else db.queryAll()
            val empty = list.isEmpty()
            val text = if (empty) "" else Exporter.compose(this, list, CLIP_LIMIT)
            val count = list.size
            ui.post {
                if (empty) {
                    toast(getString(R.string.nothing_to_copy))
                } else {
                    Exporter.copy(this, text)
                    toast(getString(R.string.copied_n, count))
                }
            }
        }
    }

    private fun exportToDownloads() {
        io.execute {
            val list = db.queryAll()
            val empty = list.isEmpty()
            val text = if (empty) "" else Exporter.compose(this, list, Int.MAX_VALUE)
            val uri = if (empty) null else Exporter.saveToDownloads(this, text)
            ui.post {
                when {
                    empty -> toast(getString(R.string.nothing_to_copy))
                    uri == null -> toast(getString(R.string.export_fail))
                    else -> toast(getString(R.string.export_ok, uri.toString()))
                }
            }
        }
    }

    private fun shareText() {
        io.execute {
            val list = db.queryAll()
            val empty = list.isEmpty()
            val text = if (empty) "" else Exporter.compose(this, list, SHARE_LIMIT)
            ui.post {
                if (empty) {
                    toast(getString(R.string.nothing_to_copy))
                } else {
                    try {
                        startActivity(Exporter.shareIntent(this, text))
                    } catch (e: Exception) {
                        toast(getString(R.string.export_fail))
                    }
                }
            }
        }
    }

    private fun showMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, MENU_EXPORT, 0, getString(R.string.menu_export))
        popup.menu.add(0, MENU_SHARE, 1, getString(R.string.menu_share))
        val state = if (Prefs.recordAll(this)) "开" else "关"
        popup.menu.add(0, MENU_TOGGLE_ALL, 2, getString(R.string.menu_toggle_all) + "（" + state + "）")
        popup.menu.add(0, MENU_CLEAR, 3, getString(R.string.menu_clear))
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_EXPORT -> exportToDownloads()
                MENU_SHARE -> shareText()
                MENU_TOGGLE_ALL -> {
                    Prefs.setRecordAll(this, !Prefs.recordAll(this))
                    refresh()
                }
                MENU_CLEAR -> confirmClear()
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
        popup.show()
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setMessage(R.string.clear_confirm)
            .setPositiveButton(R.string.confirm) { _, _ ->
                io.execute {
                    db.clear()
                    ui.post {
                        toast(getString(R.string.cleared))
                        refresh()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun todayStart(): Long =
        LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val DISPLAY_LIMIT = 500
        const val CLIP_LIMIT = 400_000
        const val SHARE_LIMIT = 200_000
        const val MENU_EXPORT = 1
        const val MENU_SHARE = 2
        const val MENU_TOGGLE_ALL = 3
        const val MENU_CLEAR = 4
    }
}
