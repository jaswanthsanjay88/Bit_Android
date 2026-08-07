import re

kt_file = r'e:\BIT\app\src\main\java\com\bit\ui\screen\guide\TermsAndConditionsScreen.kt'
html_file = r'e:\BIT\site\privacy.html'

with open(kt_file, 'r', encoding='utf-8') as f:
    content = f.read()

# Extract all TermsSection
sections = re.findall(r'TermsSection\(\s*title\s*=\s*\"(.*?)\",\s*content\s*=\s*\"\"\"(.*?)\"\"\"\.trimIndent\(\)\s*\)', content, re.DOTALL)

html_content = '''<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0" />
  <title>Privacy Policy & Terms — BIT</title>
  <link rel="preconnect" href="https://fonts.googleapis.com" />
  <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin />
  <link href="https://fonts.googleapis.com/css2?family=Fraunces:ital,opsz,wght@0,9..144,300..700;1,9..144,300..700&family=Inter:wght@300;400;500;600;700;800&display=swap" rel="stylesheet" />
  <link rel="stylesheet" href="style.css" />
  <style>
    body { padding-top: 100px; }
    .legal-container { max-width: 800px; margin: 0 auto; padding: 40px 20px; }
    .legal-title { font-family: var(--font-display); font-size: 2.5rem; margin-bottom: 10px; color: var(--text-primary); }
    .legal-subtitle { color: var(--text-secondary); margin-bottom: 40px; font-size: 1.1rem; }
    .legal-section { margin-bottom: 40px; }
    .legal-section h2 { color: #ffcc00; font-family: monospace; font-size: 1.2rem; margin-bottom: 16px; font-weight: 700; }
    .legal-section p { color: var(--text-secondary); font-family: monospace; font-size: 0.95rem; line-height: 1.6; margin-bottom: 16px; white-space: pre-wrap; }
  </style>
</head>
<body>
  <header class="navbar scrolled" id="navbar">
    <div class="container nav-container">
      <a href="index.html" class="nav-brand" aria-label="BIT Home">
        <img src="img/ic_logo.svg" alt="BIT Logo" class="logo-img" onerror="this.style.display='none'"/>
        <span>BIT</span>
      </a>
      <ul class="nav-links">
        <li><a href="index.html" class="nav-link">Home</a></li>
      </ul>
    </div>
  </header>

  <div class="container legal-container">
    <h1 class="legal-title">Terms & Conditions / Privacy Policy</h1>
    <p class="legal-subtitle">Version 3.1 &bull; Please read carefully before using BIT Local AI Assistant</p>
'''

for title, text in sections:
    html_content += f'''
    <div class="legal-section">
      <h2>{title}</h2>
      <p>{text.strip()}</p>
    </div>
'''

html_content += '''
  </div>
</body>
</html>
'''

with open(html_file, 'w', encoding='utf-8') as f:
    f.write(html_content)

print('Generated privacy.html')
