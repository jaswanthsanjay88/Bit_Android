import{j as e,r as c}from"./index-CXXFxKSd.js";import{u,B as l,X as p,c as h}from"./input-BOpLvaQi.js";import{g as x,M as g}from"./mermaid-O7DHMXV3-BkQL13H3.js";function m(r,s){const o=r[s];return typeof o=="string"?o:""}function y({panel:r}){const{t:s}=u(),[o,i]=c.useState("preview"),a=m(r.payload,"language"),t=x(a),n=m(r.payload,"code"),d=t==="html"||t==="svg"||t==="markdown"||t==="mermaid";c.useEffect(()=>{i(d?"preview":"source")},[d,r.payload]);const f=c.useMemo(()=>t==="html"?n:t==="svg"?`<!doctype html><html><body style="margin:0;display:flex;align-items:center;justify-content:center;padding:16px;">${n}</body></html>`:t==="mermaid"?`<!doctype html>
<html>
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <style>
      html, body {
        margin: 0;
        padding: 0;
        background: #ffffff;
        color: #1f2937;
        font-family: ui-sans-serif, system-ui, sans-serif;
      }
      #container {
        min-height: 100vh;
        box-sizing: border-box;
        padding: 16px;
        display: flex;
        justify-content: center;
      }
      #diagram {
        width: 100%;
      }
      #error {
        display: none;
        width: 100%;
        white-space: pre-wrap;
        color: #b91c1c;
        background: #fef2f2;
        border: 1px solid #fecaca;
        border-radius: 8px;
        padding: 12px;
        font-size: 12px;
      }
    </style>
  </head>
  <body>
    <div id="container">
      <div id="diagram"></div>
      <pre id="error"></pre>
    </div>
    <script type="module">
      import mermaid from "https://esm.sh/mermaid@11";

      const source = decodeURIComponent("${encodeURIComponent(n)}");
      const diagram = document.getElementById("diagram");
      const errorEl = document.getElementById("error");

      mermaid.initialize({
        startOnLoad: false,
        securityLevel: "loose",
      });

      try {
        const id = "mermaid-" + Math.random().toString(36).slice(2);
        const result = await mermaid.render(id, source.trim());
        if (diagram) {
          diagram.innerHTML = result.svg;
        }
      } catch (error) {
        if (errorEl) {
          errorEl.style.display = "block";
          errorEl.textContent = error instanceof Error ? error.message : String(error);
        }
      }
    <\/script>
  </body>
</html>`:"",[n,t]);return e.jsxs("div",{className:"flex h-full min-h-0 flex-col",children:[e.jsxs("div",{className:"flex items-center gap-2 border-b px-3 py-2",children:[e.jsx(l,{type:"button",size:"sm",variant:o==="preview"?"secondary":"ghost",disabled:!d,onClick:()=>{i("preview")},children:s("workbench.preview")}),e.jsx(l,{type:"button",size:"sm",variant:o==="source"?"secondary":"ghost",onClick:()=>{i("source")},children:s("workbench.source_code")})]}),e.jsx("div",{className:"flex-1 min-h-0",children:o==="preview"&&d?t==="markdown"?e.jsx("div",{className:"h-full overflow-auto p-4",children:e.jsx(g,{content:n,allowCodePreview:!1})}):e.jsx("iframe",{title:r.title,sandbox:"allow-scripts allow-same-origin",srcDoc:f,className:"h-full w-full border-0"}):e.jsx("pre",{className:"h-full overflow-auto bg-muted/30 p-4 text-xs",children:n||s("workbench.empty_content")})})]})}function v({panel:r}){return e.jsx("div",{className:"h-full overflow-auto p-4",children:e.jsx("div",{className:"rounded-lg border bg-muted/30 p-3 text-xs",children:e.jsx("pre",{children:JSON.stringify(r.payload,null,2)})})})}const b={"code-preview":{render:r=>e.jsx(y,{panel:r})}};function E({panel:r,onClose:s,className:o}){const{t:i}=u(),a=b[r.type];return e.jsxs("section",{className:h("flex h-full min-h-0 flex-col border-l",o),children:[e.jsxs("div",{className:"flex items-center justify-between gap-2 border-b px-3 py-2",children:[e.jsxs("div",{className:"min-w-0",children:[e.jsx("div",{className:"truncate font-medium text-sm",children:r.title}),e.jsx("div",{className:"truncate text-muted-foreground text-xs",children:i("workbench.type_label",{type:r.type})})]}),e.jsx(l,{"aria-label":i("workbench.close_panel"),type:"button",size:"icon-sm",variant:"ghost",onClick:s,children:e.jsx(p,{className:"size-4"})})]}),e.jsx("div",{className:"flex-1 min-h-0",children:a?a.render(r):e.jsx(v,{panel:r})})]})}export{E as WorkbenchHost};
