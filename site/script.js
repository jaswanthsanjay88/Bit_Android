/* ==========================================================================
   BIT — Landing Page Client-side Script (Google Antigravity Particle System)
   ========================================================================== */

document.addEventListener('DOMContentLoaded', () => {
  initNavbarScroll();
  initMouseParallax();
  initGitHubApi();
  initIntersectionObserver();
  initGoogleAntigravityParticles();
});

/* ── Sticky Navbar Blur ── */
function initNavbarScroll() {
  const navbar = document.getElementById('navbar');
  if (!navbar) return;

  const handleScroll = () => {
    if (window.scrollY > 40) {
      navbar.classList.add('scrolled');
    } else {
      navbar.classList.remove('scrolled');
    }
  };

  window.addEventListener('scroll', handleScroll, { passive: true });
  handleScroll();
}

/* ── 3D Hero Mouse Parallax ── */
function initMouseParallax() {
  const stage = document.getElementById('heroStage');
  const phoneLeft = document.getElementById('phoneLeft');
  const phoneRight = document.getElementById('phoneRight');

  if (!stage || !phoneLeft || !phoneRight) return;
  if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) return;

  let bounds = stage.getBoundingClientRect();
  
  window.addEventListener('resize', () => {
    bounds = stage.getBoundingClientRect();
  });

  stage.addEventListener('mousemove', (e) => {
    const mouseX = e.clientX - bounds.left - bounds.width / 2;
    const mouseY = e.clientY - bounds.top - bounds.height / 2;

    const rotX = (mouseY / bounds.height) * -16;
    const rotY = (mouseX / bounds.width) * 16;

    phoneLeft.style.transform = `rotateY(${-18 + rotY}deg) rotateX(${12 + rotX}deg) translateZ(30px)`;
    phoneRight.style.transform = `rotateY(${18 + rotY}deg) rotateX(${12 + rotX}deg) translateZ(10px)`;
  });

  stage.addEventListener('mouseleave', () => {
    phoneLeft.style.transform = `rotateY(-18deg) rotateX(12deg) translateZ(20px)`;
    phoneRight.style.transform = `rotateY(18deg) rotateX(12deg) translateZ(0px)`;
  });
}

