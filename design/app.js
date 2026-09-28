/* ===== 视频解析工具 · UI 交互（纯前端演示，未对接后端） ===== */
(function () {
  'use strict';

  /* ---------- DOM ---------- */
  var phone = document.getElementById('phone');
  var input = document.getElementById('link-input');
  var inputCard = document.getElementById('input-card');
  var parseBtn = document.getElementById('parse-btn');
  var aboutBtn = document.getElementById('about-btn');
  var backdrop = document.getElementById('backdrop');

  /* ---------- 事件：关于弹窗 ---------- */
  aboutBtn.addEventListener('click', function () {
    phone.classList.add('about-open');
  });
  backdrop.addEventListener('click', closeAbout);
  document.addEventListener('keydown', function (e) {
    if (e.key === 'Escape') closeAbout();
  });

  function closeAbout() {
    phone.classList.remove('about-open');
  }

  /* ---------- 事件：解析（演示反馈，未请求后端） ---------- */
  parseBtn.addEventListener('click', doParse);

  function doParse() {
    if (!input.value.trim()) {
      inputCard.classList.remove('shake');
      void inputCard.offsetWidth;
      inputCard.classList.add('shake');
      return;
    }
    parseBtn.classList.remove('pulse');
    void parseBtn.offsetWidth;
    parseBtn.classList.add('pulse');
  }
})();
