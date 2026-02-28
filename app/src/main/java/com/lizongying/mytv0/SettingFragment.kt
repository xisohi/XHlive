package com.lizongying.mytv0

import MainViewModel
import MainViewModel.Companion.CACHE_FILE_NAME
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.marginBottom
import androidx.core.view.marginEnd
import androidx.core.view.marginTop
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.lizongying.mytv0.ModalFragment.Companion.KEY_URL
import com.lizongying.mytv0.SimpleServer.Companion.PORT
import com.lizongying.mytv0.databinding.SettingBinding
import kotlin.math.max
import kotlin.math.min

class SettingFragment : Fragment() {

    private var _binding: SettingBinding? = null
    private val binding get() = _binding!!

    private lateinit var uri: Uri
    private lateinit var updateManager: UpdateManager
    private var server = "http://${PortUtil.lan()}:$PORT"
    private lateinit var viewModel: MainViewModel

    companion object {
        const val TAG = "SettingFragment"
        const val PERMISSIONS_REQUEST_CODE = 1
        const val PERMISSION_READ_EXTERNAL_STORAGE_REQUEST_CODE = 2
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val application = requireActivity().applicationContext as MyTVApplication
        val context = requireContext()
        val mainActivity = (activity as MainActivity)

        _binding = SettingBinding.inflate(inflater, container, false)

        // 初始化 UpdateManager
        updateManager = UpdateManager(context, context.appVersionCode)
        Log.i(TAG, "UpdateManager初始化完成，当前版本码: ${context.appVersionCode}")

        // 检查按钮是否存在
        if (binding.checkVersion == null) {
            System.err.println("错误：checkVersion 按钮为空！")
        } else {
            binding.checkVersion.text = "检查更新"
            Log.i(TAG, "成功找到checkVersion按钮")
        }

        binding.versionName.text = "v${context.appVersionName}"
        binding.version.text = "https://github.com/xisohi/XHlive"

        // 初始化所有开关和设置
        initSwitches(mainActivity)

        // 初始化按钮样式和点击事件
        initButtons(mainActivity, application, context)

        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val context = requireActivity()
        val mainActivity = (activity as MainActivity)
        val application = context.applicationContext as MyTVApplication
        val imageHelper = application.imageHelper

        viewModel = ViewModelProvider(context)[MainViewModel::class.java]

        // 设置所有监听器
        setupListeners(mainActivity, imageHelper, context)

        // 延迟2秒后自动触发点击（用于测试，测试完成后可以删除）
        view.postDelayed({
            // binding.checkVersion?.performClick()
            // Log.e(TAG, "自动触发点击")
        }, 2000)
    }

    private fun initSwitches(mainActivity: MainActivity) {
        binding.switchChannelReversal.isChecked = SP.channelReversal
        binding.switchChannelReversal.setOnCheckedChangeListener { _, isChecked ->
            SP.channelReversal = isChecked
            mainActivity.settingActive()
        }

        binding.switchChannelNum.isChecked = SP.channelNum
        binding.switchChannelNum.setOnCheckedChangeListener { _, isChecked ->
            SP.channelNum = isChecked
            mainActivity.settingActive()
        }

        binding.switchTime.isChecked = SP.time
        binding.switchTime.setOnCheckedChangeListener { _, isChecked ->
            SP.time = isChecked
            mainActivity.settingActive()
        }

        binding.switchBootStartup.isChecked = SP.bootStartup
        binding.switchBootStartup.setOnCheckedChangeListener { _, isChecked ->
            SP.bootStartup = isChecked
            mainActivity.settingActive()
        }

        binding.switchRepeatInfo.isChecked = SP.repeatInfo
        binding.switchRepeatInfo.setOnCheckedChangeListener { _, isChecked ->
            SP.repeatInfo = isChecked
            mainActivity.settingActive()
        }

        binding.switchConfigAutoLoad.isChecked = SP.configAutoLoad
        binding.switchConfigAutoLoad.setOnCheckedChangeListener { _, isChecked ->
            SP.configAutoLoad = isChecked
            mainActivity.settingActive()
        }

        binding.switchDefaultLike.isChecked = SP.defaultLike
        binding.switchDefaultLike.setOnCheckedChangeListener { _, isChecked ->
            SP.defaultLike = isChecked
            mainActivity.settingActive()
        }

        binding.switchShowAllChannels.isChecked = SP.showAllChannels

        binding.switchCompactMenu.isChecked = SP.compactMenu
        binding.switchCompactMenu.setOnCheckedChangeListener { _, isChecked ->
            SP.compactMenu = isChecked
            mainActivity.updateMenuSize()
            mainActivity.settingActive()
        }

        binding.switchDisplaySeconds.isChecked = SP.displaySeconds

        binding.switchSoftDecode.isChecked = SP.softDecode
        binding.switchSoftDecode.setOnCheckedChangeListener { _, isChecked ->
            SP.softDecode = isChecked
            mainActivity.switchSoftDecode()
            mainActivity.settingActive()
        }
    }

