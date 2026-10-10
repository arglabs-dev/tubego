"""Validate app catalog coverage, key sets and formatter contracts without Android SDK."""
import json,pathlib,re,subprocess,sys
ROOT=pathlib.Path(__file__).resolve().parents[1]
subprocess.run([sys.executable,str(pathlib.Path(__file__).with_name('generate.py')),'--check'],check=True)
catalog=json.loads(pathlib.Path(__file__).with_name('catalog.json').read_text())
files=list((ROOT/'app/src/main/java/dev/arglabs/tubego').glob('*.java'))
for path in files:
 source=path.read_text()
 for call in re.finditer(r'Texts\.(?:text\([^,]+,|forOrigin\([^,]+,[^,]+,)\s*"((?:[^"\\]|\\.)*)"',source):
  literal=json.loads('"'+call.group(1)+'"')
  assert literal in catalog,(path.name,literal)
 if path.name in ['MediaFailureText.java','QualityNoticeText.java']:
  for literal in re.finditer(r'->\s*("(?:[^"\\]|\\.)*")',source):
   message=json.loads(literal.group(1))
   if message:assert message in catalog,(path.name,message)
 if path.name.endswith('Activity.java') and path.name!='LocalizedActivity.java':
  assert 'extends LocalizedActivity' in source,(path.name,'not localized')
for label in ['Video 480p','Video 720p','Video 1080p','Máxima disponible','Solo audio (MP3)']:
 assert label in catalog,label
print('Localized screens, literal lookups and quality labels verified')
