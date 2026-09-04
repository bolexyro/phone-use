import {
  POINTER_ARROW_FILL,
  POINTER_ARROW_PATH
} from "@dhd/screenshot-markers";

export function buildOverlayHtml(cursorDurationMs: number): string {
  const duration = JSON.stringify(cursorDurationMs);
  const pointerArrowFill = JSON.stringify(POINTER_ARROW_FILL);
  const pointerArrowPath = JSON.stringify(POINTER_ARROW_PATH);
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
    z-index: 100;
  }
  #cursor.swiping {
    filter:
      drop-shadow(0 0 10px rgba(0, 230, 255, 1))
      drop-shadow(0 0 22px rgba(43, 140, 219, 0.95))
      drop-shadow(0 3px 8px rgba(0, 0, 0, 0.6));
  }

  #pointer-icon {
    position: absolute;
    top: 0;
    left: 0;
    width: 22px;
    height: 22px;
    opacity: 1;
    transform: scale(1);
    transition: opacity 160ms ease, transform 160ms cubic-bezier(.2, .9, .3, 1);
  }
  #pointer-icon svg {
    display: block;
    width: 22px;
    height: 22px;
  }

  #mouse-icon {
    position: absolute;
    top: -2px;
    left: 1px;
    width: 20px;
    height: 26px;
    opacity: 0;
    transform: scale(0.5);
    transition: opacity 160ms ease, transform 160ms cubic-bezier(.2, .9, .3, 1);
  }
  #mouse-icon svg {
    display: block;
    width: 20px;
    height: 26px;
  }

  /* When in scroll mode (after a scroll action), show the capsule mouse */
  #cursor.mode-scroll #pointer-icon {
    opacity: 0;
    transform: scale(0.5);
  }
  #cursor.mode-scroll #mouse-icon {
    opacity: 1;
    transform: scale(1);
  }

  .wheel-down {
    animation: wheel-down 450ms infinite ease-in-out;
  }
  .wheel-up {
    animation: wheel-up 450ms infinite ease-in-out;
  }
  .wheel-right {
    animation: wheel-right 450ms infinite ease-in-out;
  }
  .wheel-left {
    animation: wheel-left 450ms infinite ease-in-out;
  }

  @keyframes wheel-down {
    0% { transform: translateY(-2px); opacity: 0.4; }
    50% { transform: translateY(2.5px); opacity: 1; }
    100% { transform: translateY(5px); opacity: 0.1; }
  }
  @keyframes wheel-up {
    0% { transform: translateY(5px); opacity: 0.1; }
    50% { transform: translateY(2.5px); opacity: 1; }
    100% { transform: translateY(-2px); opacity: 0.4; }
  }
  @keyframes wheel-right {
    0% { transform: translateX(-2px); opacity: 0.4; }
    50% { transform: translateX(2.5px); opacity: 1; }
    100% { transform: translateX(5px); opacity: 0.1; }
  }
  @keyframes wheel-left {
    0% { transform: translateX(5px); opacity: 0.1; }
    50% { transform: translateX(2.5px); opacity: 1; }
    100% { transform: translateX(-2px); opacity: 0.4; }
  }

  #cursor.swiping.swipe-right {
    transform: rotate(10deg);
  }
  #cursor.swiping.swipe-left {
    transform: rotate(-10deg);
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

  #trail-svg {
    position: absolute;
    top: 0;
    left: 0;
    width: 100%;
    height: 100%;
    pointer-events: none;
    z-index: 50;
  }

  .swipe-track {
    opacity: 0;
    transition: opacity 280ms ease-out;
  }
  .swipe-track.active {
    opacity: 1;
  }

  #scroll-badge {
    position: fixed;
    bottom: 28px;
    left: 50%;
    transform: translateX(-50%) translateY(10px);
    background: rgba(10, 18, 30, 0.85);
    border: 1px solid rgba(0, 230, 255, 0.45);
    box-shadow: 0 4px 20px rgba(0, 140, 255, 0.35), inset 0 0 12px rgba(0, 230, 255, 0.2);
    backdrop-filter: blur(10px);
    color: #e0f2fe;
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
    font-size: 13px;
    font-weight: 600;
    letter-spacing: 0.5px;
    padding: 7px 16px;
    border-radius: 20px;
    opacity: 0;
    pointer-events: none;
    transition: opacity 250ms ease, transform 250ms cubic-bezier(.2, .9, .3, 1);
    z-index: 200;
    display: flex;
    align-items: center;
    gap: 8px;
  }
  #scroll-badge.show {
    opacity: 1;
    transform: translateX(-50%) translateY(0);
  }
