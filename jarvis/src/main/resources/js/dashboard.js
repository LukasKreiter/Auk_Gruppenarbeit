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