/* ── Google Antigravity Interactive Particle Constellation Engine ── */
function initGoogleAntigravityParticles() {
  const canvas = document.getElementById('heroParticleCanvas');
  if (!canvas) return;

  const ctx = canvas.getContext('2d');
  if (!ctx) return;

  let width = (canvas.width = canvas.offsetWidth || window.innerWidth);
  let height = (canvas.height = canvas.offsetHeight || 650);

  const NUM_PARTICLES = 2400;
  const particles = [];
  let currentTargetMode = 'idle'; // 'idle', 'brand', 'benchmarks', 'showcase', 'architecture', 'docs'

  // Resize Handler
  window.addEventListener('resize', () => {
    width = canvas.width = canvas.offsetWidth || window.innerWidth;
    height = canvas.height = canvas.offsetHeight || 650;
    initIdleParticles();
    if (currentTargetMode !== 'idle') {
      morphToShape(currentTargetMode);
    }
  });

  // Expanded Offscreen canvas for high-res pixel sampling (no clipping)
  const offscreen = document.createElement('canvas');
  const offCtx = offscreen.getContext('2d');
  offscreen.width = 650;
  offscreen.height = 240;

  // Particle Class Definition
  class Particle {
    constructor(x, y) {
      this.x = x;
      this.y = y;
      this.baseX = x;
      this.baseY = y;
      this.targetX = x;
      this.targetY = y;
      this.vx = (Math.random() - 0.5) * 0.7;
      this.vy = (Math.random() - 0.5) * 0.7;
      this.radius = Math.random() * 1.5 + 0.9;
      this.alpha = Math.random() * 0.6 + 0.35;
      this.baseAlpha = this.alpha;
      this.ease = Math.random() * 0.07 + 0.06;
    }

    update() {
      if (currentTargetMode === 'idle') {
        this.x += this.vx;
        this.y += this.vy;

        if (this.x < 0 || this.x > width) this.vx *= -1;
        if (this.y < 0 || this.y > height) this.vy *= -1;
      } else {
        this.x += (this.targetX - this.x) * this.ease;
        this.y += (this.targetY - this.y) * this.ease;
      }
    }

    draw() {
      ctx.beginPath();
      ctx.arc(this.x, this.y, this.radius, 0, Math.PI * 2);
      ctx.fillStyle = `rgba(255, 255, 255, ${this.alpha})`;
      if (currentTargetMode !== 'idle') {
        ctx.shadowBlur = 6;
        ctx.shadowColor = 'rgba(255, 255, 255, 0.7)';
      } else {
        ctx.shadowBlur = 0;
      }
      ctx.fill();
    }
  }

  // Initialize Ambient Stardust Oval Ring (Google Antigravity style)
  function initIdleParticles() {
    particles.length = 0;
    const centerX = width / 2;
    const centerY = height / 2.2;
    const radiusX = Math.min(width * 0.44, 520);
    const radiusY = Math.min(height * 0.4, 250);

    for (let i = 0; i < NUM_PARTICLES; i++) {
      const angle = Math.random() * Math.PI * 2;
      const spread = Math.random() * 80 - 40;
      const x = centerX + (radiusX + spread) * Math.cos(angle);
      const y = centerY + (radiusY + spread) * Math.sin(angle);
      particles.push(new Particle(x, y));
    }
  }

  initIdleParticles();

  // Draw & Sample Pixel Targets from Offscreen Canvas
  function sampleShapePoints(drawCallback) {
    offCtx.clearRect(0, 0, offscreen.width, offscreen.height);
    drawCallback(offCtx, offscreen.width, offscreen.height);

    const imgData = offCtx.getImageData(0, 0, offscreen.width, offscreen.height);
    const data = imgData.data;
    const points = [];

    // Step size 2 for dense, ultra-crisp sampling
    const step = 2;
    for (let y = 0; y < offscreen.height; y += step) {
      for (let x = 0; x < offscreen.width; x += step) {
        const index = (y * offscreen.width + x) * 4;
        const a = data[index + 3];
        if (a > 100) {
          points.push({ x, y });
        }
      }
    }
    return points;
  }

  // Generate target coordinates for each hover mode
  function morphToShape(mode) {
    currentTargetMode = mode;
    let sampledPoints = [];

    if (mode === 'brand') {
      // BIT Vector Logo & Name
      sampledPoints = sampleShapePoints((ctx, w, h) => {
        ctx.fillStyle = '#FFFFFF';
        // Rounded Square Icon
        ctx.beginPath();
        ctx.roundRect(20, 40, 110, 110, 24);
        ctx.fill();
        // Inner diamond cutout
        ctx.fillStyle = '#000000';
        ctx.beginPath();
        ctx.moveTo(75, 58);
        ctx.lineTo(102, 95);
        ctx.lineTo(75, 132);
        ctx.lineTo(48, 95);
        ctx.closePath();
        ctx.fill();

        // Text BIT
        ctx.fillStyle = '#FFFFFF';
        ctx.font = '900 86px Inter, sans-serif';
        ctx.fillText('BIT', 160, 120);
        ctx.font = '700 16px ui-monospace, monospace';
        ctx.fillText('OFFLINE AI ENGINE FOR ANDROID', 165, 145);
      });
    } else if (mode === 'benchmarks') {
      // Technical Specs / Benchmarks
      sampledPoints = sampleShapePoints((ctx, w, h) => {
        ctx.fillStyle = '#FFFFFF';
        // Speed bar chart icon
        ctx.fillRect(20, 100, 18, 50);
        ctx.fillRect(48, 65, 18, 85);
        ctx.fillRect(76, 35, 18, 115);
        // Text
        ctx.font = '900 52px Inter, sans-serif';
        ctx.fillText('BENCHMARKS', 120, 95);
        ctx.font = '700 16px ui-monospace, monospace';
        ctx.fillText('38.4 T/S INFERENCE · 8,192 CTX · <10MS TOOL ROUTING', 122, 130);
      });
    } else if (mode === 'showcase') {
      // Model Store
      sampledPoints = sampleShapePoints((ctx, w, h) => {
        ctx.fillStyle = '#FFFFFF';
        // 3D Cube Icon
        ctx.beginPath();
        ctx.roundRect(20, 45, 95, 95, 18);
        ctx.fill();
        ctx.fillStyle = '#000000';
        ctx.fillRect(35, 60, 65, 65);

        ctx.fillStyle = '#FFFFFF';
        ctx.font = '900 52px Inter, sans-serif';
        ctx.fillText('MODEL STORE', 140, 95);
        ctx.font = '700 16px ui-monospace, monospace';
        ctx.fillText('QUANTIZED GGUF WEIGHTS & HF STORE', 142, 130);
      });
    } else if (mode === 'architecture') {
      // Architecture Subsystems
      sampledPoints = sampleShapePoints((ctx, w, h) => {
        ctx.fillStyle = '#FFFFFF';
        // Neural Node graph icon
        ctx.beginPath();
        ctx.arc(35, 55, 14, 0, Math.PI * 2);
        ctx.arc(85, 95, 14, 0, Math.PI * 2);
        ctx.arc(35, 135, 14, 0, Math.PI * 2);
        ctx.fill();
        ctx.lineWidth = 5;
        ctx.strokeStyle = '#FFFFFF';
        ctx.beginPath();
        ctx.moveTo(35, 55); ctx.lineTo(85, 95); ctx.lineTo(35, 135);
        ctx.stroke();

        ctx.font = '900 52px Inter, sans-serif';
        ctx.fillText('ARCHITECTURE', 125, 95);
        ctx.font = '700 16px ui-monospace, monospace';
        ctx.fillText('llama.kt CORE · SHERPA STT · PIPER TTS · GBNF ROUTER', 127, 130);
      });
    } else if (mode === 'docs') {
      // Documentation Manual
      sampledPoints = sampleShapePoints((ctx, w, h) => {
        ctx.fillStyle = '#FFFFFF';
        // Stacked page icon
        ctx.roundRect(25, 35, 75, 105, 12);
        ctx.fill();
        ctx.fillStyle = '#000000';
        ctx.fillRect(40, 60, 45, 8);
        ctx.fillRect(40, 78, 45, 8);
        ctx.fillRect(40, 96, 30, 8);

        ctx.fillStyle = '#FFFFFF';
        ctx.font = '900 52px Inter, sans-serif';
        ctx.fillText('USER MANUAL', 130, 95);
        ctx.font = '700 16px ui-monospace, monospace';
        ctx.fillText('COMPLETE 13-CATEGORY TECHNICAL GUIDE & FAQ', 132, 130);
      });
    }

    if (sampledPoints.length === 0) return;

    // Perfect Scale & Unobscured Center Viewport Positioning
    const scale = Math.min(width / 680, 1.45);
    const offsetX = (width - 650 * scale) / 2;
    const offsetY = (height - 240 * scale) / 3.2;

    for (let i = 0; i < particles.length; i++) {
      const p = particles[i];
      const pt = sampledPoints[i % sampledPoints.length];
      p.targetX = offsetX + pt.x * scale + (Math.random() - 0.5) * 1.5;
      p.targetY = offsetY + pt.y * scale + (Math.random() - 0.5) * 1.5;
      p.alpha = Math.random() * 0.35 + 0.65;
    }
  }

  // Reset particles to idle stardust
  function resetToIdle() {
    currentTargetMode = 'idle';
    for (let i = 0; i < particles.length; i++) {
      const p = particles[i];
      p.targetX = p.baseX;
      p.targetY = p.baseY;
      p.alpha = p.baseAlpha;
    }
  }

  // Animation Loop
  function render() {
    ctx.clearRect(0, 0, width, height);

    // Draw connecting lines in idle mode
    if (currentTargetMode === 'idle') {
      ctx.strokeStyle = 'rgba(255, 255, 255, 0.05)';
      ctx.lineWidth = 0.8;
      for (let i = 0; i < particles.length; i += 8) {
        for (let j = i + 1; j < particles.length; j += 16) {
          const dx = particles[i].x - particles[j].x;
          const dy = particles[i].y - particles[j].y;
          const dist = Math.sqrt(dx * dx + dy * dy);
          if (dist < 70) {
            ctx.beginPath();
            ctx.moveTo(particles[i].x, particles[i].y);
            ctx.lineTo(particles[j].x, particles[j].y);
            ctx.stroke();
          }
        }
      }
    }

    // Render all particles
    for (let i = 0; i < particles.length; i++) {
      particles[i].update();
      particles[i].draw();
    }

    requestAnimationFrame(render);
  }

  render();

  // ── Attach Hover Event Listeners to Nav Brand & Menu Links ──
  const navBrand = document.querySelector('.nav-brand');
  if (navBrand) {
    navBrand.addEventListener('mouseenter', () => morphToShape('brand'));
    navBrand.addEventListener('mouseleave', () => resetToIdle());
  }

  const menuMappings = [
    { selector: 'a[href="#benchmarks"]', mode: 'benchmarks' },
    { selector: 'a[href="#showcase"]', mode: 'showcase' },
    { selector: 'a[href="#architecture"]', mode: 'architecture' },
    { selector: 'a[href="docs.html"]', mode: 'docs' },
    { selector: 'a[href*="docs"]', mode: 'docs' }
  ];

  menuMappings.forEach(item => {
    const els = document.querySelectorAll(item.selector);
    els.forEach(el => {
      el.addEventListener('mouseenter', () => morphToShape(item.mode));
      el.addEventListener('mouseleave', () => resetToIdle());
    });
  });
}

