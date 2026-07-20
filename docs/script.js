/* ==========================================================================
   BIT AI — Interactive Landing Page Script
   ========================================================================== */

document.addEventListener('DOMContentLoaded', () => {
  initReleaseData();
  initShowcaseTabs();
  initVoicePlayground();
  initPhone3DTilt();
  initNavbarScroll();
});

/* ── 1. Fetch GitHub Latest Release Metadata ── */
async function initReleaseData() {
  const repo = 'jaswanthsanjay88/Bit_Android';
  const tagElements = document.querySelectorAll('.github-release-tag');
  const sizeElements = document.querySelectorAll('.github-release-size');
  const downloadBtns = document.querySelectorAll('.github-download-btn');

  try {
    const response = await fetch(`https://api.github.com/repos/${repo}/releases/latest`);
    if (!response.ok) throw new Error('Release fetch failed');
    const data = await response.json();

    const tag = data.tag_name || 'v1.9.3';
    tagElements.forEach(el => el.textContent = tag);

    // Find universal APK asset if available
    const universalApk = data.assets?.find(a => a.name.includes('universal')) || data.assets?.[0];
    if (universalApk) {
      const sizeMb = (universalApk.size / (1024 * 1024)).toFixed(1);
      sizeElements.forEach(el => el.textContent = `${sizeMb} MB`);
      downloadBtns.forEach(btn => {
        btn.href = universalApk.browser_download_url;
      });
    }
  } catch (err) {
    console.log('Using default release fallback info:', err);
    tagElements.forEach(el => el.textContent = 'v1.9.3');
    sizeElements.forEach(el => el.textContent = '84.2 MB');
    downloadBtns.forEach(btn => {
      btn.href = `https://github.com/${repo}/releases/latest`;
    });
  }
}

/* ── 2. Interactive Mockup Tab Switcher ── */
function initShowcaseTabs() {
  const tabs = document.querySelectorAll('.showcase-tab');
  const screenImg = document.getElementById('phoneScreenImg');

  if (!tabs.length || !screenImg) return;

  tabs.forEach(tab => {
    tab.addEventListener('click', () => {
      // Remove active from all
      tabs.forEach(t => t.classList.remove('active'));
      tab.classList.add('active');

      const src = tab.dataset.src;
      if (src) {
        screenImg.style.opacity = '0.3';
        setTimeout(() => {
          screenImg.src = src;
          screenImg.style.opacity = '1';
        }, 150);
      }
    });
  });
}

/* ── 3. Interactive Voice Mode Playground Simulation ── */
function initVoicePlayground() {
  const orb = document.getElementById('voiceOrb');
  const waveBars = document.querySelectorAll('.wave-bar');
  const transcriptEl = document.getElementById('voiceTranscript');
  const startBtn = document.getElementById('testVoiceBtn');

  if (!orb || !waveBars.length) return;

  const sampleTranscripts = [
    '"What models are currently running locally on device?"',
    '"Analyze this chart image and extract key metrics."',
    '"Search local PDF documentation for security guidelines."',
    '"Explain quantum annealing in simple terms."'
  ];

  let isSimulating = false;
  let waveInterval = null;

  function startSimulation() {
    if (isSimulating) return;
    isSimulating = true;
    if (transcriptEl) transcriptEl.textContent = 'Listening for speech…';

    // Animate wave bars
    waveInterval = setInterval(() => {
      waveBars.forEach(bar => {
        const height = Math.floor(Math.random() * 36) + 8;
        bar.style.height = `${height}px`;
        bar.classList.add('active');
      });
    }, 120);

    setTimeout(() => {
      clearInterval(waveInterval);
      waveBars.forEach(bar => {
        bar.style.height = '12px';
        bar.classList.remove('active');
      });

      const randomText = sampleTranscripts[Math.floor(Math.random() * sampleTranscripts.length)];
      if (transcriptEl) transcriptEl.textContent = randomText;
      isSimulating = false;
    }, 2500);
  }

  orb.addEventListener('click', startSimulation);
  if (startBtn) startBtn.addEventListener('click', startSimulation);
}

/* ── 4. 3D Parallax Tilt Effect on Phone Device ── */
function initPhone3DTilt() {
  const viewport = document.querySelector('.mockup-viewport');
  const device = document.querySelector('.phone-device');

  if (!viewport || !device) return;

  viewport.addEventListener('mousemove', (e) => {
    const rect = viewport.getBoundingClientRect();
    const x = e.clientX - rect.left - rect.width / 2;
    const y = e.clientY - rect.top - rect.height / 2;

    const rotateY = (x / rect.width) * 16;
    const rotateX = -(y / rect.height) * 16;

    device.style.transform = `rotateY(${rotateY}deg) rotateX(${rotateX}deg) scale(1.02)`;
  });

  viewport.addEventListener('mouseleave', () => {
    device.style.transform = `rotateY(-8deg) rotateX(4deg) scale(1)`;
  });
}

/* ── 5. Navbar Backdrop Blur on Scroll ── */
function initNavbarScroll() {
  const navbar = document.querySelector('.navbar');
  if (!navbar) return;

  window.addEventListener('scroll', () => {
    if (window.scrollY > 40) {
      navbar.style.background = 'rgba(0, 0, 0, 0.9)';
      navbar.style.borderColor = 'rgba(255, 255, 255, 0.15)';
    } else {
      navbar.style.background = 'rgba(0, 0, 0, 0.75)';
      navbar.style.borderColor = 'rgba(255, 255, 255, 0.08)';
    }
  });
}
