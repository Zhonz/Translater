package com.screentranslate

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputEditText

/**
 * 术语库管理界面。
 *
 * 功能：
 * - 按语言（英/韩/日）浏览、搜索自定义术语
 * - 添加、编辑、删除术语
 * - 从 JSON 文件导入术语库（可选覆盖或合并）
 * - 导出术语库到 JSON 文件（可选包含内置术语）
 * - 清空自定义术语
 */
class TermManagementActivity : AppCompatActivity() {

    private lateinit var repository: TermRepository
    private lateinit var rvTerms: RecyclerView
    private lateinit var etSearch: TextInputEditText
    private lateinit var tvStats: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var tabLang: TabLayout

    private var currentLang = "en"
    private val adapter = TermAdapter()

    // 文件选择器
    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { importFromUri(it) } }

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let { exportToUri(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_term_management)

        repository = TermRepository(this)

        bindViews()
        setupTabs()
        setupListeners()
        refreshList()
    }

    private fun bindViews() {
        rvTerms = findViewById(R.id.rvTerms)
        etSearch = findViewById(R.id.etSearch)
        tvStats = findViewById(R.id.tvStats)
        tvEmpty = findViewById(R.id.tvEmpty)
        tabLang = findViewById(R.id.tabLang)

        rvTerms.layoutManager = LinearLayoutManager(this)
        rvTerms.adapter = adapter

        etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                refreshList()
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
    }

    private fun setupTabs() {
        val labels = mapOf("en" to "英文", "kr" to "韩文", "jp" to "日文")
        for (lang in repository.supportedLangs) {
            tabLang.addTab(tabLang.newTab().setText(labels[lang]).setTag(lang))
        }
        tabLang.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                currentLang = tab.tag as String
                etSearch.setText("")
                refreshList()
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    private fun setupListeners() {
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnAdd).setOnClickListener { showEditDialog(null, null) }
        findViewById<View>(R.id.btnImport).setOnClickListener {
            importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
        }
        findViewById<View>(R.id.btnExport).setOnClickListener {
            showExportOptions()
        }
        findViewById<View>(R.id.btnClear).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("清空自定义术语")
                .setMessage("确定要清空当前语言的所有自定义术语吗？此操作不可撤销。")
                .setPositiveButton("清空") { _, _ ->
                    repository.clearCustom(currentLang)
                    refreshList()
                    Toast.makeText(this, "已清空", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun refreshList() {
        val query = etSearch.text?.toString()?.trim()?.lowercase() ?: ""
        val allTerms = repository.getCustomTerms(currentLang).toList()
        val filtered = if (query.isEmpty()) {
            allTerms
        } else {
            allTerms.filter { (src, trans) ->
                src.lowercase().contains(query) || trans.lowercase().contains(query)
            }
        }.sortedBy { it.first }

        adapter.submitList(filtered)

        val total = repository.getCustomCount(currentLang)
        val builtInCount = repository.getBuiltInTerms(currentLang).size
        tvStats.text = "自定义 $total 条 / 内置 $builtInCount 条"
        tvEmpty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        rvTerms.visibility = if (filtered.isEmpty()) View.GONE else View.VISIBLE
    }

    // ==================== 编辑对话框 ====================

    private fun showEditDialog(originalSource: String?, originalTranslation: String?) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_term_edit, null)
        val etSource = view.findViewById<EditText>(R.id.etSource)
        val etTranslation = view.findViewById<EditText>(R.id.etTranslation)
        etSource.setText(originalSource ?: "")
        etTranslation.setText(originalTranslation ?: "")

        val isEdit = originalSource != null
        val title = if (isEdit) "编辑术语" else "添加术语"

        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(view)
            .setPositiveButton("保存") { _, _ ->
                val src = etSource.text?.toString()?.trim() ?: ""
                val trans = etTranslation.text?.toString()?.trim() ?: ""
                if (src.isEmpty() || trans.isEmpty()) {
                    Toast.makeText(this, "原文和译文不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                // 编辑时若修改了原文，先删除旧条目
                if (isEdit && originalSource != null && originalSource != src) {
                    repository.removeTerm(currentLang, originalSource)
                }
                repository.setTerm(currentLang, src, trans)
                refreshList()
                Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ==================== 导入导出 ====================

    private fun importFromUri(uri: Uri) {
        try {
            val content = contentResolver.openInputStream(uri)?.use {
                it.readBytes().toString(Charsets.UTF_8)
            } ?: run {
                Toast.makeText(this, "无法读取文件", Toast.LENGTH_SHORT).show()
                return
            }
            // 让用户选择导入模式
            AlertDialog.Builder(this)
                .setTitle("导入术语库")
                .setMessage("选择导入模式：\n• 合并：保留已有自定义术语，仅添加新条目\n• 覆盖：替换当前语言全部自定义术语")
                .setPositiveButton("覆盖") { _, _ ->
                    val count = repository.importFromJson(content, overwrite = true)
                    Toast.makeText(this, "已导入 $count 条术语", Toast.LENGTH_SHORT).show()
                    refreshList()
                }
                .setNegativeButton("合并") { _, _ ->
                    val count = repository.importFromJson(content, overwrite = false)
                    Toast.makeText(this, "已导入 $count 条术语", Toast.LENGTH_SHORT).show()
                    refreshList()
                }
                .setNeutralButton("取消", null)
                .show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "导入失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showExportOptions() {
        AlertDialog.Builder(this)
            .setTitle("导出术语库")
            .setMessage("选择导出范围：\n• 仅自定义：只导出你添加的术语\n• 完整术语库：内置 + 自定义（含边狱巴士/脑叶公司/废墟图书馆术语）")
            .setPositiveButton("完整术语库") { _, _ ->
                exportLauncher.launch("terms_full_${System.currentTimeMillis()}.json")
                pendingExportIncludeBuiltIn = true
            }
            .setNegativeButton("仅自定义") { _, _ ->
                exportLauncher.launch("terms_custom_${System.currentTimeMillis()}.json")
                pendingExportIncludeBuiltIn = false
            }
            .setNeutralButton("取消", null)
            .show()
    }

    private var pendingExportIncludeBuiltIn = false

    private fun exportToUri(uri: Uri) {
        try {
            val json = repository.exportToJson(includeBuiltIn = pendingExportIncludeBuiltIn)
            contentResolver.openOutputStream(uri)?.use {
                it.write(json.toByteArray(Charsets.UTF_8))
            }
            Toast.makeText(this, "导出成功", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "导出失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ==================== 适配器 ====================

    private inner class TermAdapter :
        RecyclerView.Adapter<TermAdapter.TermViewHolder>() {

        private var items: List<Pair<String, String>> = emptyList()

        fun submitList(data: List<Pair<String, String>>) {
            items = data
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TermViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_term, parent, false)
            return TermViewHolder(view)
        }

        override fun onBindViewHolder(holder: TermViewHolder, position: Int) {
            val (src, trans) = items[position]
            holder.tvSource.text = src
            holder.tvTranslation.text = trans
            holder.itemView.setOnClickListener {
                showEditDialog(src, trans)
            }
            holder.btnDelete.setOnClickListener {
                repository.removeTerm(currentLang, src)
                refreshList()
            }
        }

        override fun getItemCount(): Int = items.size

        inner class TermViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvSource: TextView = view.findViewById(R.id.tvSource)
            val tvTranslation: TextView = view.findViewById(R.id.tvTranslation)
            val btnDelete: ImageButton = view.findViewById(R.id.btnDelete)
        }
    }
}