/* ── Live GitHub API Integration ── */
async function initGitHubApi() {
  const repoOwner = 'jaswanthsanjay88';
  const repoName = 'Bit_Android';

  const starCountEl = document.getElementById('githubStarCount');
  const versionTagEl = document.getElementById('githubVersionTag');
  const downloadSizeEl = document.getElementById('githubDownloadSize');

  try {
    const repoRes = await fetch(`https://api.github.com/repos/${repoOwner}/${repoName}`);
    if (repoRes.ok) {
      const repoData = await repoRes.json();
      if (starCountEl && repoData.stargazers_count !== undefined) {
        starCountEl.textContent = `★ ${repoData.stargazers_count.toLocaleString()}`;
      }
    }
  } catch (err) {
    console.log('GitHub repo info fetch fallback:', err);
  }

  try {
    const releaseRes = await fetch(`https://api.github.com/repos/${repoOwner}/${repoName}/releases/latest`);
    if (releaseRes.ok) {
      const releaseData = await releaseRes.json();
      if (versionTagEl && releaseData.tag_name) {
        versionTagEl.textContent = `${releaseData.tag_name}`;
      }
      if (downloadSizeEl && releaseData.assets && releaseData.assets.length > 0) {
        const universalAsset = releaseData.assets.find(a => a.name.includes('universal')) || releaseData.assets[0];
        const sizeMb = (universalAsset.size / (1024 * 1024)).toFixed(1);
        downloadSizeEl.textContent = `Latest Release (${sizeMb} MB APK)`;
      }
    }
  } catch (err) {
    console.log('GitHub release fetch fallback:', err);
  }
}

/* ── Intersection Observer Scroll Reveal ── */
function initIntersectionObserver() {
  const elements = document.querySelectorAll('.stat-card, .showcase-frame-container, .feature-item, .cta-stage');

  const observer = new IntersectionObserver((entries) => {
    entries.forEach(entry => {
      if (entry.isIntersecting) {
        entry.target.style.opacity = '1';
        entry.target.style.transform = 'translateY(0)';
        observer.unobserve(entry.target);
      }
    });
  }, {
    threshold: 0.15
  });

  elements.forEach(el => {
    el.style.opacity = '0';
    el.style.transform = 'translateY(24px)';
    el.style.transition = 'opacity 0.6s cubic-bezier(0.16, 1, 0.3, 1), transform 0.6s cubic-bezier(0.16, 1, 0.3, 1)';
    observer.observe(el);
  });
}

