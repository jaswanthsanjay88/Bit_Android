/**
 * Cloudflare Worker: BIT App Reviews & Ratings API
 * Host: api.jaswanthsanjay.me
 */

export default {
  async fetch(request, env, ctx) {
    const corsHeaders = {
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
      'Access-Control-Allow-Headers': 'Content-Type, Authorization',
      'Content-Type': 'application/json; charset=UTF-8',
    };

    // 1. Handle CORS Preflight (OPTIONS request)
    if (request.method === 'OPTIONS') {
      return new Response(null, { headers: corsHeaders });
    }

    const url = new URL(request.url);
    const pathname = url.pathname.replace(/\/$/, '') || '/';

    // Auto-detect ANY bound KV Namespace instance in env (regardless of variable name)
    let kv = null;
    let boundKeyName = null;
    for (const key of Object.keys(env)) {
      if (env[key] && typeof env[key].get === 'function') {
        kv = env[key];
        boundKeyName = key;
        break;
      }
    }

    // 2. Route: /api/rating
    if (pathname === '/api/rating') {
      // GET /api/rating — Return all reviews
      if (request.method === 'GET') {
        try {
          let ratings = [];
          if (kv) {
            const stored = await kv.get('latest_ratings');
            if (stored) ratings = JSON.parse(stored);
          }

          if (!ratings || ratings.length === 0) {
            ratings = [
              {
                id: '1',
                name: 'Atharva',
                role: 'Lead Developer',
                rating: 5,
                comment: 'On-device GGUF LLM execution with zero data leakage!',
                avatar: 'https://api.dicebear.com/10.x/notionists/svg?seed=Atharva',
                timestamp: new Date().toISOString()
              },
              {
                id: '2',
                name: 'Alex Rivera',
                role: 'AI Researcher',
                rating: 5,
                comment: 'Whisper STT and Piper TTS running completely offline on Android.',
                avatar: 'https://api.dicebear.com/10.x/notionists/svg?seed=Alex',
                timestamp: new Date().toISOString()
              }
            ];
          }

          return new Response(JSON.stringify({
            success: true,
            ratings,
            kvBound: Boolean(kv),
            boundKeyName: boundKeyName || 'None'
          }), {
            status: 200,
            headers: corsHeaders,
          });
        } catch (err) {
          return new Response(JSON.stringify({ success: false, error: err.message }), {
            status: 500,
            headers: corsHeaders,
          });
        }
      }

      // POST /api/rating — Save review
      if (request.method === 'POST') {
        try {
          const body = await request.json();
          const { name, role, rating, comment, review, feedback, avatar, appVersion } = body || {};

          const newEntry = {
            id: Date.now().toString(),
            name: (name || 'Anonymous User').trim(),
            role: (role || 'BIT User').trim(),
            rating: Number(rating) || 5,
            comment: (comment || review || feedback || 'Great app!').trim(),
            avatar: avatar || `https://api.dicebear.com/10.x/notionists/svg?seed=${encodeURIComponent(name || 'BitUser')}`,
            appVersion: appVersion || '1.9.4',
            timestamp: new Date().toISOString()
          };

          if (kv) {
            const stored = await kv.get('latest_ratings');
            let currentList = stored ? JSON.parse(stored) : [];
            currentList.unshift(newEntry);
            currentList = currentList.slice(0, 50);
            await kv.put('latest_ratings', JSON.stringify(currentList));
            console.log(`Saved review to KV using binding '${boundKeyName}'`);
          } else {
            console.warn(`No valid KV Namespace binding found in env! Available env keys: [${Object.keys(env).join(', ')}]`);
          }

          return new Response(JSON.stringify({
            success: true,
            message: kv ? 'Review saved to KV!' : 'Review received, but KV binding missing in Cloudflare Dashboard!',
            review: newEntry,
            kvSaved: Boolean(kv),
            boundKeyName: boundKeyName || 'None',
            availableEnvKeys: Object.keys(env)
          }), {
            status: 200,
            headers: corsHeaders,
          });
        } catch (err) {
          return new Response(JSON.stringify({ success: false, error: err.message }), {
            status: 400,
            headers: corsHeaders,
          });
        }
      }
    }

    // 3. Return 403 Forbidden for root homepage and all other routes
    return new Response(JSON.stringify({ error: 'Forbidden', message: 'Direct access forbidden' }), {
      status: 403,
      headers: corsHeaders,
    });
  }
};
