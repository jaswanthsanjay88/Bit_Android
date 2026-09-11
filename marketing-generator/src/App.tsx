import React, { useState, useRef } from 'react';
import { toCanvas } from 'html-to-image';

interface ScreenData {
  id: number;
  tag: string;
  italicHeadline: string;
  regularHeadline: string;
  sub: string;
  statusPill: string;
  img: string;
}

const INITIAL_SCREENS: ScreenData[] = [
  {
    id: 1,
    tag: 'LOCAL REASONING',
    italicHeadline: 'on your silicon.',
    regularHeadline: 'An autonomous mind,',
    sub: 'Run production Llama, Qwen, and Gemma models natively on Android hardware. Zero cloud servers, zero telemetry.',
    statusPill: '100% OFFLINE',
    img: '/captures/screen_1.png'
  },
  {
    id: 2,
    tag: 'MULTI-STEP THINKING',
    italicHeadline: 'executed in real time.',
    regularHeadline: 'Deep reasoning steps,',
    sub: 'Native web search, file processing, and autonomous multi-turn tool orchestration.',
    statusPill: 'ZERO LATENCY',
    img: '/captures/screen_2.png'
  },
  {
    id: 3,
    tag: 'ON-DEVICE MODELS',
    italicHeadline: 'commanded on device.',
    regularHeadline: 'Quantized local weights,',
    sub: 'Download and run quantized GGUF weights directly on local flash storage.',
    statusPill: 'GGUF VAULT',
    img: '/captures/screen_3.png'
  },
  {
    id: 4,
    tag: 'AGENT TOOLS & MCP',
    italicHeadline: 'extensible via MCP.',
    regularHeadline: 'Local intelligence,',
    sub: 'Connect Model Context Protocol tools, agent skills, and Linux sandboxes on-device.',
    statusPill: 'MCP RUNTIME',
    img: '/captures/screen_4.png'
  }
];

