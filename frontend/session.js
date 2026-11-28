/* 员工会话（登录页与控制台共用）
   - 唯一写入点：登录页登录成功、控制台 S1 重新登录。
   - 存储口径：sessionStorage（关闭标签页即失效），不落 localStorage。
   - 键名与数据结构只在本文件定义，其他页面不得另写一套（R00 命名定源纪律）。
   用法：页面先加载本文件，再取 window.RFStaffSession。 */
(function () {
  "use strict";

  var KEY = "retail.staff.session";

  function read() {
    try {
      return JSON.parse(window.sessionStorage.getItem(KEY) || "null");
    } catch (e) {
      return null;
    }
  }

  function expired(s) {
    var t = Date.parse(s && s.expiresAt);
    return !isFinite(t) || t <= Date.now();
  }

  var api = {
    key: KEY,

    /** 读有效会话；无、损坏或已过期一律清掉并返回 null。 */
    load: function () {
      var s = read();
      if (!s || !s.token || expired(s)) {
        api.clear();
        return null;
      }
      return s;
    },

    /** 登录响应 → 会话；username 取登录表单（响应里没有 username，只有 displayName）。 */
    save: function (login, username) {
      var s = {
        token: login.token,
        username: username || "",
        displayName: login.displayName || username || "",
        roles: login.roles || [],
        permissions: login.permissions || [],
        subjectType: login.subjectType || "EMPLOYEE",
        expiresAt: login.expiresAt || ""
      };
      try {
        window.sessionStorage.setItem(KEY, JSON.stringify(s));
      } catch (e) {
        /* 隐私模式等不可写场景：本次会话不持久，页面内仍可用 */
      }
      return s;
    },

    clear: function () {
      try {
        window.sessionStorage.removeItem(KEY);
      } catch (e) {
        /* ignore */
      }
    },

    token: function () {
      var s = api.load();
      return s ? s.token : "";
    },

    /** 页面准入：无有效会话即跳登录页（登录页/控制台/客户页共用同一口径）。 */
    require: function (loginUrl) {
      var s = api.load();
      if (!s) location.replace(loginUrl || "login.html");
      return s;
    }
  };

  window.RFStaffSession = api;
})();
