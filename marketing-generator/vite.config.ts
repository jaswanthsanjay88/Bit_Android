import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import fs from 'fs'
import path from 'path'
import { execFile } from 'child_process'

function adbPlugin() {
  return {
    name: 'adb-screencap-middleware',
    configureServer(server: any) {
      server.middlewares.use('/api/adb-screencap', (req: any, res: any) => {
        const url = new URL(req.url, 'http://localhost')
        const id = url.searchParams.get('id') || '1'
        const adbPath = process.env.LOCALAPPDATA 
          ? path.join(process.env.LOCALAPPDATA, 'Android', 'Sdk', 'platform-tools', 'adb.exe')
          : 'adb'

        const outDir = path.resolve(__dirname, 'public', 'captures')
        if (!fs.existsSync(outDir)) fs.mkdirSync(outDir, { recursive: true })
        const outFile = path.join(outDir, `screen_${id}.png`)

        execFile(adbPath, ['exec-out', 'screencap', '-p'], { encoding: 'buffer', maxBuffer: 30 * 1024 * 1024 }, (err, stdout) => {
          if (err) {
            res.statusCode = 500
            res.setHeader('Content-Type', 'application/json')
            res.end(JSON.stringify({ error: err.message }))
            return
          }
          fs.writeFileSync(outFile, stdout)
          res.statusCode = 200
          res.setHeader('Content-Type', 'application/json')
          res.end(JSON.stringify({ success: true, url: `/captures/screen_${id}.png?t=${Date.now()}` }))
        })
      })
    }
  }
}

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), adbPlugin()],
})

