/* ── BIT LANDING PAGE INTERACTIVE SCRIPT ── */

document.addEventListener('DOMContentLoaded', () => {
  initVoiceOrb();
  initTiltMockups();
  fetchLatestRelease();
});

/* ── 1. Interactive Monochrome Voice Orb ── */

function initVoiceOrb() {
  const canvas = document.getElementById('orbCanvas');
  if (!canvas) return;

  const ctx = canvas.getContext('2d');
  const width = (canvas.width = 280);
  const height = (canvas.height = 280);
  const centerX = width / 2;
  const centerY = height / 2;
  const baseRadius = 80;

  let time = 0;
  let mouseX = centerX;
  let mouseY = centerY;
  let isHovered = false;

  canvas.addEventListener('mousemove', (e) => {
    const rect = canvas.getBoundingClientRect();
    mouseX = e.clientX - rect.left;
    mouseY = e.clientY - rect.top;
    isHovered = true;
  });

  canvas.addEventListener('mouseleave', () => {
    mouseX = centerX;
    mouseY = centerY;
    isHovered = false;
  });

  function render() {
    time += 0.025;
    ctx.clearRect(0, 0, width, height);

    // Dynamic deformation
    const hoverDist = Math.hypot(mouseX - centerX, mouseY - centerY);
    const pulseFactor = isHovered ? Math.sin(time * 4) * 6 : Math.sin(time * 2) * 3;
    const radius = baseRadius + pulseFactor;

    // Outer aura
    const outerGrad = ctx.createRadialGradient(centerX, centerY, radius * 0.4, centerX, centerY, radius * 1.5);
    outerGrad.addColorStop(0, 'rgba(255, 255, 255, 0.25)');
    outerGrad.addColorStop(0.5, 'rgba(255, 255, 255, 0.08)');
    outerGrad.addColorStop(1, 'rgba(0, 0, 0, 0)');
    ctx.fillStyle = outerGrad;
    ctx.beginPath();
    ctx.arc(centerX, centerY, radius * 1.5, 0, Math.PI * 2);
    ctx.fill();

    // Inner core orb
    ctx.save();
    ctx.beginPath();
    
    // Waveform vertices
    const points = 64;
    for (let i = 0; i < points; i++) {
      const angle = (i / points) * Math.PI * 2;
      const noise = Math.sin(angle * 5 + time * 2) * 3 + Math.cos(angle * 3 - time * 3) * 2;
      const r = radius + noise;
      const x = centerX + Math.cos(angle) * r;
      const y = centerY + Math.sin(angle) * r;
      if (i === 0) ctx.moveTo(x, y);
      else ctx.lineTo(x, y);
    }
    ctx.closePath();

    // Monochrome sphere gradient
    const offsetX = (mouseX - centerX) * 0.15;
    const offsetY = (mouseY - centerY) * 0.15;
    const coreGrad = ctx.createRadialGradient(
      centerX - radius * 0.3 + offsetX,
      centerY - radius * 0.3 + offsetY,
      radius * 0.1,
      centerX + offsetX,
      centerY + offsetY,
      radius * 1.1
    );
    coreGrad.addColorStop(0, '#FFFFFF');
    coreGrad.addColorStop(0.3, '#E5E5E5');
    coreGrad.addColorStop(0.65, '#222222');
    coreGrad.addColorStop(1, '#050505');

    ctx.fillStyle = coreGrad;
    ctx.shadowColor = 'rgba(255, 255, 255, 0.4)';
    ctx.shadowBlur = 24;
    ctx.fill();
    ctx.restore();

    requestAnimationFrame(render);
  }

  render();
}

/* ── 2. 3D Tilt Mockup Interaction ── */

function initTiltMockups() {
  const cards = document.querySelectorAll('.mockup-card');
  cards.forEach((card) => {
    const frame = card.querySelector('.phone-frame');
    if (!frame) return;

    card.addEventListener('mousemove', (e) => {
      const rect = card.getBoundingClientRect();
      const x = e.clientX - rect.left - rect.width / 2;
      const y = e.clientY - rect.top - rect.height / 2;
      const rotateX = (-y / rect.height) * 12;
      const rotateY = (x / rect.width) * 12;
      frame.style.transform = `perspective(1000px) rotateX(${rotateX}deg) rotateY(${rotateY}deg) scale(1.03)`;
    });

    card.addEventListener('mouseleave', () => {
      frame.style.transform = 'perspective(1000px) rotateY(-4deg) rotateX(3deg) scale(1)';
    });
  });
}

/* ── 3. Live GitHub Release API Telemetry ── */

async function fetchLatestRelease() {
  const tagEl = document.getElementById('releaseTag');
  const dateEl = document.getElementById('releaseDate');
  const sizeEl = document.getElementById('releaseSize');
  const downloadBtn = document.getElementById('primaryDownloadBtn');

  if (!tagEl) return;

  try {
    const res = await fetch('https://api.github.com/repos/jaswanthsanjay88/Bit_Android/releases/latest');
    if (!res.ok) throw new Error('Failed to fetch release');

    const data = await res.json();
    const tag = data.tag_name || 'v1.9.3';
    const publishedAt = data.published_at
      ? new Date(data.published_at).toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' })
      : 'Jul 20, 2026';

    let universalAsset = data.assets?.find((a) => a.name.includes('universal')) || data.assets?.[0];
    let sizeStr = universalAsset
      ? (universalAsset.size / (1024 * 1024)).toFixed(1) + ' MB'
      : '~45 MB';

    tagEl.textContent = tag;
    if (dateEl) dateEl.textContent = publishedAt;
    if (sizeEl) sizeEl.textContent = sizeStr;

    // Update main header/hero download button link
    const downloadUrl = universalAsset?.browser_download_url || `https://github.com/jaswanthsanjay88/Bit_Android/releases/download/${tag}/app-universal-release.apk`;
    if (downloadBtn) {
      downloadBtn.href = downloadUrl;
    }
  } catch (err) {
    console.warn('GitHub API offline or rate-limited, using fallback release info.', err);
    tagEl.textContent = 'v1.9.3';
    if (dateEl) dateEl.textContent = 'Latest Release';
    if (sizeEl) sizeEl.textContent = 'ARM64 / Universal';
  }
}