export default function App() {
  const [device, setDevice] = useState<'play' | 'appstore'>('play');
  const [screens, setScreens] = useState<ScreenData[]>(INITIAL_SCREENS);
  const [capturingId, setCapturingId] = useState<number | null>(null);
  const [exporting, setExporting] = useState<boolean>(false);
  const [statusMessage, setStatusMessage] = useState<string>('');
  const cardRefs = useRef<(HTMLDivElement | null)[]>([]);

  // Layout & Display Controls
  const [layoutMode, setLayoutMode] = useState<'full' | 'bleed'>('full');
  const [removeStatusBar, setRemoveStatusBar] = useState<boolean>(true);
  const [statusBarCrop, setStatusBarCrop] = useState<number>(4.3); // 4.3% matches 117px / 2720px
  const [removeNavBar, setRemoveNavBar] = useState<boolean>(true);
  const [navBarCrop, setNavBarCrop] = useState<number>(1.7); // 1.7% matches 47px / 2720px
  const [phoneScale, setPhoneScale] = useState<number>(1.0);
  const [showControls, setShowControls] = useState<boolean>(false);

  // Dimensions
  // Play: 1080 x 1920 (exact ratio 0.5625 = 9:16)
  // AppStore: 1290 x 2796 (ratio 0.4614)
  const isPlay = device === 'play';
  const targetW = isPlay ? 1080 : 1290;
  const targetH = isPlay ? 1920 : 2796;
  const previewW = isPlay ? 360 : 344;
  const previewH = isPlay ? 640 : 746;
  const exportScale = isPlay ? 3.0 : 3.75;

  // Phone Screen Dimensions
  const topCrop = removeStatusBar ? statusBarCrop / 100 : 0;
  const bottomCrop = removeNavBar ? navBarCrop / 100 : 0;
  const visibleHeightFrac = Math.max(0.1, 1 - topCrop - bottomCrop);
  // Screen aspect ratio for clean 1224x(2720 * visibleHeightFrac)
  const screenAspect = 1224 / (2720 * visibleHeightFrac);

  // Chassis Dimensions based on layout mode
  const isFull = layoutMode === 'full';
  // Screen width inside phone chassis
  const phoneScreenWidth = isFull
    ? Math.round(256 * phoneScale)
    : Math.round(284 * phoneScale);
  const phoneScreenHeight = Math.round(phoneScreenWidth / screenAspect);

  const phoneChassisWidth = phoneScreenWidth + 14;
  const phoneChassisHeight = isFull ? phoneScreenHeight + 16 : phoneScreenHeight + 8;

  const handleSnapAdb = async (screenId: number) => {
    setCapturingId(screenId);
    setStatusMessage(`Snapping live screen ${screenId} from connected Android phone...`);
    try {
      const res = await fetch(`/api/adb-screencap?id=${screenId}`);
      const data = await res.json();
      if (data.success && data.url) {
        setScreens((prev) =>
          prev.map((s) => (s.id === screenId ? { ...s, img: data.url } : s))
        );
        setStatusMessage(`Captured screen ${screenId} successfully from ADB.`);
      } else {
        setStatusMessage(`ADB capture failed: ${data.error || 'Unknown error'}`);
      }
    } catch (err: any) {
      setStatusMessage(`ADB capture error: ${err.message}`);
    } finally {
      setCapturingId(null);
      setTimeout(() => setStatusMessage(''), 4000);
    }
  };

  const handleFileUpload = (screenId: number, e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    const reader = new FileReader();
    reader.onload = () => {
      setScreens((prev) =>
        prev.map((s) => (s.id === screenId ? { ...s, img: reader.result as string } : s))
      );
    };
    reader.readAsDataURL(file);
  };

  const exportSinglePng = async (idx: number) => {
    const node = cardRefs.current[idx];
    if (!node) return;
    setExporting(true);
    setStatusMessage(`Rendering exact ${targetW}×${targetH} PNG for Screen ${idx + 1}...`);
    try {
      // Capture element at exact pixel ratio with local web fonts or fallback
      let sourceCanvas: HTMLCanvasElement;
      try {
        sourceCanvas = await toCanvas(node, {
          pixelRatio: exportScale,
          cacheBust: true,
        });
      } catch (fontErr) {
        console.warn('Embedding fallback font', fontErr);
        sourceCanvas = await toCanvas(node, {
          pixelRatio: exportScale,
          cacheBust: true,
          skipFonts: true,
        });
      }

      // Composite onto target canvas to guarantee exact 1080x1920 (no alpha, no extra borders)
      const outCanvas = document.createElement('canvas');
      outCanvas.width = targetW;
      outCanvas.height = targetH;
      const ctx = outCanvas.getContext('2d');
      if (ctx) {
        ctx.fillStyle = '#06080A';
        ctx.fillRect(0, 0, targetW, targetH);
        ctx.imageSmoothingEnabled = true;
        ctx.imageSmoothingQuality = 'high';
        ctx.drawImage(sourceCanvas, 0, 0, targetW, targetH);
      }

      const dataUrl = outCanvas.toDataURL('image/png');
      const a = document.createElement('a');
      a.href = dataUrl;
      a.download = `bit_${device}_screenshot_${idx + 1}_${targetW}x${targetH}.png`;
      a.click();
      setStatusMessage(`Saved Screen ${idx + 1} (${targetW}×${targetH}) successfully.`);
    } catch (err: any) {
      console.error('Export error', err);
      setStatusMessage(`Export error: ${err.message}`);
    } finally {
      setExporting(false);
      setTimeout(() => setStatusMessage(''), 3000);
    }
  };

  const exportAllPngs = async () => {
    setExporting(true);
    setStatusMessage(`Batch exporting all 4 screens at exact ${targetW}×${targetH}...`);
    for (let i = 0; i < screens.length; i++) {
      const node = cardRefs.current[i];
      if (!node) continue;
      try {
        let sourceCanvas: HTMLCanvasElement;
        try {
          sourceCanvas = await toCanvas(node, {
            pixelRatio: exportScale,
            cacheBust: true,
          });
        } catch (fontErr) {
          console.warn('Embedding fallback font', fontErr);
          sourceCanvas = await toCanvas(node, {
            pixelRatio: exportScale,
            cacheBust: true,
            skipFonts: true,
          });
        }

        const outCanvas = document.createElement('canvas');
        outCanvas.width = targetW;
        outCanvas.height = targetH;
        const ctx = outCanvas.getContext('2d');
        if (ctx) {
          ctx.fillStyle = '#06080A';
          ctx.fillRect(0, 0, targetW, targetH);
          ctx.imageSmoothingEnabled = true;
          ctx.imageSmoothingQuality = 'high';
          ctx.drawImage(sourceCanvas, 0, 0, targetW, targetH);
        }

        const dataUrl = outCanvas.toDataURL('image/png');
        const a = document.createElement('a');
        a.href = dataUrl;
        a.download = `bit_${device}_screenshot_${i + 1}_${targetW}x${targetH}.png`;
        a.click();
        await new Promise((r) => setTimeout(r, 450));
      } catch (err: any) {
        console.error('Export failed for index', i, err);
      }
    }
    setExporting(false);
    setStatusMessage(`All 4 marketing screenshots exported at exact ${targetW}×${targetH}!`);
    setTimeout(() => setStatusMessage(''), 3500);
  };

  return (
    <div style={{ background: '#030406', color: '#E2E6EA', minHeight: '100vh', padding: '28px 24px 60px 24px' }}>
      {/* Top Header & Studio Navigation */}
      <header
        style={{
          maxWidth: '1680px',
          margin: '0 auto 28px auto',
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          borderBottom: '1px solid #14191F',
          paddingBottom: '18px',
          flexWrap: 'wrap',
          gap: '16px'
        }}
      >
        <div>
          <div style={{ display: 'flex', alignItems: 'center', gap: '10px', marginBottom: '4px' }}>
            <span style={{ fontFamily: 'var(--serif)', fontStyle: 'italic', fontSize: '1.45rem', color: '#F4EBDD' }}>
              Moody Curated AI
            </span>
            <span
              style={{
                fontSize: '0.72rem',
                letterSpacing: '0.12em',
                color: '#E8B97A',
                border: '1px solid rgba(232,185,122,0.3)',
                padding: '2px 8px',
                borderRadius: '999px',
                fontFamily: 'var(--mono)'
              }}
            >
              {isFull ? 'FULL SCREEN FIT' : 'BOTTOM BLEED'}
            </span>
          </div>
          <p style={{ color: '#8E959D', fontSize: '0.82rem', margin: 0 }}>
            Curated on-device AI editorial showcase. Full app screen UI visible with status bar cleanly removed.
          </p>
        </div>

        <div style={{ display: 'flex', gap: '10px', alignItems: 'center', flexWrap: 'wrap' }}>
          {statusMessage && (
            <div style={{ fontSize: '0.8rem', color: '#E8B97A', fontFamily: 'var(--mono)', marginRight: '6px' }}>
              {statusMessage}
            </div>
          )}

          {/* Layout Mode Toggle */}
          <div style={{ display: 'flex', background: '#090C10', padding: '3px', borderRadius: '24px', border: '1px solid #1A2026' }}>
            <button
              onClick={() => setLayoutMode('full')}
              style={{
                background: isFull ? 'rgba(232, 185, 122, 0.12)' : 'transparent',
                color: isFull ? '#F4EBDD' : '#8E959D',
                border: isFull ? '1px solid rgba(232, 185, 122, 0.4)' : '1px solid transparent',
                padding: '7px 14px',
                borderRadius: '20px',
                cursor: 'pointer',
                fontSize: '0.76rem',
                fontFamily: 'var(--mono)'
              }}
            >
              Full Screen UI
            </button>
            <button
              onClick={() => setLayoutMode('bleed')}
              style={{
                background: !isFull ? 'rgba(232, 185, 122, 0.12)' : 'transparent',
                color: !isFull ? '#F4EBDD' : '#8E959D',
                border: !isFull ? '1px solid rgba(232, 185, 122, 0.4)' : '1px solid transparent',
                padding: '7px 14px',
                borderRadius: '20px',
                cursor: 'pointer',
                fontSize: '0.76rem',
                fontFamily: 'var(--mono)'
              }}
            >
              Bottom Bleed
            </button>
          </div>

          {/* Platform Switcher */}
          <div style={{ display: 'flex', background: '#090C10', padding: '3px', borderRadius: '24px', border: '1px solid #1A2026' }}>
            <button
              onClick={() => setDevice('play')}
              style={{
                background: isPlay ? 'rgba(255,255,255,0.08)' : 'transparent',
                color: isPlay ? '#F4EBDD' : '#8E959D',
                border: isPlay ? '1px solid rgba(244,235,221,0.25)' : '1px solid transparent',
                padding: '7px 14px',
                borderRadius: '20px',
                cursor: 'pointer',
                fontSize: '0.76rem',
                fontFamily: 'var(--mono)'
              }}
            >
              Google Play (1080×1920)
            </button>
            <button
              onClick={() => setDevice('appstore')}
              style={{
                background: !isPlay ? 'rgba(255,255,255,0.08)' : 'transparent',
                color: !isPlay ? '#F4EBDD' : '#8E959D',
                border: !isPlay ? '1px solid rgba(244,235,221,0.25)' : '1px solid transparent',
                padding: '7px 14px',
                borderRadius: '20px',
                cursor: 'pointer',
                fontSize: '0.76rem',
                fontFamily: 'var(--mono)'
              }}
            >
              App Store (1290×2796)
            </button>
          </div>

          {/* Crop & Scale Tuning Toggle Button */}
          <button
            onClick={() => setShowControls(!showControls)}
            style={{
              background: showControls ? 'rgba(232, 185, 122, 0.15)' : 'rgba(26, 32, 38, 0.6)',
              border: showControls ? '1px solid #E8B97A' : '1px solid rgba(255, 255, 255, 0.1)',
              color: '#F4EBDD',
              padding: '7px 14px',
              borderRadius: '20px',
              cursor: 'pointer',
              fontSize: '0.76rem',
              fontFamily: 'var(--mono)',
              display: 'flex',
              alignItems: 'center',
              gap: '6px'
            }}
          >
            ⚙ Status Bar & Fit {showControls ? '▲' : '▼'}
          </button>

          {/* Export All Button */}
          <button
            onClick={exportAllPngs}
            disabled={exporting}
            style={{
              background: 'rgba(232, 185, 122, 0.12)',
              border: '1px solid rgba(232, 185, 122, 0.55)',
              color: '#F4EBDD',
              padding: '8px 20px',
              borderRadius: '24px',
              cursor: exporting ? 'wait' : 'pointer',
              fontWeight: 500,
              fontSize: '0.8rem',
              letterSpacing: '0.04em'
            }}
          >
            {exporting ? 'Rendering...' : `Export All 4 (${isPlay ? '1080×1920' : '1290×2796'})`}
          </button>
        </div>
      </header>

      {/* Expandable Status Bar & Fine Tuning Drawer */}
      {showControls && (
        <div
          style={{
            maxWidth: '1680px',
            margin: '-14px auto 24px auto',
            background: '#090C10',
            border: '1px solid #1A2026',
            borderRadius: '16px',
            padding: '14px 20px',
            display: 'flex',
            flexWrap: 'wrap',
            alignItems: 'center',
            gap: '24px',
            fontSize: '0.78rem',
            fontFamily: 'var(--mono)',
            color: '#B0B8C0'
          }}
        >
          {/* Status Bar Removal Checkbox */}
          <label style={{ display: 'flex', alignItems: 'center', gap: '8px', cursor: 'pointer' }}>
            <input
              type="checkbox"
              checked={removeStatusBar}
              onChange={(e) => setRemoveStatusBar(e.target.checked)}
              style={{ accentColor: '#E8B97A' }}
            />
            <span>Remove Top Status Bar</span>
          </label>

          {/* Top Status Bar Crop Slider */}
          {removeStatusBar && (
            <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
              <span>Top Crop: {statusBarCrop.toFixed(1)}%</span>
              <input
                type="range"
                min="0"
                max="10"
                step="0.1"
                value={statusBarCrop}
                onChange={(e) => setStatusBarCrop(parseFloat(e.target.value))}
                style={{ width: '110px', accentColor: '#E8B97A' }}
              />
            </div>
          )}

          {/* Bottom Bar Removal Checkbox */}
          <label style={{ display: 'flex', alignItems: 'center', gap: '8px', cursor: 'pointer' }}>
            <input
              type="checkbox"
              checked={removeNavBar}
              onChange={(e) => setRemoveNavBar(e.target.checked)}
              style={{ accentColor: '#E8B97A' }}
            />
            <span>Hide Bottom Gesture Pill</span>
          </label>

          {/* Bottom Crop Slider */}
          {removeNavBar && (
            <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
              <span>Bottom Crop: {navBarCrop.toFixed(1)}%</span>
              <input
                type="range"
                min="0"
                max="5"
                step="0.1"
                value={navBarCrop}
                onChange={(e) => setNavBarCrop(parseFloat(e.target.value))}
                style={{ width: '90px', accentColor: '#E8B97A' }}
              />
            </div>
          )}

          {/* Phone Scale Slider */}
          <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
            <span>Phone Scale: {Math.round(phoneScale * 100)}%</span>
            <input
              type="range"
              min="0.8"
              max="1.2"
              step="0.02"
              value={phoneScale}
              onChange={(e) => setPhoneScale(parseFloat(e.target.value))}
              style={{ width: '100px', accentColor: '#E8B97A' }}
            />
          </div>

          <button
            onClick={() => {
              setRemoveStatusBar(true);
              setStatusBarCrop(4.3);
              setRemoveNavBar(true);
              setNavBarCrop(1.7);
              setPhoneScale(1.0);
            }}
            style={{
              background: 'transparent',
              border: '1px solid rgba(255, 255, 255, 0.15)',
              color: '#8E959D',
              borderRadius: '12px',
              padding: '4px 10px',
              cursor: 'pointer',
              fontSize: '0.72rem'
            }}
          >
            Reset Defaults
          </button>
        </div>
      )}

      {/* Main Showcase Cards Row */}
      <main
        style={{
          maxWidth: '1680px',
          margin: '0 auto',
          display: 'flex',
          gap: '28px',
          overflowX: 'auto',
          paddingBottom: '30px'
        }}
      >
        {screens.map((s, idx) => (
          <div key={s.id} style={{ display: 'flex', flexDirection: 'column', gap: '14px', flexShrink: 0 }}>
            {/* The Actual Marketing Canvas (Card) */}
            <div
              ref={(el) => {
                cardRefs.current[idx] = el;
              }}
              style={{
                width: `${previewW}px`,
                height: `${previewH}px`,
                position: 'relative',
                overflow: 'hidden',
                borderRadius: '26px',
                border: '1px solid #1A2026',
                background: '#06080A',
                boxShadow: '0 25px 65px rgba(0,0,0,0.85), 0 0 40px rgba(26,32,38,0.35)',
                display: 'flex',
                flexDirection: 'column'
              }}
            >
              {/* Layer 1: Cool Teal Shadow Tint (#1A2026) */}
              <div
                style={{
                  position: 'absolute',
                  inset: 0,
                  background: 'radial-gradient(ellipse 110% 80% at 50% 12%, #1A2026 0%, #0D1115 52%, #06080A 100%)',
                  zIndex: 1
                }}
              />

              {/* Layer 2: Radial Vignette */}
              <div
                style={{
                  position: 'absolute',
                  inset: 0,
                  background: 'radial-gradient(circle at 50% 42%, transparent 42%, rgba(0, 0, 0, 0.72) 100%)',
                  zIndex: 2
                }}
              />

              {/* Layer 3: 5% SVG Film Grain */}
              <svg
                style={{
                  position: 'absolute',
                  inset: 0,
                  width: '100%',
                  height: '100%',
                  opacity: 0.05,
                  mixBlendMode: 'overlay',
                  pointerEvents: 'none',
                  zIndex: 3
                }}
              >
                <filter id={`noise-${s.id}`}>
                  <feTurbulence type="fractalNoise" baseFrequency="0.8" numOctaves="3" stitchTiles="stitch" />
                </filter>
                <rect width="100%" height="100%" filter={`url(#noise-${s.id})`} />
              </svg>

              {/* TOP HERO HEADER (Centered Headline & Eyebrow like Reference Image) */}
              <div
                style={{
                  position: 'relative',
                  zIndex: 10,
                  padding: '22px 20px 10px 20px',
                  display: 'flex',
                  flexDirection: 'column',
                  alignItems: 'center',
                  textAlign: 'center',
                  flexShrink: 0
                }}
              >
                {/* Eyebrow flanked by subtle editorial star & plus glyphs */}
                <div
                  style={{
                    width: '100%',
                    display: 'flex',
                    justifyContent: 'space-between',
                    alignItems: 'center',
                    marginBottom: '8px'
                  }}
                >
                  <span style={{ fontSize: '0.85rem', color: '#E8B97A', opacity: 0.75 }}>☆</span>
                  <div
                    style={{
                      display: 'inline-flex',
                      alignItems: 'center',
                      fontSize: '0.66rem',
                      fontFamily: 'var(--mono)',
                      letterSpacing: '0.2em',
                      color: '#E8B97A'
                    }}
                  >
                    {s.tag}
                  </div>
                  <span style={{ fontSize: '0.85rem', color: '#E8B97A', opacity: 0.75 }}>+</span>
                </div>

                {/* Headline: Single flow heading with br to guarantee zero overlap */}
                <h2
                  style={{
                    fontFamily: 'var(--serif)',
                    fontWeight: 300,
                    fontSize: isPlay ? '1.34rem' : '1.48rem',
                    lineHeight: 1.3,
                    color: '#F4EBDD',
                    letterSpacing: '-0.015em',
                    textAlign: 'center',
                    margin: '0 auto',
                    maxWidth: '96%'
                  }}
                >
                  <span style={{ display: 'inline' }}>{s.regularHeadline}</span>
                  <br />
                  <span
                    style={{
                      fontStyle: 'italic',
                      fontWeight: 300,
                      borderBottom: '1px solid rgba(232, 185, 122, 0.45)',
                      paddingBottom: '2px',
                      display: 'inline-block',
                      marginTop: '3px'
                    }}
                  >
                    {s.italicHeadline}
                  </span>
                </h2>
              </div>

              {/* PHONE MOCKUP (Centered, showing full screen UI with status bar cleanly removed) */}
              <div
                style={{
                  position: 'relative',
                  zIndex: 10,
                  flex: 1,
                  display: 'flex',
                  justifyContent: 'center',
                  alignItems: isFull ? 'center' : 'flex-start',
                  marginTop: isFull ? '4px' : '8px',
                  overflow: 'hidden'
                }}
              >
                {/* Phone Outer Chassis */}
                <div
                  style={{
                    width: `${phoneChassisWidth}px`,
                    height: `${phoneChassisHeight}px`,
                    background: '#12161D',
                    borderRadius: isFull ? '34px' : '34px 34px 0 0',
                    border: '4px solid #1C232B',
                    borderBottom: isFull ? '4px solid #1C232B' : 'none',
                    boxShadow:
                      '0 20px 50px rgba(0,0,0,0.9), 0 0 35px rgba(26,32,38,0.5), inset 0 1px 1px rgba(255,255,255,0.18)',
                    display: 'flex',
                    flexDirection: 'column',
                    alignItems: 'center',
                    justifyContent: 'center',
                    padding: '6px',
                    boxSizing: 'border-box',
                    position: 'relative'
                  }}
                >
                  {/* Phone Screen Container with status bar crop */}
                  <div
                    style={{
                      width: `${phoneScreenWidth}px`,
                      height: `${phoneScreenHeight}px`,
                      borderRadius: isFull ? '26px' : '26px 26px 0 0',
                      overflow: 'hidden',
                      position: 'relative',
                      background: '#0E0E10'
                    }}
                  >
                    {/* The Full Screenshot with Status Bar and Nav Bar cleanly cropped out */}
                    <img
                      src={s.img}
                      alt={s.regularHeadline}
                      style={{
                        position: 'absolute',
                        left: 0,
                        width: '100%',
                        top: `-${(topCrop / visibleHeightFrac) * 100}%`,
                        height: `${(1 / visibleHeightFrac) * 100}%`,
                        objectFit: 'fill',
                        display: 'block'
                      }}
                    />
                  </div>
                </div>
              </div>
            </div>

            {/* Editor & Control Strip for this Card */}
            <div style={{ display: 'flex', flexDirection: 'column', gap: '8px', padding: '0 4px', width: `${previewW}px` }}>
              <div style={{ display: 'flex', gap: '8px' }}>
                {/* Snap Phone via ADB button */}
                <button
                  onClick={() => handleSnapAdb(s.id)}
                  disabled={capturingId === s.id}
                  style={{
                    flex: 1,
                    background: 'rgba(26, 32, 38, 0.7)',
                    border: '1px solid rgba(255, 255, 255, 0.12)',
                    color: '#D4DAE0',
                    padding: '7px 10px',
                    borderRadius: '12px',
                    fontSize: '0.72rem',
                    fontFamily: 'var(--mono)',
                    cursor: capturingId === s.id ? 'wait' : 'pointer',
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                    gap: '6px'
                  }}
                >
                  <span
                    style={{
                      width: '6px',
                      height: '6px',
                      borderRadius: '50%',
                      background: capturingId === s.id ? '#E8B97A' : '#4ADE80'
                    }}
                  />
                  {capturingId === s.id ? 'Snapping...' : 'Snap via ADB'}
                </button>

                {/* Replace File Upload */}
                <label
                  style={{
                    background: 'rgba(255, 255, 255, 0.04)',
                    border: '1px solid rgba(255, 255, 255, 0.1)',
                    color: '#949BA2',
                    padding: '7px 12px',
                    borderRadius: '12px',
                    fontSize: '0.72rem',
                    fontFamily: 'var(--mono)',
                    cursor: 'pointer',
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center'
                  }}
                >
                  Replace
                  <input
                    type="file"
                    accept="image/*"
                    onChange={(e) => handleFileUpload(s.id, e)}
                    style={{ display: 'none' }}
                  />
                </label>

                {/* Single PNG Export */}
                <button
                  onClick={() => exportSinglePng(idx)}
                  disabled={exporting}
                  style={{
                    background: 'rgba(255, 255, 255, 0.04)',
                    border: '1px solid rgba(255, 255, 255, 0.1)',
                    color: '#F4EBDD',
                    padding: '7px 12px',
                    borderRadius: '12px',
                    fontSize: '0.72rem',
                    fontFamily: 'var(--mono)',
                    cursor: 'pointer'
                  }}
                >
                  PNG
                </button>
              </div>

              {/* Editable Tag */}
              <input
                type="text"
                value={s.tag}
                onChange={(e) => {
                  const val = e.target.value;
                  setScreens((prev) => prev.map((item) => (item.id === s.id ? { ...item, tag: val } : item)));
                }}
                title="Eyebrow Tag"
                placeholder="Eyebrow Tag (e.g. LOCAL REASONING)"
                style={{
                  background: '#080A0D',
                  border: '1px solid #14181D',
                  borderRadius: '8px',
                  color: '#E8B97A',
                  fontFamily: 'var(--mono)',
                  fontSize: '0.74rem',
                  letterSpacing: '0.1em',
                  padding: '5px 8px'
                }}
              />

              {/* Editable Regular Phrase (Top line) */}
              <input
                type="text"
                value={s.regularHeadline}
                onChange={(e) => {
                  const val = e.target.value;
                  setScreens((prev) => prev.map((item) => (item.id === s.id ? { ...item, regularHeadline: val } : item)));
                }}
                title="Regular clause"
                placeholder="Top headline line"
                style={{
                  background: '#080A0D',
                  border: '1px solid #14181D',
                  borderRadius: '8px',
                  color: '#F4EBDD',
                  fontFamily: 'var(--serif)',
                  fontSize: '0.82rem',
                  padding: '5px 8px'
                }}
              />

              {/* Editable Italic Phrase (Accent line) */}
              <input
                type="text"
                value={s.italicHeadline}
                onChange={(e) => {
                  const val = e.target.value;
                  setScreens((prev) => prev.map((item) => (item.id === s.id ? { ...item, italicHeadline: val } : item)));
                }}
                title="Italic clause"
                placeholder="Italic accent clause"
                style={{
                  background: '#080A0D',
                  border: '1px solid #14181D',
                  borderRadius: '8px',
                  color: '#E8B97A',
                  fontFamily: 'var(--serif)',
                  fontStyle: 'italic',
                  fontSize: '0.82rem',
                  padding: '5px 8px'
                }}
              />
            </div>
          </div>
        ))}
      </main>
    </div>
  );
}
