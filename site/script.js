/* ==========================================================================
   BIT — Landing Page Client-side Script
   ========================================================================== */

document.addEventListener('DOMContentLoaded', () => {
  initAnnouncementBanner();
  initPrivacyToast();
  initNavbarScroll();
  initMouseParallax();
  initGitHubApi();
  initIntersectionObserver();
  initLiveRatings();
  initModelStoreCatalog();
  
  // Auto-open tester form if linked directly
  if (window.location.hash === '#apply') {
    openTesterModal();
  }
});

/* ── Sticky Navbar Blur ── */
function initNavbarScroll() {
  const headerStack = document.getElementById('headerStack');
  const navbar = document.getElementById('navbar');

  const handleScroll = () => {
    const isScrolled = window.scrollY > 30;
    if (headerStack) headerStack.classList.toggle('scrolled', isScrolled);
    if (navbar) navbar.classList.toggle('scrolled', isScrolled);
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

  // Respect reduced motion preference
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

/* ── Live GitHub API Integration ── */
async function initGitHubApi() {
  const repoOwner = 'jaswanthsanjay88';
  const repoName = 'Bit_Android';

  const starCountEl = document.getElementById('githubStarCount');
  const versionTagEl = document.getElementById('githubVersionTag');
  const downloadSizeEl = document.getElementById('githubDownloadSize');

  try {
    // Fetch Repo Metadata (Stars)
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
    // Fetch Latest Release Info
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

/* ── Live Ratings API Fetch & Marquee Population ── */
async function initLiveRatings() {
  const track1 = document.getElementById('marqueeTrack1');
  const track2 = document.getElementById('marqueeTrack2');
  const track3 = document.getElementById('marqueeTrack3');
  if (!track1) return;

  let reviews = [];

  try {
    const res = await fetch('https://api.jaswanthsanjay.me/api/rating');
    if (res.ok) {
      const data = await res.json();
      if (data.success && data.ratings && data.ratings.length > 0) {
        reviews = data.ratings;
      }
    }
  } catch (err) {
    console.log('Using default reviews fallback:', err);
  }

  // Fallback reviews matching the app's features
  if (!reviews || reviews.length === 0) {
    reviews = [
      {
        name: "Alex R.",
        role: "Entrepreneur",
        rating: 5,
        comment: "This on-device stack is a game-changer for my workflow. Zero latency, 100% offline privacy."
      },
      {
        name: "Sarah W.",
        role: "AI Developer",
        rating: 5,
        comment: "GBNF grammar-constrained JSON tool calling directly on Android silicon is unbelievable."
      },
      {
        name: "Michael B.",
        role: "Startup Founder",
        rating: 5,
        comment: "The performance on Snapdragon chips is top tier. Running GGUF models locally with zero cloud dependencies."
      },
      {
        name: "Jessica L.",
        role: "Mobile Architect",
        rating: 5,
        comment: "Whisper STT and Piper TTS running offline in background threads. Incredible work!"
      },
      {
        name: "Chloe K.",
        role: "Product Manager",
        rating: 5,
        comment: "The most privacy-conscious mobile AI application I have used."
      },
      {
        name: "David P.",
        role: "Software Engineer",
        rating: 5,
        comment: "Clean Kotlin + Compose UI paired with native C++ llama.cpp bindings."
      }
    ];
  }

  function renderCard(item) {
    const seed = encodeURIComponent(item.name || 'User');
    const avatarUrl = item.avatar || `https://api.dicebear.com/10.x/notionists/svg?seed=${seed}`;
    const stars = '★'.repeat(item.rating || 5) + '☆'.repeat(5 - (item.rating || 5));

    return `
      <div class="review-card">
        <div class="review-header">
          <img src="${avatarUrl}" alt="${item.name}" class="review-avatar" />
          <div>
            <div class="review-author">${item.name}</div>
            <div class="review-role">${item.role || 'BIT User'}</div>
          </div>
        </div>
        <div class="review-stars">${stars}</div>
        <div class="review-comment">"${item.comment || item.review || item.feedback}"</div>
      </div>
    `;
  }

  const col1Items = [];
  const col2Items = [];
  const col3Items = [];

  reviews.forEach((rev, idx) => {
    if (idx % 3 === 0) col1Items.push(rev);
    else if (idx % 3 === 1) col2Items.push(rev);
    else col3Items.push(rev);
  });

  const buildColumnHtml = (items) => {
    const list = [...items, ...items, ...items];
    return `<div class="flex-col-gap">${list.map(renderCard).join('')}</div>
            <div class="flex-col-gap">${list.map(renderCard).join('')}</div>`;
  };

  if (track1) track1.innerHTML = buildColumnHtml(col1Items.length ? col1Items : reviews);
  if (track2) track2.innerHTML = buildColumnHtml(col2Items.length ? col2Items : reviews);
  if (track3) track3.innerHTML = buildColumnHtml(col3Items.length ? col3Items : reviews);
}

/* ── Curated Model Catalog Fetch & Populate ── */
async function initModelStoreCatalog() {
  const modelsGrid = document.getElementById('modelsGrid');
  if (!modelsGrid) return;

  try {
    let models = [];
    const res = await fetch('./api/models.json');
    if (res.ok) {
      const data = await res.json();
      models = data.models || [];
    } else {
      const fallbackRes = await fetch('/api/models');
      if (fallbackRes.ok) {
        const data = await fallbackRes.json();
        models = data.models || data;
      }
    }

    if (!models || models.length === 0) return;

    modelsGrid.innerHTML = models.map(model => {
      const typeLower = (model.type || 'gguf').toLowerCase();
      const badgeClass = `badge-${typeLower}`;
      const iconUrl = model.iconUrl || (model.icon ? `https://raw.githubusercontent.com/lobehub/lobe-icons/main/packages/static-png/light/${model.icon}.png` : '');
      const iconHtml = iconUrl ? `<img src="${iconUrl}" alt="${model.name}" class="model-brand-icon" />` : `<div class="model-brand-icon"></div>`;

      const ramHtml = model.minRamGb ? `<span class="meta-chip meta-chip-ram">${model.minRamGb} GB RAM</span>` : '';
      const tagsHtml = (model.tags || []).slice(0, 2).map(t => `<span class="meta-chip">${t}</span>`).join('');

      return `
        <div class="model-card-site">
          <div>
            <div class="model-header">
              ${iconHtml}
              <div class="model-title-box">
                <div class="model-name">${model.name}</div>
              </div>
              <span class="model-type-badge ${badgeClass}">${model.type}</span>
            </div>
            <div class="model-desc">${model.description}</div>
            <div class="model-meta-row">
              ${ramHtml}
              ${tagsHtml}
            </div>
          </div>
          <div class="model-card-footer">
            <span class="model-size">${model.size}</span>
            <a href="${model.url}" target="_blank" rel="noopener noreferrer" class="btn-download-sm">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>
              Download
            </a>
          </div>
        </div>
      `;
    }).join('');

  } catch (err) {
    console.log('Model Catalog fetch error:', err);
  }
}

/* ── Announcement Banner & Responsive Header Offset ── */
function initAnnouncementBanner() {
  const headerStack = document.getElementById('headerStack');
  const banner = document.getElementById('announcementBanner');

  function updateBodyPadding() {
    if (headerStack) {
      const h = headerStack.offsetHeight || 0;
      document.body.style.paddingTop = h + 'px';
    }
  }

  if (banner && sessionStorage.getItem('bit_announcement_dismissed') === 'true') {
    banner.style.display = 'none';
    updateBodyPadding();
    return;
  }

  updateBodyPadding();

  if (headerStack && window.ResizeObserver) {
    const observer = new ResizeObserver(() => updateBodyPadding());
    observer.observe(headerStack);
  } else {
    window.addEventListener('resize', updateBodyPadding, { passive: true });
  }

  window.dismissAnnouncementBanner = function() {
    if (banner) banner.style.display = 'none';
    sessionStorage.setItem('bit_announcement_dismissed', 'true');
    updateBodyPadding();
  };
}

/* ── Privacy Toast Persistence & Dismissal ── */
function initPrivacyToast() {
  const toast = document.getElementById('privacyToast');
  if (!toast) return;

  if (localStorage.getItem('bit_privacy_toast_dismissed_v20260806') === 'true') {
    toast.style.display = 'none';
    return;
  }

  window.dismissPrivacyToast = function() {
    toast.classList.add('toast-dismissed');
    localStorage.setItem('bit_privacy_toast_dismissed_v20260806', 'true');
    setTimeout(() => {
      toast.style.display = 'none';
    }, 400);
  };
}

/* ── Helper to Open QA Tester Modal ── */
window.openTesterModal = function() {
  const testerModal = document.getElementById('testerModal');
  const testerForm = document.getElementById('testerForm');
  const successMsg = document.getElementById('successMsg');
  if (testerModal) {
    if (successMsg) successMsg.style.display = 'none';
    if (testerForm) {
      testerForm.style.display = 'flex';
      testerForm.reset();
    }
    testerModal.showModal();
  }
};

