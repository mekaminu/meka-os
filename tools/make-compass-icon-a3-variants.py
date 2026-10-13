import math
from PIL import Image, ImageDraw, ImageFont
exec(open('make.py').read().split("opts=[")[0])
FN=ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf',96)
def ticks(d,R,mono,skip0=True):
    for h in range(12):
        a=h*30
        if skip0 and a==0: continue
        major=a%90==0
        d.line([P(a,R),P(a,R-(46 if major else 26))],fill=mono or (BR if major else DIM),width=14 if major else 9)
def capn(d,mono): d.ellipse([C-28,C-28,C+28,C+28],fill=mono or BR); d.ellipse([C-10,C-10,C+10,C+10],fill=BG)
def v1(mono=None):  # watch dial: 11 hour ticks, N at 12, compass needle
    im=base(mono is None); d=ImageDraw.Draw(im)
    ticks(d,395,mono); d.text((C,C-345),'N',font=FN,fill=mono or BRIGHT,anchor='mm')
    needle(d,24,280,40,mono or BRIGHT,mono or DIM,tail=250); capn(d,mono); return im
def v2(mono=None):  # + fine ring
    im=base(mono is None); d=ImageDraw.Draw(im)
    R=410; d.ellipse([C-R,C-R,C+R,C+R],outline=mono or BR,width=7)
    ticks(d,385,mono); d.text((C,C-330),'N',font=ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf',84),fill=mono or BRIGHT,anchor='mm')
    needle(d,24,270,38,mono or BRIGHT,mono or DIM,tail=240); capn(d,mono); return im
def v3(mono=None):  # needle shaped like a watch hand: long tapered bright hand to N side, short dark counterweight
    im=base(mono is None); d=ImageDraw.Draw(im)
    ticks(d,395,mono); d.text((C,C-345),'N',font=FN,fill=mono or BRIGHT,anchor='mm')
    a=24; tip=P(a,290); l=P(a-90,26); r=P(a+90,26)
    d.polygon([tip,r,(C+0,C+0),l],fill=mono or BRIGHT)
    tl=P(a+180,110); d.line([(C,C),tl],fill=mono or DIM,width=30); x,y=tl; d.ellipse([x-30,y-30,x+30,y+30],fill=mono or DIM)
    capn(d,mono); return im
opts=[('A3·1 · Watch dial with N',v1),('A3·2 · Dial, N and fine ring',v2),('A3·3 · Watch-hand needle',v3)]
for i,(n,fn) in enumerate(opts): fn().resize((512,512),Image.LANCZOS).save(f'variant-a3-{i+1}.png')
W,H=1230,640; sheet=Image.new('RGB',(W,H),(0,0,0)); d=ImageDraw.Draw(sheet)
fb=ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf',22); fs=ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf',12)
for row,(name,fn) in enumerate(opts):
    y0=30+row*200
    l1,l2=name.split(' · ',1); d.text((30,y0+60),l1,font=fb,fill=BR); d.text((30,y0+92),l2,font=ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf',17),fill=BR)
    for k,(bgc,txt) in enumerate([((18,18,22),(220,220,220)),((228,226,220),(40,40,40))]):
        x0=200+k*430; d.rounded_rectangle([x0,y0,x0+410,y0+180],radius=18,fill=bgc)
        icon=fn()
        for j,(sz,shape) in enumerate([(112,'circle'),(64,'circle'),(64,'squircle'),(40,'circle')]):
            ic=mask(icon,shape).resize((sz,sz),Image.LANCZOS)
            xs=[x0+20,x0+150,x0+235,x0+330][j]; ys=y0+20+(112-sz)//2
            sheet.paste(ic,(xs,ys),ic)
            d.text((xs+sz//2,y0+150),'Compass',font=fs,fill=txt,anchor='mm')
    m=mask(mono_icon(fn),'circle').resize((96,96),Image.LANCZOS)
    d.rounded_rectangle([1070,y0+30,1190,y0+150],radius=18,fill=(44,44,50)); sheet.paste(m,(1082,y0+42),m)
d.text((1130,20),'Themed',font=fs,fill=(150,150,150),anchor='mm')
sheet.save('compass-a3-variants.png')
