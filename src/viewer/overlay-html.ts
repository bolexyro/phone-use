export function buildOverlayHtml(cursorDurationMs: number): string {
  const duration = JSON.stringify(cursorDurationMs);
  return `<!doctype html>
<html>
<head>
<meta charset="utf-8">
<style>
  html, body {
    width: 100%;
    height: 100%;
    margin: 0;
    overflow: hidden;
    background: transparent;
    pointer-events: none;
  }
  #cursor {
    position: absolute;
    width: 46px;
    height: 58px;
    opacity: 0;
    transform-origin: 0 0;
    transform: scale(.78);
    filter: drop-shadow(0 0 6px #00e5ff);
    pointer-events: none;
  }
  #cursor svg {
    display: block;
    width: 46px;
    height: 58px;
  }
  #cursor.show {
    animation: cursor-pop ${duration}ms ease-out forwards;
  }
  @keyframes cursor-pop {
    0% { opacity: 0; transform: scale(.78); }
    12% { opacity: 1; transform: scale(1); }
    78% { opacity: 1; transform: scale(1); }
    100% { opacity: 0; transform: scale(.92); }
  }
</style>
</head>
<body>
  <div id="cursor" aria-hidden="true">
    <svg viewBox="0 0 46 58" role="presentation">
      <path
        d="M 3 2 L 3 43 L 14 33 L 23 55 L 32 51 L 23 29 L 43 29 Z"
        fill="#050505"
        stroke="#ffffff"
        stroke-width="3"
        stroke-linejoin="round"
      />
      <path
        d="M 8 12 L 8 33 L 14 27 L 20 42"
        fill="none"
        stroke="#00e5ff"
        stroke-width="2.5"
        stroke-linecap="round"
        stroke-linejoin="round"
      />
    </svg>
  </div>
  <script>
    (() => {
      const cursor = document.getElementById("cursor");
      let hideTimer = 0;
      window.phoneControlShowCursor = (point) => {
        if (!cursor || !point) return;
        cursor.style.left = String(point.localX) + "px";
        cursor.style.top = String(point.localY) + "px";
        cursor.classList.remove("show");
        void cursor.offsetWidth;
        cursor.classList.add("show");
        window.clearTimeout(hideTimer);
        hideTimer = window.setTimeout(() => cursor.classList.remove("show"), ${duration});
      };
    })();
  </script>
</body>
</html>`;
}
