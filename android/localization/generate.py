"""Deterministic ES/EN Android resources. User content and protocol strings are excluded."""
import hashlib,json,pathlib,re,sys,xml.etree.ElementTree as ET
ROOT=pathlib.Path(__file__).resolve().parents[1]
CATALOG=json.loads(pathlib.Path(__file__).with_name('catalog.json').read_text())
def key(s):return 'ui_'+hashlib.sha256(s.encode()).hexdigest()[:12]
def placeholders(s):return re.findall(r'%(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[a-zA-Z%]',s)
def resource(s):
    # Android quoted resources retain whitespace; apostrophes/quotes must be escaped.
    return '"'+s.replace('\\','\\\\').replace('"','\\"').replace("'","\\'").replace('\n','\\n')+'"'
def output(language):
 root=ET.Element('resources')
 for source,values in sorted(CATALOG.items(),key=lambda x:key(x[0])):
  assert set(values)=={'es','en'} and values['en'], source
  assert placeholders(values['es'])==placeholders(values['en']),source
  ET.SubElement(root,'string',name=key(source),formatted='false').text=resource(values[language])
 ET.indent(root);return ET.tostring(root,encoding='unicode')+'\n'
for language,folder in [('es','values'),('en','values-en')]:
 path=ROOT/'app/src/main/res'/folder/'strings.xml';content=output(language)
 if '--check' in sys.argv:assert path.read_text()==content,str(path)+' needs regeneration'
 else:path.parent.mkdir(parents=True,exist_ok=True);path.write_text(content)
assert len({key(s)for s in CATALOG})==len(CATALOG),'resource hash collision'
print(f'{len(CATALOG)} ES/EN strings verified')
