// ---- live clock (pure client-side, no backend round trip needed) ----
function tickClock() {
  const now = new Date();
  const timeEl = document.getElementById('clock-time');
  const dateEl = document.getElementById('clock-date');
  const ringEl = document.getElementById('clock-ring-fill');
  if (!timeEl || !dateEl || !ringEl) return;

  const hh = String(now.getHours()).padStart(2, '0');
  const mm = String(now.getMinutes()).padStart(2, '0');
  timeEl.textContent = `${hh}:${mm}`;

  dateEl.textContent = now.toLocaleDateString('de-DE', {
    weekday: 'long', day: '2-digit', month: 'long'
  });

  // ring fill represents progress through the current day
  const secondsToday = now.getHours() * 3600 + now.getMinutes() * 60 + now.getSeconds();
  const pct = Math.round((secondsToday / 86400) * 100);
  ringEl.style.setProperty('--pct', pct);
}
tickClock();
setInterval(tickClock, 1000 * 15);

// ---- central orb: front-end "listening" toggle ----
// Not wired to a backend endpoint yet — hook this up to VoiceController
// (e.g. hx-post="/voice/toggle") once voice capture is implemented.
document.addEventListener('click', (e) => {
  const orb = e.target.closest('.orb .core');
  if (!orb) return;
  const wrap = orb.closest('.orb');
  wrap.classList.toggle('listening');
  const hint = wrap.parentElement.querySelector('.orb-hint');
  if (hint) {
    hint.textContent = wrap.classList.contains('listening')
      ? 'Hört zu … noch einmal klicken zum Beenden'
      : 'Klicken oder sprechen zum Starten';
  }
});

// ---- keep the log terminal scrolled to the newest line after htmx swaps ----
document.body.addEventListener('htmx:afterSwap', (e) => {
  if (e.target && e.target.id === 'logs-panel') {
    e.target.scrollTop = e.target.scrollHeight;
  }
  if (e.target && e.target.id === 'chat-log') {
    e.target.scrollTop = e.target.scrollHeight;
  }
});

// ---- htmx swaps no 4xx/5xx responses by default: show the problem detail instead of silently doing nothing ----
function showHtmxError(target, message) {
  if (!target) return;
  let box = target.querySelector(':scope > .htmx-error');
  if (!box) {
    box = document.createElement('div');
    box.className = 'htmx-error error';
    target.prepend(box);
  }
  box.textContent = message;
}

document.body.addEventListener('htmx:responseError', (e) => {
  const xhr = e.detail.xhr;
  let message = `Fehler ${xhr.status}`;
  try {
    const problem = JSON.parse(xhr.responseText);
    if (problem && problem.detail) message = problem.detail;
  } catch (_) {
    // kein problem+json (z. B. HTML-Fehlerseite): beim Statuscode bleiben
  }
  console.error('htmx-Anfrage fehlgeschlagen', e.detail.pathInfo?.requestPath, xhr.status, xhr.responseText);
  showHtmxError(e.detail.target, message);
});

document.body.addEventListener('htmx:sendError', (e) => {
  console.error('htmx-Anfrage nicht gesendet (Server nicht erreichbar?)', e.detail.pathInfo?.requestPath);
  showHtmxError(e.detail.target, 'Jarvis ist nicht erreichbar.');
});

// a later successful swap clears the error box automatically, because the target content is replaced
