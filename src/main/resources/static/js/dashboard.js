(() => {
  'use strict';
  const refreshButton = document.querySelector('#refresh');
  const automatic = document.querySelector('#auto-refresh');
  const error = document.querySelector('#connection-error');
  let inFlight = false;
  let pendingNavigation = null;
  let timer;

  const schedule = () => {
    clearTimeout(timer);
    if (automatic.checked) timer = setTimeout(() => {
      if (!document.hidden && !document.querySelector('#workspace input:focus')) refresh();
      else schedule();
    }, 5000);
  };

  async function refresh(url = new URL(location.href), navigate = false) {
    if (inFlight) {
      if (navigate) pendingNavigation = url;
      return;
    }
    inFlight = true;
    refreshButton.disabled = true;
    refreshButton.setAttribute('aria-busy', 'true');
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 12000);
    const workspace = document.querySelector('#workspace');
    const opened = [...workspace.querySelectorAll('details[open][data-detail-id]')].map(item => item.dataset.detailId);
    const marketScroll = workspace.querySelector('.market-list')?.scrollTop ?? 0;
    const activityScroll = workspace.querySelector('.activity-list')?.scrollTop ?? 0;
    const tableScroll = workspace.querySelector('.orders-panel .table-scroll')?.scrollLeft ?? 0;
    const focused = workspace.contains(document.activeElement) ? document.activeElement : null;
    const focusId = focused?.id;
    const focusHref = focused?.tagName === 'A' ? focused.getAttribute('href') : null;
    try {
      const fragmentUrl = new URL('/dashboard/content', location.origin);
      fragmentUrl.search = url.search;
      const response = await fetch(fragmentUrl, {cache: 'no-store', signal: controller.signal, headers: {'Accept': 'text/html'}});
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      const doc = new DOMParser().parseFromString(await response.text(), 'text/html');
      const replacement = doc.querySelector('#workspace');
      if (!replacement) throw new Error('Dashboard fragment is missing');
      for (const item of replacement.querySelectorAll('details[data-detail-id]')) item.open = opened.includes(item.dataset.detailId);
      workspace.replaceWith(replacement);
      const markets = replacement.querySelector('.market-list');
      const activity = replacement.querySelector('.activity-list');
      const table = replacement.querySelector('.orders-panel .table-scroll');
      if (markets) markets.scrollTop = marketScroll;
      if (activity) activity.scrollTop = activityScroll;
      if (table) table.scrollLeft = tableScroll;
      if (focusId) document.getElementById(focusId)?.focus({preventScroll: true});
      else if (focusHref) [...replacement.querySelectorAll('a')].find(a => a.getAttribute('href') === focusHref)?.focus({preventScroll: true});
      if (navigate) history.pushState(null, '', url.pathname + url.search + url.hash);
      error.hidden = true;
    } catch (failure) {
      error.hidden = false;
    } finally {
      clearTimeout(timeout);
      refreshButton.disabled = false;
      refreshButton.removeAttribute('aria-busy');
      inFlight = false;
      if (pendingNavigation) {
        const next = pendingNavigation;
        pendingNavigation = null;
        refresh(next, true);
      } else schedule();
    }
  }

  document.addEventListener('click', event => {
    const link = event.target.closest('a[data-refresh]');
    if (!link || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey || event.button !== 0) return;
    event.preventDefault();
    refresh(new URL(link.href), true);
  });
  document.addEventListener('submit', event => {
    if (!event.target.matches('form[data-refresh-form]')) return;
    event.preventDefault();
    const url = new URL('/', location.origin);
    url.search = new URLSearchParams(new FormData(event.target)).toString();
    refresh(url, true);
  });
  refreshButton.addEventListener('click', () => refresh());
  automatic.addEventListener('change', schedule);
  window.addEventListener('popstate', () => refresh());
  document.addEventListener('visibilitychange', () => {
    if (!document.hidden && automatic.checked) refresh();
  });
  schedule();
})();
