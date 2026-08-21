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
    width: 22px;
    height: 22px;
    opacity: 1;
    transform-origin: 11px 11px;
    transform: rotate(0deg) translateY(0);
    filter:
      drop-shadow(0 0 4px rgba(43, 140, 219, 0.95))
      drop-shadow(0 0 9px rgba(43, 140, 219, 0.65))
      drop-shadow(0 2px 4px rgba(0, 0, 0, 0.35));
    transition:
      left ${duration}ms cubic-bezier(.2, .9, .3, 1),
      top ${duration}ms cubic-bezier(.2, .9, .3, 1);
    pointer-events: none;
  }
  #cursor svg {
    display: block;
    width: 22px;
    height: 22px;
  }
  #cursor.click {
    animation: cursor-click 480ms cubic-bezier(.16, .84, .32, 1.25);
  }
  @keyframes cursor-click {
    0% {
      transform: rotate(0deg) translateY(0);
    }
    28% {
      transform: rotate(32deg) translateY(4px) scale(0.9);
      filter:
        drop-shadow(0 0 10px rgba(0, 230, 255, 1))
        drop-shadow(0 0 20px rgba(43, 140, 219, 0.95))
        drop-shadow(0 2px 6px rgba(0, 0, 0, 0.5));
    }
    55% {
      transform: rotate(32deg) translateY(4px) scale(0.9);
      filter:
        drop-shadow(0 0 10px rgba(0, 230, 255, 1))
        drop-shadow(0 0 20px rgba(43, 140, 219, 0.95))
        drop-shadow(0 2px 6px rgba(0, 0, 0, 0.5));
    }
    82% {
      transform: rotate(-4deg) translateY(-1px) scale(1.03);
    }
    100% {
      transform: rotate(0deg) translateY(0) scale(1);
    }
  }
</style>
</head>
<body>
  <div id="cursor" aria-hidden="true">
    <svg viewBox="0 0 48 48" role="presentation">
      <path
        d="M 4 4 L 38 16 L 24 24 L 16 38 Z"
        fill="#2b8cdb"
        stroke="#ffffff"
        stroke-width="2.5"
        stroke-linejoin="round"
      />
    </svg>
  </div>
  <script>
    (() => {
      const cursor = document.getElementById("cursor");
      let clickTimer = 0;
      const HOTSPOT_X = 2;
      const HOTSPOT_Y = 2;
      const initial = {
        x: Math.max(0, Math.floor((window.innerWidth - cursor.offsetWidth) / 2)),
        y: Math.max(0, Math.floor((window.innerHeight - cursor.offsetHeight) / 2))
      };
      cursor.style.left = String(initial.x) + "px";
      cursor.style.top = String(initial.y) + "px";
      window.phoneControlShowCursor = (point) => {
        if (!cursor || !point) return;
        cursor.style.left = String(point.localX - HOTSPOT_X) + "px";
        cursor.style.top = String(point.localY - HOTSPOT_Y) + "px";
        window.clearTimeout(clickTimer);
        clickTimer = window.setTimeout(() => {
          cursor.classList.remove("click");
          void cursor.offsetWidth;
          cursor.classList.add("click");
          clickTimer = window.setTimeout(() => cursor.classList.remove("click"), 500);
        }, ${duration});
      };
    })();
  </script>
</body>
</html>`;
}
