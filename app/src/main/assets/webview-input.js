(() => {
  if (window.__openCodeHostInput) return;
  window.__openCodeHostInput = true;

  // Apply before the next touch starts, including handles mounted by SPA routes.
  // Pointer capture alone does not prevent Chromium from cancelling a drag to pan.
  const style = document.createElement("style");
  style.textContent = '[data-component="resize-handle"] { touch-action: none; }';
  document.head.appendChild(style);

  // Trackpad pinches can arrive as Ctrl+wheel instead of native pinch events.
  window.addEventListener("wheel", event => {
    if (event.ctrlKey) event.preventDefault();
  }, { capture: true, passive: false });

  let pressedTarget;
  window.addEventListener("pointerdown", event => { pressedTarget = event.target; }, true);
  window.addEventListener("click", event => {
    // Touch menu actions run on pointerup. If the menu is replaced by a dialog,
    // Android can retarget the following compatibility click to its new overlay.
    // A real backdrop dismissal must begin on that same backdrop.
    const overlay = event.target.closest?.('[data-component="dialog-overlay"]');
    if (event.detail > 0 && overlay && !overlay.contains(pressedTarget)) {
      event.preventDefault();
      event.stopImmediatePropagation();
    }
    pressedTarget = undefined;
  }, true);

  let resize;
  const finish = (session, cancel) => {
    if (resize !== session) return;
    resize = undefined;
    if (cancel) session.handle.dispatchEvent(new PointerEvent("pointercancel", {
      bubbles: true, pointerId: session.id, pointerType: session.type,
    }));
    if (session.handle.hasPointerCapture(session.id)) session.handle.releasePointerCapture(session.id);
    Object.assign(document.body.style, session.body);
  };
  window.addEventListener("pointerdown", event => {
    if (resize) finish(resize, true);
    const handle = event.target.closest?.('[data-component="resize-handle"]');
    if (!handle || event.button !== 0 || event.ctrlKey || event.detail > 1) return;
    const { cursor, overflow, userSelect } = document.body.style;
    resize = { handle, id: event.pointerId, type: event.pointerType, body: { cursor, overflow, userSelect } };
  }, true);
  window.addEventListener("pointermove", event => {
    // A mouse hover proves the button is up, even if a window switch swallowed
    // the release. Cancel only the resize we observed, retaining normal cursors.
    if (resize && event.pointerType === "mouse" && event.buttons === 0) finish(resize, true);
  }, true);
  for (const type of ["pointerup", "pointercancel", "lostpointercapture"]) {
    window.addEventListener(type, event => {
      const session = resize;
      if (session?.id === event.pointerId) queueMicrotask(() => finish(session, type !== "pointerup"));
    }, true);
  }
  const cancel = () => { if (resize) finish(resize, true); };
  window.addEventListener("blur", cancel);
  window.addEventListener("pagehide", cancel);
  document.addEventListener("visibilitychange", () => { if (document.hidden) cancel(); });
})();
