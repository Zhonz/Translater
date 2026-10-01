package com.screentranslate

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText
import com.screentranslate.model.AppConfig
import com.screentranslate.model.ProviderPreset
import com.screentranslate.util.PrefsManager

/**
 * 主界面：权限申请、AI 服务商配置、悬浮窗服务启停。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefsManager: PrefsManager

    private lateinit var tvOverlayStatus: TextView
    private lateinit var tvCaptureStatus: TextView
    private lateinit var tvServiceStatus: TextView
    private lateinit var btnRequestOverlay: Button
    private lateinit var btnRequestCapture: Button
    private lateinit var btnStartService: Button
    private lateinit var btnStopService: Button
    private lateinit var btnSaveConfig: Button
    private lateinit var switchPmTerms: com.google.android.material.switchmaterial.SwitchMaterial
    private lateinit var spinnerProvider: Spinner
    private lateinit var tvProviderDesc: TextView
    private lateinit var etApiUrl: TextInputEditText
    private lateinit var etApiKey: TextInputEditText
    private lateinit var etModel: TextInputEditText
    private lateinit var etPrompt: TextInputEditText

    /** 服务商预设列表 */
    private val providers = ProviderPreset.BUILT_IN
    /** 标记是否正在程序化设置 spinner，避免触发回调覆盖用户输入 */
    private var isProgrammaticSelection = false

    // MediaProjection 授权结果
    private var captureResultCode: Int = 0
    private var captureData: Intent? = null

    // 悬浮窗权限申请
    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        updateStatus()
    }

    // MediaProjection 截屏授权
    private val capturePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            captureResultCode = result.resultCode
            captureData = result.data
            Toast.makeText(this, "截屏权限已授权", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "截屏权限被拒绝", Toast.LENGTH_SHORT).show()
        }
        updateStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefsManager = PrefsManager(this)

        bindViews()
        setupProviderSpinner()
        loadConfigIntoViews()
        setupListeners()
        setupPmTermsSwitch()
        updateStatus()
    }

    private fun bindViews() {
        tvOverlayStatus = findViewById(R.id.tvOverlayStatus)
        tvCaptureStatus = findViewById(R.id.tvCaptureStatus)
        tvServiceStatus = findViewById(R.id.tvServiceStatus)
        btnRequestOverlay = findViewById(R.id.btnRequestOverlay)
        btnRequestCapture = findViewById(R.id.btnRequestCapture)
        btnStartService = findViewById(R.id.btnStartService)
        btnStopService = findViewById(R.id.btnStopService)
        btnSaveConfig = findViewById(R.id.btnSaveConfig)
        switchPmTerms = findViewById(R.id.switchPmTerms)
        spinnerProvider = findViewById(R.id.spinnerProvider)
        tvProviderDesc = findViewById(R.id.tvProviderDesc)
        etApiUrl = findViewById(R.id.etApiUrl)
        etApiKey = findViewById(R.id.etApiKey)
        etModel = findViewById(R.id.etModel)
        etPrompt = findViewById(R.id.etPrompt)
    }

    /** 初始化服务商下拉选择器 */
    private fun setupProviderSpinner() {
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            providers.map { it.name }
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerProvider.adapter = adapter

        spinnerProvider.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                if (isProgrammaticSelection) return
                val preset = providers[position]
                // 自动填入 API 地址和模型（自定义选项不覆盖）
                if (preset.apiUrl.isNotBlank()) {
                    etApiUrl.setText(preset.apiUrl)
                }
                if (preset.model.isNotBlank()) {
                    etModel.setText(preset.model)
                }
                tvProviderDesc.text = preset.description
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
    }

    private fun loadConfigIntoViews() {
        val config = prefsManager.loadConfig()
        etApiUrl.setText(config.apiUrl)
        etApiKey.setText(config.apiKey)
        etModel.setText(config.model)
        etPrompt.setText(config.prompt)

        // 根据已保存的 apiUrl 反查并选中对应的服务商
        val preset = ProviderPreset.findByApiUrl(config.apiUrl)
        val index = providers.indexOfFirst { it.name == preset.name }
        if (index >= 0) {
            isProgrammaticSelection = true
            spinnerProvider.setSelection(index)
            isProgrammaticSelection = false
        }
        tvProviderDesc.text = preset.description
    }

    private fun setupListeners() {
        btnRequestOverlay.setOnClickListener { requestOverlayPermission() }
        btnRequestCapture.setOnClickListener { requestCapturePermission() }
        btnSaveConfig.setOnClickListener { saveConfig() }
        btnStartService.setOnClickListener { startFloatingService() }
        btnStopService.setOnClickListener { stopFloatingService() }
    }

    // ==================== 权限 ====================

    private fun requestOverlayPermission() {
        if (hasOverlayPermission()) {
            Toast.makeText(this, "悬浮窗权限已授权", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        overlayPermissionLauncher.launch(intent)
    }

    private fun requestCapturePermission() {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        capturePermissionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun hasOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    private fun hasCapturePermission(): Boolean = captureData != null

    // ==================== 配置 ====================

    private fun saveConfig() {
        val config = AppConfig(
            apiUrl = etApiUrl.text?.toString()?.trim() ?: "",
            apiKey = etApiKey.text?.toString()?.trim() ?: "",
            model = etModel.text?.toString()?.trim() ?: "",
            prompt = etPrompt.text?.toString()?.takeIf { it.isNotBlank() }
                ?: AppConfig.DEFAULT_PROMPT
        )
        if (config.apiUrl.isBlank() || config.model.isBlank()) {
            Toast.makeText(this, "API 地址和模型名称不能为空", Toast.LENGTH_SHORT).show()
            return
        }
        prefsManager.saveConfig(config)
        Toast.makeText(this, "配置已保存", Toast.LENGTH_SHORT).show()
    }

    private fun setupPmTermsSwitch() {
        switchPmTerms.isChecked = prefsManager.isProjectMoonTermsEnabled()
        switchPmTerms.setOnCheckedChangeListener { _, isChecked ->
            prefsManager.setProjectMoonTermsEnabled(isChecked)
        }
    }

    // ==================== 服务 ====================

    private fun startFloatingService() {
        if (!hasOverlayPermission()) {
            Toast.makeText(this, "请先申请悬浮窗权限", Toast.LENGTH_SHORT).show()
            return
        }
        if (!hasCapturePermission()) {
            Toast.makeText(this, "请先申请截屏权限", Toast.LENGTH_SHORT).show()
            return
        }
        val data = captureData ?: return
        FloatingWindowService.start(this, captureResultCode, data)
        Toast.makeText(this, "悬浮窗已启动", Toast.LENGTH_SHORT).show()
        // 退到后台，让用户在其他应用中使用
        moveTaskToBack(true)
    }

    private fun stopFloatingService() {
        FloatingWindowService.stop(this)
        Toast.makeText(this, "悬浮窗已关闭", Toast.LENGTH_SHORT).show()
    }

    // ==================== 状态显示 ====================

    private fun updateStatus() {
        tvOverlayStatus.text = if (hasOverlayPermission()) {
            getString(R.string.status_granted)
        } else {
            getString(R.string.status_denied)
        }
        tvCaptureStatus.text = if (hasCapturePermission()) {
            getString(R.string.status_granted)
        } else {
            getString(R.string.status_denied)
        }
        tvServiceStatus.text = if (prefsManager.isServiceRunning()) {
            getString(R.string.service_running)
        } else {
            getString(R.string.service_stopped)
        }
    }
}
