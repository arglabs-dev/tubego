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
 if path.name.endswith('Activity.java') and path.name not in ['LocalizedActivity.java','DsActivity.java']:
  assert 'extends LocalizedActivity' in source or 'extends DsActivity' in source,(path.name,'not localized')
for path in files:
 for call in re.finditer(r'\bt\(\s*"((?:[^"\\]|\\.)*)"|\btf\(\s*"((?:[^"\\]|\\.)*)"',path.read_text()):
  literal=json.loads('"'+(call.group(1) or call.group(2))+'"')
  assert literal in catalog,(path.name,literal)
for label in ['Video 480p','Video 720p','Video 1080p','Máxima disponible','Solo audio (MP3)']:
 assert label in catalog,label
print('Localized screens, literal lookups and quality labels verified')

# Every declared screen must have a purpose, scope and icon in the shared system.
import xml.etree.ElementTree as ET
manifest=ET.parse(ROOT/'app/src/main/AndroidManifest.xml').getroot()
meta=(ROOT/'app/src/main/java/dev/arglabs/tubego/ScreenInfo.java').read_text()
for activity in manifest.findall('application/activity'):
 name=activity.attrib['{http://schemas.android.com/apk/res/android}name'].split('.')[-1]
 assert 'case "'+name+'"' in meta,(name,'missing screen metadata')
for info in re.finditer(r'new Info\("([^"\\]*)", "([^"\\]*)", "([^"\\]*)"',meta):
 for literal in info.groups():assert literal in catalog,('ScreenInfo.java',literal)
for path in files:
 for info in re.finditer(r'UiKit\.sectionCard\([^,]+,\s*"([^"\\]*)",\s*"([^"\\]*)"',path.read_text()):
  for literal in info.groups():assert literal in catalog,(path.name,literal)
print('All manifest screens have localized purpose, scope and visual metadata')
