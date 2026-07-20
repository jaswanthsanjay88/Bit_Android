import React, { useState } from 'react';

const SCREENS = [
  {
    id: 1,
    tag: 'ON-DEVICE GGUF LLM',
    title: 'Offline Intelligence.\nZero Cloud Dependency.',
    sub: 'Run production Llama, Qwen, and Gemma models fully locally on Android silicon.',
    img: '/bit_chat_interface.jpg'
  },
  {
    id: 2,
    tag: 'HANDS-FREE CONVERSATIONAL UI',
    title: 'Live Voice Mode.\nDynamic Pulsing Orb.',
    sub: 'Sherpa-ONNX Whisper STT paired with Piper VITS neural voice synthesis and VAD barge-in.',
    img: '/bit_live_voice_mode.jpg'
  },
  {
    id: 3,
    tag: 'GGUF MODEL MANAGEMENT',
    title: 'HuggingFace Store &\nLocal SAF Importer.',
    sub: 'Search, download, and switch quantized GGUF weights directly on device storage.',
    img: '/bit_model_store.jpg'
  },
  {
    id: 4,
    tag: 'SAMPLER-LEVEL CONTROLS',
    title: 'Per-Model Config &\nGBNF Tool Router.',
    sub: 'Tune temperature, context size up to 32k, repetition penalty, and GBNF JSON tool calls.',
    img: '/bit_model_editor.jpg'
  }
];

export default function App() {
  const [device, setDevice] = useState<'play' | 'appstore'>('play');

  return (
    <div style={{ background: '#000000', color: '#FFFFFF', minHeight: '100vh', fontFamily: 'system-ui, sans-serif', padding: '40px 20px' }}>
      <header style={{ maxWidth: '1200px', margin: '0 auto 40px auto', display: 'flex', justifyContent: 'space-between', alignItems: 'center', borderBottom: '1px solid #222', paddingBottom: '20px' }}>
        <div>
          <h1 style={{ fontSize: '1.8rem', fontWeight: 800, margin: 0 }}>BIT App Store Screenshot Generator</h1>
          <p style={{ color: '#888', margin: '4px 0 0 0', fontSize: '0.9rem' }}>Monochrome Marketing Screenshots for Google Play Store & Apple App Store</p>
        </div>
        <div>
          <button 
            onClick={() => setDevice('play')}
            style={{ background: device === 'play' ? '#FFF' : '#111', color: device === 'play' ? '#000' : '#FFF', border: '1px solid #333', padding: '10px 18px', borderRadius: '20px', cursor: 'pointer', fontWeight: 600, marginRight: '10px' }}
          >
            Google Play (1080×1920)
          </button>
          <button 
            onClick={() => setDevice('appstore')}
            style={{ background: device === 'appstore' ? '#FFF' : '#111', color: device === 'appstore' ? '#000' : '#FFF', border: '1px solid #333', padding: '10px 18px', borderRadius: '20px', cursor: 'pointer', fontWeight: 600 }}
          >
            App Store (1290×2796)
          </button>
        </div>
      </header>

      <main style={{ maxWidth: '1400px', margin: '0 auto', display: 'flex', gap: '30px', overflowX: 'auto', paddingBottom: '40px' }}>
        {SCREENS.map((s) => (
          <div 
            key={s.id}
            style={{
              width: device === 'play' ? '360px' : '340px',
              height: device === 'play' ? '640px' : '735px',
              flexShrink: 0,
              background: '#0A0A0A',
              border: '1px solid #222',
              borderRadius: '24px',
              padding: '32px 24px',
              display: 'flex',
              flexDirection: 'column',
              justify-content: 'space-between',
              position: 'relative',
              boxShadow: '0 20px 50px rgba(0,0,0,0.8)'
            }}
          >
            <div>
              <div style={{ fontSize: '0.68rem', fontWeight: 700, letterSpacing: '0.1em', color: '#888', marginBottom: '12px' }}>{s.tag}</div>
              <h2 style={{ fontSize: '1.4rem', fontWeight: 700, lineHeight: 1.25, margin: '0 0 10px 0', whiteSpace: 'pre-line' }}>{s.title}</h2>
              <p style={{ fontSize: '0.8rem', color: '#888', margin: 0, lineHeight: 1.45 }}>{s.sub}</p>
            </div>

            {/* Phone Frame */}
            <div 
              style={{
                width: '240px',
                height: '460px',
                margin: '20px auto 0 auto',
                background: '#000',
                borderRadius: '36px',
                border: '6px solid #1C1C1C',
                position: 'relative',
                overflow: 'hidden',
                boxShadow: '0 25px 60px rgba(0,0,0,0.95), inset 1px 1px 0px rgba(255,255,255,0.2)'
              }}
            >
              <div style={{ position: 'absolute', top: '8px', left: '50%', transform: 'translateX(-50%)', width: '70px', height: '14px', background: '#000', borderRadius: '7px', zIndex: 10 }} />
              <img src={s.img} alt={s.title} style={{ width: '100%', height: '100%', objectFit: 'cover', filter: 'grayscale(100%) contrast(1.08)' }} />
            </div>
          </div>
        ))}
      </main>
    </div>
  );
}
