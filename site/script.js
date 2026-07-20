/* ==========================================================================
   BIT — Landing Page Client-side Script
   ========================================================================== */

document.addEventListener('DOMContentLoaded', () => {
  initNavbarScroll();
  initMouseParallax();
  initGitHubApi();
  initIntersectionObserver();
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