    private fun initButtons(mainActivity: MainActivity, application: MyTVApplication, context: Context) {
        val txtTextSize = application.px2PxFont(binding.versionName.textSize)

        binding.content.layoutParams.width = application.px2Px(binding.content.layoutParams.width)
        binding.content.setPadding(
            application.px2Px(binding.content.paddingLeft),
            application.px2Px(binding.content.paddingTop),
            application.px2Px(binding.content.paddingRight),
            application.px2Px(binding.content.paddingBottom)
        )

        binding.name.textSize = application.px2PxFont(binding.name.textSize)
        binding.version.textSize = txtTextSize
        val layoutParamsVersion = binding.version.layoutParams as ViewGroup.MarginLayoutParams
        layoutParamsVersion.topMargin = application.px2Px(binding.version.marginTop)
        layoutParamsVersion.bottomMargin = application.px2Px(binding.version.marginBottom)
        binding.version.layoutParams = layoutParamsVersion

        val btnWidth = application.px2Px(binding.confirmConfig.layoutParams.width)
        val btnLayoutParams = binding.confirmConfig.layoutParams as ViewGroup.MarginLayoutParams
        btnLayoutParams.marginEnd = application.px2Px(binding.confirmConfig.marginEnd)

        binding.versionName.textSize = txtTextSize

        // 设置所有按钮的样式和点击事件
        val buttons = listOf(
            binding.remoteSettings,
            binding.confirmConfig,
            binding.clear,
            binding.checkVersion,
            binding.exit,
            binding.appreciate,
        )

        buttons.forEach { button ->
            button.layoutParams.width = btnWidth
            button.textSize = txtTextSize
            button.layoutParams = btnLayoutParams

            // 设置焦点变化背景
            button.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    button.background = ColorDrawable(
                        ContextCompat.getColor(context, R.color.focus)
                    )
                    button.setTextColor(
                        ContextCompat.getColor(context, R.color.white)
                    )
                } else {
                    button.background = ColorDrawable(
                        ContextCompat.getColor(context, R.color.description_blur)
                    )
                    button.setTextColor(
                        ContextCompat.getColor(context, R.color.blur)
                    )
                }
            }
        }

        // 单独为checkVersion设置点击事件（确保触发）
        setupCheckVersionClickListener(mainActivity)

        val textSizeSwitch = application.px2PxFont(binding.switchChannelReversal.textSize)
        val layoutParamsSwitch = binding.switchChannelReversal.layoutParams as ViewGroup.MarginLayoutParams
        layoutParamsSwitch.topMargin = application.px2Px(binding.switchChannelReversal.marginTop)

        val switches = listOf(
            binding.switchChannelReversal,
            binding.switchChannelNum,
            binding.switchTime,
            binding.switchBootStartup,
            binding.switchRepeatInfo,
            binding.switchConfigAutoLoad,
            binding.switchDefaultLike,
            binding.switchShowAllChannels,
            binding.switchCompactMenu,
            binding.switchDisplaySeconds,
            binding.switchSoftDecode,
        )

        switches.forEach { switch ->
            switch.textSize = textSizeSwitch
            switch.layoutParams = layoutParamsSwitch
            switch.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    switch.setTextColor(ContextCompat.getColor(context, R.color.focus))
                } else {
                    switch.setTextColor(ContextCompat.getColor(context, R.color.title_blur))
                }
            }
        }
    }

    private fun setupCheckVersionClickListener(mainActivity: MainActivity) {
        binding.checkVersion.apply {
            // 确保按钮可点击
            isClickable = true
            isFocusable = true

            // 设置点击事件
            setOnClickListener {
                // 1. Toast确认（最可靠）
                Toast.makeText(requireContext(), "开始检查更新", Toast.LENGTH_SHORT).show()

                // 2. 日志输出
                Log.i(TAG, "========== 检查更新按钮被点击 ==========")
                Log.i(TAG, "当前代理状态: ${com.lizongying.mytv0.Github.getProxyStatus()}")
                System.out.println("SettingFragment: 检查更新按钮被点击")

                // 3. 调用更新
                requestInstallPermissions()

                // 4. 隐藏设置界面
                mainActivity.settingActive()
            }

            // 设置长按测试
            setOnLongClickListener {
                Toast.makeText(requireContext(), "长按测试 - 版本: ${requireContext().appVersionName}", Toast.LENGTH_LONG).show()
                Log.i(TAG, "长按测试")
                true
            }
        }
    }

    private fun setupListeners(mainActivity: MainActivity, imageHelper: ImageHelper, context: Context) {

        binding.remoteSettings.setOnClickListener {
            val imageModalFragment = ModalFragment()
            val args = Bundle()
            args.putString(KEY_URL, server)
            imageModalFragment.arguments = args
            imageModalFragment.show(requireFragmentManager(), ModalFragment.TAG)
            mainActivity.settingActive()
        }

        binding.confirmConfig.setOnClickListener {
            val sourcesFragment = SourcesFragment()
            sourcesFragment.show(requireFragmentManager(), SourcesFragment.TAG)
            mainActivity.settingActive()
        }

        binding.appreciate.setOnClickListener {
            val imageModalFragment = ModalFragment()
            val args = Bundle()
            args.putInt(ModalFragment.KEY_DRAWABLE_ID, R.drawable.appreciate)
            imageModalFragment.arguments = args
            imageModalFragment.show(requireFragmentManager(), ModalFragment.TAG)
            mainActivity.settingActive()
        }

        binding.setting.setOnClickListener {
            hideSelf()
        }

        binding.exit.setOnClickListener {
            requireActivity().finishAffinity()
        }

        binding.switchDisplaySeconds.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setDisplaySeconds(isChecked)
        }

        binding.clear.setOnClickListener {
            clearSettings(mainActivity, imageHelper, context)
        }

        binding.switchShowAllChannels.setOnCheckedChangeListener { _, isChecked ->
            SP.showAllChannels = isChecked
            viewModel.groupModel.setChange()
            mainActivity.settingActive()
        }
    }

    private fun clearSettings(mainActivity: MainActivity, imageHelper: ImageHelper, context: Context) {
        SP.channelNum = SP.DEFAULT_CHANNEL_NUM
        SP.sources = SP.DEFAULT_SOURCES
        Log.i(TAG, "DEFAULT_SOURCES ${SP.DEFAULT_SOURCES}")
        viewModel.sources.init()

        SP.channelReversal = SP.DEFAULT_CHANNEL_REVERSAL
        SP.time = SP.DEFAULT_TIME
        SP.bootStartup = SP.DEFAULT_BOOT_STARTUP
        SP.repeatInfo = SP.DEFAULT_REPEAT_INFO
        SP.configAutoLoad = SP.DEFAULT_CONFIG_AUTO_LOAD
        SP.proxy = SP.DEFAULT_PROXY

        imageHelper.clearImage()
        SP.softDecode = SP.DEFAULT_SOFT_DECODE

        SP.configUrl = SP.DEFAULT_CONFIG_URL
        Log.i(TAG, "config url: ${SP.configUrl}")
        context.deleteFile(CACHE_FILE_NAME)
        viewModel.reset(context)
        confirmConfig()

        SP.channel = SP.DEFAULT_CHANNEL
        Log.i(TAG, "default channel: ${SP.channel}")
        confirmChannel()

        SP.deleteLike()
        Log.i(TAG, "clear like")

        SP.positionGroup = viewModel.groupModel.defaultPosition()
        viewModel.groupModel.initPosition()

        SP.position = SP.DEFAULT_POSITION
        Log.i(TAG, "list position: ${SP.position}")
        val tvListModel = viewModel.groupModel.getCurrentList()
        tvListModel?.setPosition(SP.DEFAULT_POSITION)
        tvListModel?.setPositionPlaying(SP.DEFAULT_POSITION)

        viewModel.groupModel.setPositionPlaying()
        viewModel.groupModel.getCurrentList()?.setPositionPlaying()
        viewModel.groupModel.getCurrent()?.setReady()

        SP.showAllChannels = SP.DEFAULT_SHOW_ALL_CHANNELS
        SP.compactMenu = SP.DEFAULT_COMPACT_MENU

        viewModel.setDisplaySeconds(SP.DEFAULT_DISPLAY_SECONDS)

        SP.epg = SP.DEFAULT_EPG
        viewModel.updateEPG()

        R.string.config_restored.showToast()
    }

    private fun requestInstallPermissions() {
        val context = requireContext()

        Log.i(TAG, "===== 开始请求权限 =====")
        Log.i(TAG, "调用 updateManager.checkAndUpdate()")

        // 直接调用更新，跳过权限检查以便测试
        updateManager.checkAndUpdate()

        // 原有权限代码暂时注释掉
        /*
        val permissionsList = mutableListOf<String>()

        System.out.println("SettingFragment: 點擊了更新按鈕")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            permissionsList.add(Manifest.permission.REQUEST_INSTALL_PACKAGES)
        }

        if (permissionsList.isNotEmpty()) {
            Log.i(TAG, "申請權限: $permissionsList")
            updateManager.checkAndUpdate()
        } else {
            updateManager.checkAndUpdate()
        }
        */
    }

    private fun requestReadPermissions() {
        val context = requireContext()
        val permissionsList = mutableListOf<String>()

        checkAndAddPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE, permissionsList)

        if (permissionsList.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                requireActivity(),
                permissionsList.toTypedArray(),
                PERMISSION_READ_EXTERNAL_STORAGE_REQUEST_CODE
            )
        } else {
            viewModel.importFromUri(uri)
        }
    }

    private fun checkAndAddPermission(context: Context, permission: String, permissionsList: MutableList<String>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
            permissionsList.add(permission)
        }
    }

    private fun confirmConfig() {
        if (SP.configUrl.isNullOrEmpty()) {
            Log.w(TAG, "SP.configUrl is null or empty")
            return
        }

        uri = Uri.parse(Utils.formatUrl(SP.configUrl!!))
        if (uri.scheme == "") {
            uri = uri.buildUpon().scheme("http").build()
        }
        if (uri.isAbsolute) {
            if (uri.scheme == "file") {
                requestReadPermissions()
            } else {
                viewModel.importFromUri(uri)
            }
        } else {
            R.string.invalid_config_address.showToast()
        }
        (activity as MainActivity).settingActive()
    }

    private fun confirmChannel() {
        SP.channel = min(max(SP.channel, 0), viewModel.groupModel.getAllList()!!.size())
        (activity as MainActivity).settingActive()
    }

    private fun hideSelf() {
        requireActivity().supportFragmentManager.beginTransaction()
            .hide(this)
            .commitAllowingStateLoss()
        (activity as MainActivity).showTimeFragment()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (_binding != null && !hidden) {
            binding.remoteSettings.requestFocus()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            PERMISSION_READ_EXTERNAL_STORAGE_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    viewModel.importFromUri(uri)
                } else {
                    R.string.authorization_failed.showToast()
                }
            }
            PERMISSIONS_REQUEST_CODE -> {
                val allPermissionsGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
                if (allPermissionsGranted) {
                    updateManager.checkAndUpdate()
                } else {
                    Log.w(TAG, "ask permissions failed")
                    R.string.authorization_failed.showToast()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}