</style>
</head>
<body>
  <svg id="trail-svg">
    <defs>
      <linearGradient id="swipe-grad" x1="0%" y1="0%" x2="100%" y2="100%">
        <stop offset="0%" stop-color="#00e6ff" stop-opacity="0.9" />
        <stop offset="100%" stop-color="#2b8cdb" stop-opacity="0.1" />
      </linearGradient>
      <filter id="glow" x="-20%" y="-20%" width="140%" height="140%">
        <feGaussianBlur stdDeviation="3" result="blur" />
        <feMerge>
          <feMergeNode in="blur" />
          <feMergeNode in="SourceGraphic" />
        </feMerge>
      </filter>
    </defs>
    <g id="swipe-group" class="swipe-track">
      <line id="swipe-line" stroke="url(#swipe-grad)" stroke-width="5" stroke-linecap="round" filter="url(#glow)" />
      <circle id="swipe-start-dot" r="5" fill="#00e6ff" filter="url(#glow)" />
      <circle id="swipe-end-dot" r="4" fill="#2b8cdb" filter="url(#glow)" />
    </g>
  </svg>

  <div id="scroll-badge" aria-hidden="true">
    <span id="badge-icon">⤓</span>
    <span id="badge-text">Scroll Down</span>
  </div>

  <div id="cursor" aria-hidden="true">
    <div id="pointer-icon">
      <svg viewBox="0 0 48 48" role="presentation">
        <path
          d=${pointerArrowPath}
          fill=${pointerArrowFill}
          stroke="#ffffff"
          stroke-width="2.5"
          stroke-linejoin="round"
        />
      </svg>
    </div>
    <div id="mouse-icon">
      <svg viewBox="0 0 24 32" role="presentation">
        <!-- Outer mouse capsule body -->
        <rect
          x="2"
          y="2"
          width="20"
          height="28"
          rx="10"
          ry="10"
          fill="#2b8cdb"
          stroke="#ffffff"
          stroke-width="2"
        />
        <!-- Bottom palm rest subtle shading -->
        <path
          d="M 2 15 C 2 15 2 29 12 29 C 22 29 22 15 22 15 Z"
          fill="rgba(0, 0, 0, 0.15)"
        />
        <!-- Horizontal divider separating buttons from palm rest -->
        <line
          x1="2"
          y1="15"
          x2="22"
          y2="15"
          stroke="#ffffff"
          stroke-width="1.8"
        />
        <!-- Vertical divider between left and right buttons -->
        <line
          x1="12"
          y1="2"
          x2="12"
          y2="15"
          stroke="#ffffff"
          stroke-width="1.5"
        />
        <!-- Center scroll wheel -->
        <rect
          id="scroll-wheel"
          x="10"
          y="5"
          width="4"
          height="7"
          rx="2"
          fill="#ffffff"
          stroke="#2b8cdb"
          stroke-width="0.8"
        />
      </svg>
    </div>
  </div>
  <script>
    (() => {
      const cursor = document.getElementById("cursor");
      const scrollWheel = document.getElementById("scroll-wheel");
      const swipeGroup = document.getElementById("swipe-group");
      const swipeLine = document.getElementById("swipe-line");
      const swipeStartDot = document.getElementById("swipe-start-dot");
      const swipeEndDot = document.getElementById("swipe-end-dot");
      const scrollBadge = document.getElementById("scroll-badge");
      const badgeIcon = document.getElementById("badge-icon");
      const badgeText = document.getElementById("badge-text");

      let clickTimer = 0;
      let badgeTimer = 0;
      const HOTSPOT_X = 2;
      const HOTSPOT_Y = 2;
      const initial = {
        x: Math.max(0, Math.floor((window.innerWidth - cursor.offsetWidth) / 2)),
        y: Math.max(0, Math.floor((window.innerHeight - cursor.offsetHeight) / 2))
      };
      cursor.style.left = String(initial.x) + "px";
      cursor.style.top = String(initial.y) + "px";

      // Non-scroll action (click / tap): switches back to normal arrow cursor
      window.phoneControlShowCursor = (point) => {
        if (!cursor || !point) return;
        cursor.classList.remove("mode-scroll");
        cursor.classList.remove("swiping");
        if (scrollWheel) {
          scrollWheel.className = "";
        }
        cursor.style.transition = "left ${duration}ms cubic-bezier(.2, .9, .3, 1), top ${duration}ms cubic-bezier(.2, .9, .3, 1)";
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

      const DIRECTION_LABELS = {
        down: { icon: "⤓", text: "Scroll Down" },
        up: { icon: "⤒", text: "Scroll Up" },
        left: { icon: "⇤", text: "Scroll Left" },
        right: { icon: "⇥", text: "Scroll Right" }
      };

      // Scroll action: switches to capsule mouse cursor, keeps it centered, and REMAINS in capsule mode
      window.phoneControlShowScroll = (scroll) => {
        if (!cursor || !scroll) return;
        const dur = Math.max(150, scroll.durationMs || 300);

        // Show direction badge
        const info = DIRECTION_LABELS[scroll.direction] || { icon: "⤓", text: "Scroll " + scroll.direction };
        badgeIcon.textContent = info.icon;
        badgeText.textContent = info.text;
        scrollBadge.classList.add("show");
        window.clearTimeout(badgeTimer);
        badgeTimer = window.setTimeout(() => scrollBadge.classList.remove("show"), dur + 400);

        // Switch to capsule mouse and animate scroll wheel
        cursor.classList.add("mode-scroll");
        cursor.classList.remove("click", "swipe-left", "swipe-right");
        cursor.classList.add("swiping");
        if (scroll.direction === "left") cursor.classList.add("swipe-left");
        if (scroll.direction === "right") cursor.classList.add("swipe-right");

        if (scrollWheel) {
          if (scroll.direction === "up") scrollWheel.className = "wheel-up";
          else if (scroll.direction === "down") scrollWheel.className = "wheel-down";
          else if (scroll.direction === "left") scrollWheel.className = "wheel-left";
          else if (scroll.direction === "right") scrollWheel.className = "wheel-right";
        }

        // Keep capsule mouse centered on the scrolled container area
        const centerX = Math.max(0, Math.floor(((scroll.startX + scroll.endX) / 2) - HOTSPOT_X));
        const centerY = Math.max(0, Math.floor(((scroll.startY + scroll.endY) / 2) - HOTSPOT_Y));
        cursor.style.transition = "left 200ms cubic-bezier(.2, .9, .3, 1), top 200ms cubic-bezier(.2, .9, .3, 1)";
        cursor.style.left = String(centerX) + "px";
        cursor.style.top = String(centerY) + "px";

        // Draw swipe path line
        swipeLine.setAttribute("x1", String(scroll.startX));
        swipeLine.setAttribute("y1", String(scroll.startY));
        swipeLine.setAttribute("x2", String(scroll.endX));
        swipeLine.setAttribute("y2", String(scroll.endY));
        swipeStartDot.setAttribute("cx", String(scroll.startX));
        swipeStartDot.setAttribute("cy", String(scroll.startY));
        swipeEndDot.setAttribute("cx", String(scroll.endX));
        swipeEndDot.setAttribute("cy", String(scroll.endY));
        swipeGroup.classList.add("active");

        // Stop active swipe line and wheel spin, but RETAIN capsule mouse centered!
        window.setTimeout(() => {
          swipeGroup.classList.remove("active");
          cursor.classList.remove("swiping", "swipe-left", "swipe-right");
          if (scrollWheel) {
            scrollWheel.className = "";
          }
        }, dur + 100);
      };
    })();
  </script>
</body>
</html>`;
}
