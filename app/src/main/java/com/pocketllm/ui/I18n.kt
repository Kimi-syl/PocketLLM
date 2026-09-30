package com.pocketllm.ui

object I18n {
    fun isEnglish(lang: String): Boolean {
        if (lang == "en") return true
        if (lang == "system") {
            val defaultLang = java.util.Locale.getDefault().language
            return defaultLang.startsWith("en")
        }
        return false
    }

    fun isSimplified(lang: String): Boolean {
        if (lang == "zh-CN") return true
        if (lang == "system") {
            val loc = java.util.Locale.getDefault()
            return loc.language == "zh" && (loc.country == "CN" || loc.script == "Hans")
        }
        return false
    }

    fun t(key: String, lang: String): String {
        val en = isEnglish(lang)
        val cn = isSimplified(lang)

        return when (key) {
            "settings" -> if (en) "Settings" else if (cn) "设置" else "設定"
            "search_service" -> if (en) "Search Service" else if (cn) "搜索服务" else "搜尋服務"
            "voice_service" -> if (en) "Voice Service" else if (cn) "语音服务" else "語音服務"
            "mcp" -> "MCP"
            "ui_agent_title" -> if (en) "UI Agent" else if (cn) "UI 智能体" else "UI 智能體"
            "workspace_env" -> if (en) "Workspace & Environment" else if (cn) "工作区与环境" else "工作區與環境"
            "scheduled_tasks" -> if (en) "Scheduled Tasks" else if (cn) "定时任务" else "定時任務"
            "skills" -> if (en) "Skills" else if (cn) "技能" else "技能"
            "lorebook" -> if (en) "Lorebook" else if (cn) "世界书" else "世界書"
            "memory" -> if (en) "Memory" else if (cn) "记忆" else "記憶"
            "quick_phrases" -> if (en) "Quick Phrases" else if (cn) "快捷短语" else "快捷短語"
            "prompt_injection" -> if (en) "Prompt Injection" else if (cn) "指令注入" else "指令注入"
            "network_proxy" -> if (en) "Network Proxy" else if (cn) "网络代理" else "網絡代理"
            "default_models" -> if (en) "Default Models" else if (cn) "默认模型" else "預設模型"
            "companion_settings" -> if (en) "Virtual Companion" else if (cn) "虚拟伴侣与助手" else "虛擬伴侶與助手"
            "customisation" -> if (en) "Customisation & UI" else if (cn) "偏好与外观定制" else "偏好與自訂"
            "accessibility" -> if (en) "Accessibility" else if (cn) "无障碍设置" else "無障礙設定"
            "data_settings" -> if (en) "Data Settings" else if (cn) "数据设置" else "資料設定"
            "data_backup" -> if (en) "Data Backup" else if (cn) "数据备份" else "資料備份"
            "chat_storage" -> if (en) "Chat Storage" else if (cn) "聊天记录存储" else "聊天記錄儲存"
            "about_group" -> if (en) "About" else if (cn) "关于" else "關於"
            "about" -> if (en) "About PocketLLM" else if (cn) "关于 PocketLLM" else "關於 PocketLLM"
            "statistics" -> if (en) "Statistics" else if (cn) "统计数据" else "統計數據"
            "documentation" -> if (en) "Documentation" else if (cn) "使用文档" else "使用文件"
            "logs" -> if (en) "Logs" else if (cn) "运行日志" else "運行日誌"
            "tool_descriptions" -> if (en) "Tool Descriptions" else if (cn) "工具描述" else "工具描述"
            "sponsor" -> if (en) "Sponsor & Support" else if (cn) "赞助与支持" else "贊助與支持"
            "language" -> if (en) "Language" else if (cn) "界面语言" else "介面語言"
            "settings_style" -> if (en) "Settings Style" else if (cn) "设置页面风格" else "設定頁面風格"
            "style_modern" -> if (en) "Modern Categorized Style" else if (cn) "现代分组风格" else "現代分組風格"
            "style_classic" -> if (en) "Classic Companion Style" else if (cn) "经典伴侣整合风格" else "經典伴侶整合風格"
            "high_contrast" -> if (en) "High Contrast Mode" else if (cn) "高对比度模式" else "高對比度模式"
            "haptic_feedback" -> if (en) "Haptic Feedback" else if (cn) "触觉震动反馈" else "觸覺震動回饋"
            "reduce_motion" -> if (en) "Reduce Motion" else if (cn) "减弱动态效果" else "減弱動態效果"
            "font_size" -> if (en) "Font Scaling" else if (cn) "字体大小缩放" else "字體大小縮放"
            "sandbox_title" -> if (en) "Linux Sandbox Environment" else if (cn) "Linux 沙盒运行环境" else "Linux 沙盒執行環境"
            "sandbox_ready" -> if (en) "Ready" else if (cn) "已就绪" else "已就緒"
            "sandbox_uninstalled" -> if (en) "Not Configured" else if (cn) "未设定" else "未設定"
            "sandbox_desc_ready" -> if (en) "Alpine 3.21 · Python 3, Node.js, Git, Shell tools ready" else if (cn) "Alpine 3.21 · 支持 Python 3, Node.js, Git, Shell 工具" else "Alpine 3.21 · 支援 Python 3, Node.js, Git, Shell 工具"
            "sandbox_desc_idle" -> if (en) "Isolated Linux container for running AI code & automation tools" else if (cn) "隔离 Linux 容器 · 供 AI 运行代码、工作区文件与自动化工具" else "隔離 Linux 容器 · 供 AI 執行代碼、工作區檔案與自動化工具"
            "companion_hero_title" -> if (en) "AI Companion Momo" else if (cn) "AI 虚拟伴侣 Momo" else "AI 虛擬伴侶 Momo"
            "companion_hero_desc" -> if (en) "Floating bubble, Live2D/3D avatar, personality & voice interaction" else if (cn) "桌面悬浮球、Live2D/3D 立绘、性格调校与语音互动" else "桌面懸浮球、Live2D/3D 立繪、性格調校與語音互動"
            "back" -> if (en) "Back" else if (cn) "返回" else "返回"
            "save" -> if (en) "Save" else if (cn) "保存" else "儲存"
            "cancel" -> if (en) "Cancel" else if (cn) "取消" else "取消"
            "confirm" -> if (en) "Confirm" else if (cn) "确定" else "確定"
            "close" -> if (en) "Close" else if (cn) "关闭" else "關閉"
            "lang_dropdown_title" -> if (en) "Interface Language" else if (cn) "界面语言" else "介面語言"
            "lang_dropdown_desc" -> if (en) "Select English or Chinese interface language" else if (cn) "选择繁体中文、简体中文或英文界面" else "選擇繁體中文、簡體中文或英文介面"
            "lang_zh_tw" -> "繁體中文"
            "lang_zh_cn" -> "简体中文"
            "lang_en" -> "English"
            "lang_system" -> if (en) "System Default" else if (cn) "跟随系统" else "跟隨系統"
            "settings_style_title" -> if (en) "Settings Page Style" else if (cn) "设置页面风格" else "設定頁面風格"
            "settings_style_desc" -> if (en) "Switch between Modern Categorized layout and Classic Companion layout" else if (cn) "在现代分组风格与经典伴侣风格之间自由切换" else "在現代分組風格與經典伴侶風格之間自由切換"
            "style_modern_desc" -> if (en) "Modular categorized layout with Sandbox, MCP & Search focus" else if (cn) "模块化分组布局，沙盒环境、MCP 与搜索优先" else "模組化分組佈局，沙盒環境、MCP 與搜尋優先"
            "style_classic_desc" -> if (en) "Classic companion-centric layout with 3D/2D avatar & floating bubble on top" else if (cn) "经典伴侣整合布局，桌面悬浮球、3D/Live2D立绘与心情报告置顶" else "經典伴侶整合佈局，桌面懸浮球、3D/Live2D立繪與心情報告置頂"
            "visual_accessibility" -> if (en) "Visual & Display" else if (cn) "视觉与显示" else "視覺與顯示"
            "high_contrast_desc" -> if (en) "Increase contrast of cards, outlines and background" else if (cn) "强化卡片边缘、描边与高对比度界面" else "強化卡片邊緣、描邊與高對比度介面"
            "font_size_desc" -> if (en) "Scale UI text size (85% - 135%) for comfortable reading" else if (cn) "按比例缩放应用内文字大小（85% - 135%）" else "按比例縮放應用內文字大小（85% - 135%）"
            "reduce_motion_desc" -> if (en) "Minimize animations, page transitions and live effects" else if (cn) "减少过渡动效与粒子动态效果" else "減少過渡動效與粒子動態效果"
            "touch_interaction" -> if (en) "Touch & Motor Accessibility" else if (cn) "触控与交互" else "觸控與互動"
            "large_touch_targets" -> if (en) "Enlarge Touch Targets" else if (cn) "增大触控目标" else "增大觸控目標"
            "large_touch_targets_desc" -> if (en) "Ensure buttons have at least 56dp touch area for easy tapping" else if (cn) "确保所有按钮具有至少 56dp 触控区域以方便点击" else "確保所有按鈕具有至少 56dp 觸控區域以方便點擊"
            "haptic_feedback_desc" -> if (en) "Vibrate subtly when pressing buttons and tapping avatars" else if (cn) "在点击按钮与交互时提供振动触觉反馈" else "在點擊按鈕與互動時提供震動觸覺回饋"
            "system_accessibility_service" -> if (en) "System Accessibility Service" else if (cn) "系统无障碍服务" else "系統無障礙服務"
            "system_accessibility_desc" -> if (en) "Enables screen reading and automation assistance for Momo & agent" else if (cn) "为虚拟伴侣与 AI 智能体提供屏幕读取与辅助支持" else "為虛擬伴侶與 AI 智慧體提供螢幕讀取與輔助支援"
            "screen_read_service" -> if (en) "PocketLLM Accessibility Reader" else if (cn) "PocketLLM 屏幕辅助读取服务" else "PocketLLM 螢幕輔助讀取服務"
            "service_active" -> if (en) "Connected & Running" else if (cn) "已连接并就绪" else "已連接並就緒"
            "service_inactive" -> if (en) "Not enabled in system settings" else if (cn) "未在系统设置中启用" else "未在系統設定中啟用"
            "service_manage" -> if (en) "Manage in Settings" else if (cn) "管理权限" else "管理權限"
            "service_enable" -> if (en) "Enable Service" else if (cn) "前往开启" else "前往開啟"
            "audio_accessibility" -> if (en) "Speech & Audio" else if (cn) "语音与听力" else "語音與聽力"
            "tts_auto_speak" -> if (en) "Auto-read AI Responses" else if (cn) "自动朗读回复" else "自動朗讀回覆"
            "tts_auto_speak_desc" -> if (en) "Automatically read out assistant & companion responses" else if (cn) "收到助手与伴侣消息时自动使用语音朗读" else "收到助手與伴侶訊息時自動使用語音朗讀"
            "companion_card_open" -> if (en) "Open Companion" else if (cn) "进入伴侣设置" else "進入伴侶設定"
            "companion_active_bubble" -> if (en) "Bubble Active" else if (cn) "悬浮球已开启" else "懸浮球已開啟"
            "companion_inactive_bubble" -> if (en) "Bubble Off" else if (cn) "悬浮球未开启" else "懸浮球未開啟"
            "customisation_group" -> if (en) "Personalization & UI" else if (cn) "个性化与外观" else "個性化與外觀"
            "theme_color_mode" -> if (en) "Color Theme" else if (cn) "颜色主题" else "色彩主題"
            "theme_light" -> if (en) "Light Mode" else if (cn) "浅色模式" else "淺色模式"
            "theme_dark" -> if (en) "Dark Mode" else if (cn) "深色模式" else "深色模式"
            "theme_system" -> if (en) "Follow System" else if (cn) "跟随系统" else "跟隨系統"
            "floating_companion_switch" -> if (en) "Desktop Floating Companion" else if (cn) "桌面悬浮伴侣" else "桌面懸浮伴侶"
            "floating_companion_desc" -> if (en) "Display Momo bubble / avatar over other applications" else if (cn) "在其他应用之上显示伴侣气泡与角色" else "在其他應用之上顯示伴侶氣泡與角色"
            "classic_companion_hero" -> if (en) "Classic Companion Momo" else if (cn) "经典伴侣 Momo" else "經典伴侶 Momo"
            "classic_companion_subtitle" -> if (en) "3D VRM & Live2D Avatar · Mood · Reminders · Floating Bubble" else if (cn) "3D VRM 与 Live2D 立绘 · 心情 · 每日提醒 · 桌面悬浮" else "3D VRM 與 Live2D 立繪 · 心情 · 每日提醒 · 桌面懸浮"
            "tab_chat" -> if (en) "Chat" else if (cn) "聊天" else "聊天"
            "tab_models" -> if (en) "Models" else if (cn) "模型" else "模型"
            "tab_usage" -> if (en) "Usage" else if (cn) "用量" else "用量"
            "tab_sandbox" -> if (en) "Sandbox" else if (cn) "沙盒环境" else "沙盒環境"
            "tab_server" -> if (en) "Server" else if (cn) "服务器" else "伺服器"
            "tab_keys" -> if (en) "API Keys" else if (cn) "API 密钥" else "API 金鑰"
            "tab_settings" -> if (en) "Settings" else if (cn) "设置" else "設定"
            "tab_logs" -> if (en) "Logs" else if (cn) "日志" else "日誌"
            "new_chat" -> if (en) "New Chat" else if (cn) "新建对话" else "開啟新對話"
            "recent_chats" -> if (en) "Recent Chats" else if (cn) "最近对话" else "近期對話"
            else -> key
        }
    }
}
