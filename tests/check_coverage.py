"""Published line coverage gates over the complete Maven reactor."""
from pathlib import Path
import sys
import xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[1]
totals={key:[0,0] for key in ('domain','application','infrastructure','global')}
report=ROOT/'workflow-app/target/site/jacoco-aggregate/jacoco.xml'
if not report.is_file():
    raise SystemExit('FAILED missing aggregate coverage report')
tree=ET.parse(report)
for package in tree.findall('.//package'):
    name=package.attrib['name']
    counter=next(c for c in package.findall('counter') if c.attrib['type']=='LINE')
    covered,missed=int(counter.attrib['covered']),int(counter.attrib['missed'])
    layer='domain' if '/domain/' in name else 'application' if '/application/' in name else 'infrastructure'
    for key in (layer,'global'):
        totals[key][0]+=covered;totals[key][1]+=missed
failed=False
for key,threshold in [('domain',.90),('application',.80),('infrastructure',.60),('global',.80)]:
    covered,missed=totals[key];ratio=covered/(covered+missed) if covered+missed else 0
    print(f'{key}: {ratio:.2%} ({covered}/{covered+missed}); required {threshold:.0%}')
    failed|=ratio<threshold
sys.exit(1 if failed else 0)
