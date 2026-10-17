#!/usr/bin/env python3
"""Optional CPU smoke tests; needs litert-lm==0.18.0, sherpa-onnx==1.13.8 and numpy.
Never downloads/uploads. Does not validate Android execution, codecs or speaker output.
"""
import argparse
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--assistant',required=True)
parser.add_argument('--spleeter-dir',required=True)
args=parser.parse_args()

import hashlib,json,time
from pathlib import Path
import litert_lm
model=Path(args.assistant)
assert model.stat().st_size==1597922856
h=hashlib.sha256()
with model.open('rb') as f:
 for data in iter(lambda:f.read(1024*1024),b''): h.update(data)
assert h.hexdigest()=='c4a9b7289365ab80fe599b844b77ad253eb3c53fb0e5d963d699146a6f01255e'
cache=model.parent/'host-smoke-cache';cache.mkdir(exist_ok=True)
start=time.monotonic()
import re
source_root=Path(__file__).resolve().parents[1]/'app/src/main/java/sa/rihla/audio/studio'
assistant_source=(source_root/'LocalAssistant.kt').read_text()
system=re.search(r'"""(You are Mashwari.*?recognition\.)"""',assistant_source,re.S).group(1)
system=system.replace('${AudioScene.entries.joinToString { it.key }}','natural,3d,7.1,custom,lecture_soft,lecture_clear').replace('${AssistantNavigation.pages.joinToString()}','dashboard,history,calendar,map,audio,statistics,vehicle,expenses,settings')
organizer_source=(source_root/'AudioOrganizer.kt').read_text()
classifier=re.search(r'ai.generate\(\s*"([^"\n]+)"',organizer_source).group(1)
tracks=[dict(id=hashlib.sha256(f'track-{i}'.encode()).hexdigest(),title=title,artist='الشيخ عثمان الخميس',album='محاضرات') for i,title in enumerate(['تفسير سورة الفاتحة','شرح فقه الصلاة','دروس العقيدة','شرح حديث النية','السيرة النبوية','فقه الصيام'])]
def parse(text):
 return json.loads(str(text).strip().removeprefix('```json').removeprefix('```').removesuffix('```').strip())
with litert_lm.Engine(model_path=str(model),backend=litert_lm.Backend.CPU(thread_count=4),max_num_tokens=4096,cache_dir=str(cache)) as engine:
 with engine.create_conversation(system_message=system,sampler_config=litert_lm.SamplerConfig(top_k=1,top_p=.9,temperature=.1),max_output_tokens=768) as conversation:
  reply=parse(conversation.send_message('المشاوير: 0. المصاريف المسجلة: 0. الصوتيات: 0. طلب المستخدم: افتح الخريطة'))
  assert reply['action']=='navigate' and reply['value']=='map' and reply['reply']
 with engine.create_conversation(system_message=classifier,sampler_config=litert_lm.SamplerConfig(top_k=1,top_p=.9,temperature=.1),max_output_tokens=768) as conversation:
  reply=parse(conversation.send_message(json.dumps(tracks,ensure_ascii=False)))
  rows=reply if isinstance(reply,list) else reply['items']
  assert len(rows)==len(tracks) and {r['id'] for r in rows}=={t['id'] for t in tracks}
  assert all(1<=len(r['topic'])<=80 and r['source']=='الشيخ عثمان الخميس' for r in rows)

print('PASS verified Qwen CPU inference',round(time.monotonic()-start,2),'seconds',flush=True)

from pathlib import Path
import hashlib, json, time
import numpy as np
import sherpa_onnx
root=Path(args.spleeter_dir)
for name, expected in [('vocals','24cef84aedcd1fe87c0b743ef3370ad34dc1fabf6c9014d6128a75a538c7b668'),('accompaniment','d14cea55793cc531a5875f5f4da08207d1c5ab9292e8e0099a104eecb014fcc0')]:
 assert hashlib.sha256((root/f'{name}.fp16.onnx').read_bytes()).hexdigest()==expected
config=sherpa_onnx.OfflineSourceSeparationConfig(model=sherpa_onnx.OfflineSourceSeparationModelConfig(spleeter=sherpa_onnx.OfflineSourceSeparationSpleeterModelConfig(vocals=str(root/'vocals.fp16.onnx'),accompaniment=str(root/'accompaniment.fp16.onnx')),num_threads=2))
start=time.monotonic();engine=sherpa_onnx.OfflineSourceSeparation(config)
t=np.arange(44100*3,dtype=np.float32)/44100
signal=(.12*np.sin(2*np.pi*220*t)+.08*np.sin(2*np.pi*1200*t)).astype(np.float32)
padded=np.pad(signal,(4096,4096))
output=engine.process(44100,np.stack([padded,padded]))
assert output.sample_rate==44100 and len(output.stems)==2
for stem in output.stems:
 samples=np.asarray(stem.data)[:,4096:4096+signal.size]
 assert samples.shape==(2,signal.size) and float(np.max(np.abs(samples)))<1
 assert np.isfinite(samples).all() and samples.size>0
 print('stem',samples.shape,'peak',float(np.max(np.abs(samples))),flush=True)
print('PASS real Spleeter ONNX CPU inference',round(time.monotonic()-start,2),'seconds',flush=True)
