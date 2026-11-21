(() => {
  if (window !== window.top) return;

  const applyViewport = () => {
    const head = document.head;
    if (!head) return;
    let viewport = head.querySelector('meta[name="viewport"]');
    if (!viewport) {
      viewport = document.createElement('meta');
      viewport.name = 'viewport';
      head.append(viewport);
    }
    viewport.content = 'width=device-width, initial-scale=1, viewport-fit=contain';
  };

  let fitQueued = false;
  const scheduleFit = () => {
    if (fitQueued) return;
    fitQueued = true;
    requestAnimationFrame(() => {
      fitQueued = false;
      const body = document.body;
      const width = document.documentElement.clientWidth;
      if (!body || width <= 0) return;

      const zoom = body.style.zoom;
      body.style.zoom = '1';
      const contentWidth = Math.max(body.scrollWidth, body.getBoundingClientRect().width);
      const next = contentWidth > width + 1
        ? String(Math.max(0.40, width / contentWidth))
        : zoom && zoom !== '1' ? '1' : zoom;
      body.style.zoom = next;
    });
  };

  applyViewport();
  document.addEventListener('DOMContentLoaded', () => {
    applyViewport();
    scheduleFit();
  }, { once: true });
  window.addEventListener('load', scheduleFit, { once: true });
  window.addEventListener('resize', scheduleFit, { passive: true });
})();
