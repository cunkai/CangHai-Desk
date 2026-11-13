(function(global){
  'use strict';

  var LANGS = {
    'zh-Hans': '简体中文',
    'zh-Hant': '繁體中文',
    'en': 'English'
  };

  var DICT = {
    'zh-Hans': {
      lang_name: '简体中文',
      canghai_os: '沧海系统',
      settings: '设置',
      profile: '个人信息',
      username: '用户名字',
      change_avatar: '点击头像更换照片',
      save: '保存',
      cancel: '取消',
      saved: '已保存',
      about: '关于沧海系统',
      about_desc: '沧海系统 · 一个手机驱动多块外屏/Moonlight局域网投屏 · 沧海应用原生Web端运行',
      language: '语言 / 語言 / Language',
      dock: 'Dock 栏',
      dock_auto_hide: '自动隐藏 Dock',
      desktop_grid: '桌面网格',
      cols: '横向格子数',
      rows: '竖向格子数',
      cell_size: '每格大小',
      cursor_style: '鼠标样式',
      cursor_size: '指针大小',
      cursor_speed: '指针速度',
      cursor_manage: '管理',
      cursor_arrow: '箭头',
      cursor_dot: '圆点',
      cursor_block: '方块',
      cursor_custom: '自定义',
      wallpaper: '壁纸',
      wp_canghai: '沧海',
      wp_deep: '深海',
      wp_shallow: '浅滩',
      wp_midnight: '极夜深海',
      open: '打开',
      quit: '退出',
      remove_from_dock: '从 Dock 栏移除',
      pin_to_dock: '固定到 Dock 栏',
      export_pkg: '导出沧海软件…',
      export_ext: '.沧海软件',
      export_pkg_tip: '选择保存位置后就会写出',
      new_folder: '新建文件夹',
      new_file: '新建文件',
      install_app: '安装软件…',
      add_widget: '添加小组件',
      sort_by: '排序方式',
      launchpad: '启动台',
      search_apps: '搜索应用…',
      no_matching_apps: '未找到匹配的应用',
      sticky_note: '便签',
      input_monitor: '调试外设',
      input_recent_events: '最近事件',
      input_clear: '清空',
      install_title: '安装软件',
      install_subtitle: '选择或拖入 HTML 文件 / 沧海软件包',
      pkg_file_label: '沧海软件',
      pkg_file_desc: '选择 .沧海软件 / .canghai 软件包',
      html_file_label: '单个 HTML 文件',
      html_file_desc: '选择独立 HTML 网页应用',
      brand_logo_show: '显示左上角系统图标',
      close: '关闭',
      delete: '删除',
      edit: '编辑',
      all_apps: '全部应用',
      installed_apps: '已安装',
      system_apps: '系统应用'
    },
    'zh-Hant': {
      lang_name: '繁體中文',
      canghai_os: '滄海系統',
      settings: '設定',
      profile: '個人資訊',
      username: '使用者名稱',
      change_avatar: '點擊頭像更換照片',
      save: '儲存',
      cancel: '取消',
      saved: '已儲存',
      about: '關於滄海系統',
      about_desc: '滄海系統 · 一個手機驅動多塊外螢幕/Moonlight區域網投屏 · 滄海應用原生Web端運行',
      language: '語言 / 语言 / Language',
      dock: 'Dock 列',
      dock_auto_hide: '自動隱藏 Dock',
      desktop_grid: '桌面網格',
      cols: '橫向格子數',
      rows: '直向格子數',
      cell_size: '每格大小',
      cursor_style: '滑鼠樣式',
      cursor_size: '指針大小',
      cursor_speed: '指針速度',
      cursor_manage: '管理',
      cursor_arrow: '箭頭',
      cursor_dot: '圓點',
      cursor_block: '方塊',
      cursor_custom: '自訂',
      wallpaper: '桌布',
      wp_canghai: '滄海',
      wp_deep: '深海',
      wp_shallow: '淺灘',
      wp_midnight: '極夜深海',
      open: '開啟',
      quit: '結束',
      remove_from_dock: '從 Dock 列移除',
      pin_to_dock: '固定到 Dock 列',
      export_pkg: '導出滄海軟體…',
      export_ext: '.canghai',
      export_pkg_tip: '選擇儲存位置後即可寫出',
      new_folder: '新增資料夾',
      new_file: '新增檔案',
      install_app: '安裝軟體…',
      add_widget: '新增小工具',
      sort_by: '排序方式',
      launchpad: '啟動台',
      search_apps: '搜尋應用…',
      no_matching_apps: '未找到相符的應用',
      sticky_note: '便簽',
      input_monitor: '除錯外設',
      input_recent_events: '最近事件',
      input_clear: '清空',
      install_title: '安裝軟體',
      install_subtitle: '選擇或拖入 HTML 檔案 / 滄海軟體包',
      pkg_file_label: '滄海軟體',
      pkg_file_desc: '選擇 .canghai / .滄海軟件 軟體包',
      html_file_label: '單個 HTML 檔案',
      html_file_desc: '選擇獨立 HTML 網頁應用',
      brand_logo_show: '顯示左上角系統圖示',
      close: '關閉',
      delete: '刪除',
      edit: '編輯',
      all_apps: '全部應用',
      installed_apps: '已安裝',
      system_apps: '系統應用'
    },
    'en': {
      lang_name: 'English',
      canghai_os: 'Canghai OS',
      settings: 'Settings',
      profile: 'Profile',
      username: 'Username',
      change_avatar: 'Click avatar to change photo',
      save: 'Save',
      cancel: 'Cancel',
      saved: 'Saved',
      about: 'About Canghai OS',
      about_desc: 'Canghai OS · Multi-display & Moonlight streaming · Native Web apps',
      language: 'Language / 语言 / 語言',
      dock: 'Dock',
      dock_auto_hide: 'Auto-hide Dock',
      desktop_grid: 'Desktop Grid',
      cols: 'Columns',
      rows: 'Rows',
      cell_size: 'Grid Size',
      cursor_style: 'Cursor Style',
      cursor_size: 'Cursor Size',
      cursor_speed: 'Cursor Speed',
      cursor_manage: 'Manage',
      cursor_arrow: 'Arrow',
      cursor_dot: 'Dot',
      cursor_block: 'Block',
      cursor_custom: 'Custom',
      wallpaper: 'Wallpaper',
      wp_canghai: 'Canghai',
      wp_deep: 'Deep Sea',
      wp_shallow: 'Shallow',
      wp_midnight: 'Midnight',
      open: 'Open',
      quit: 'Quit',
      remove_from_dock: 'Remove from Dock',
      pin_to_dock: 'Pin to Dock',
      export_pkg: 'Export App…',
      export_ext: '.canghai',
      export_pkg_tip: 'Select save location to export',
      new_folder: 'New Folder',
      new_file: 'New File',
      install_app: 'Install App…',
      add_widget: 'Add Widget',
      sort_by: 'Sort By',
      launchpad: 'Launchpad',
      search_apps: 'Search apps…',
      no_matching_apps: 'No matching apps found',
      sticky_note: 'Notes',
      input_monitor: 'Input Monitor',
      input_recent_events: 'Recent Events',
      input_clear: 'Clear',
      install_title: 'Install App',
      install_subtitle: 'Choose or drop HTML file or Canghai package',
      pkg_file_label: 'Canghai App',
      pkg_file_desc: 'Select .canghai / .沧海软件 package',
      html_file_label: 'Single HTML File',
      html_file_desc: 'Select standalone HTML web app',
      brand_logo_show: 'Show Top-Left Brand Logo',
      close: 'Close',
      delete: 'Delete',
      edit: 'Edit',
      all_apps: 'All Apps',
      installed_apps: 'Installed',
      system_apps: 'System'
    }
  };

  var curLang = 'zh-Hans';

  function detectSystemLang(){
    try {
      var nav = (navigator.language || navigator.userLanguage || '').toLowerCase();
      if (/^(zh-tw|zh-hk|zh-mo|zh-hant)/i.test(nav)) return 'zh-Hant';
      if (/^zh/i.test(nav)) return 'zh-Hans';
      return 'en';
    } catch(e) {
      return 'zh-Hans';
    }
  }

  function initLang(){
    try {
      var saved = localStorage.getItem('canghai_language');
      if (saved && DICT[saved]) { curLang = saved; return curLang; }
    } catch(e){}
    curLang = detectSystemLang();
    return curLang;
  }

  initLang();

  var CHI18n = {
    LANGS: LANGS,
    getLang: function(){ return curLang; },
    getExportExt: function(){ return curLang === 'zh-Hans' ? '.沧海软件' : '.canghai'; },
    setLang: function(lang, notifyNative){
      if(!DICT[lang]) lang = 'zh-Hans';
      curLang = lang;
      try { localStorage.setItem('canghai_language', lang); } catch(e){}
      if(notifyNative){
        try {
          if(window.Aosen && window.Aosen.setLanguage) window.Aosen.setLanguage(lang);
          else if(window.Canghai && window.Canghai.setLanguage) window.Canghai.setLanguage(lang);
          else if(window.CanghaiPhone && window.CanghaiPhone.setLanguage) window.CanghaiPhone.setLanguage(lang);
          else if(window.CanghaiDesk && window.CanghaiDesk.setLanguage) window.CanghaiDesk.setLanguage(lang);
        } catch(e){}
      }
      try {
        CHI18n.applyDOM(document);
        document.dispatchEvent(new CustomEvent('canghai:lang', { detail: { lang: lang } }));
      } catch(e){}
    },
    t: function(key, fallback){
      var d = DICT[curLang] || DICT['zh-Hans'];
      if(d && d[key] !== undefined) return d[key];
      var def = DICT['zh-Hans'];
      if(def && def[key] !== undefined) return def[key];
      return fallback !== undefined ? fallback : key;
    },
    applyDOM: function(root){
      try {
        root = root || document;
        var els = root.querySelectorAll('[data-i18n]');
        for(var i=0; i<els.length; i++){
          var k = els[i].getAttribute('data-i18n');
          var val = CHI18n.t(k);
          if(val) els[i].textContent = val;
        }
        var phs = root.querySelectorAll('[data-i18n-ph]');
        for(var j=0; j<phs.length; j++){
          var pk = phs[j].getAttribute('data-i18n-ph');
          var pval = CHI18n.t(pk);
          if(pval) phs[j].setAttribute('placeholder', pval);
        }
      } catch(e){}
    }
  };

  document.addEventListener('DOMContentLoaded', function(){
    CHI18n.applyDOM(document);
  });

  global.CHI18n = CHI18n;
})(typeof window !== 'undefined' ? window : this);
