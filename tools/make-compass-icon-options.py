import math
from PIL import Image, ImageDraw, ImageFilter, ImageFont
S=1024; C=S/2
BR=(0xC9,0xB3,0x7E); BRIGHT=(0xEC,0xD7,0x9E); DIM=(0x7A,0x6A,0x45); BG=(8,8,8)
def base(glow=True):
    im=Image.new('RGBA',(S,S),BG+(255,))
    if glow:
        g=Image.new('RGBA',(S,S),(0,0,0,0)); d=ImageDraw.Draw(g)
        for r in range(380,0,-6):
            a=int(38*(1-r/380)**1.6); d.ellipse([C-r,C-r,C+r,C+r],fill=BR+(a,))
        im=Image.alpha_composite(im,g)
    return im
def P(a,r,cx=C,cy=C):  # angle degrees clockwise from north
    t=math.radians(a); return (cx+r*math.sin(t), cy-r*math.cos(t))
def needle(d,ang,L,w,colN,colS,tail=None):
    tail=tail or L
    tip=P(ang,L); back=P(ang+180,tail); l=P(ang-90,w); r=P(ang+90,w)
    d.polygon([tip,r,(C,C),l],fill=colN)
    d.polygon([back,l,(C,C),r],fill=colS)
def pin(d,r1=34,r2=13):
    d.ellipse([C-r1,C-r1,C+r1,C+r1],fill=BR); d.ellipse([C-r2,C-r2,C+r2,C+r2],fill=BG)
def opt_a(mono=None):
    im=base(mono is None); d=ImageDraw.Draw(im)
    c1=mono or BRIGHT; c2=mono or DIM
    for a in (0,90,180,270):
        p1=P(a,330); p2=P(a,385); d.line([p1,p2],fill=mono or BR,width=22)
    needle(d,32,330,46,c1,c2,tail=230)
    pin(d) if mono is None else (d.ellipse([C-34,C-34,C+34,C+34],fill=mono), d.ellipse([C-13,C-13,C+13,C+13],fill=BG))
    return im
def opt_b(mono=None):
    im=base(mono is None); d=ImageDraw.Draw(im)
    for a in (45,135,225,315):
        needle(d,a,230,34,mono or DIM,mono or DIM,tail=0.01)
    for a in (90,180,270):
        needle(d,a,370,44,mono or BR,mono or BR,tail=0.01)
    needle(d,0,400,46,mono or BRIGHT,mono or BRIGHT,tail=0.01)
    d.ellipse([C-30,C-30,C+30,C+30],fill=BG); d.ellipse([C-14,C-14,C+14,C+14],fill=mono or BR)
    return im
def opt_c(mono=None):
    im=base(mono is None); d=ImageDraw.Draw(im)
    R=380; d.ellipse([C-R,C-R,C+R,C+R],outline=mono or BR,width=18)
    try: f=ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf',104)
    except: f=ImageFont.load_default()
    d.text((C,C-285),'N',font=f,fill=mono or BRIGHT,anchor='mm')
    needle(d,0,215,40,mono or BRIGHT,mono or DIM,tail=215)
    d.ellipse([C-26,C-26,C+26,C+26],fill=mono or BR); d.ellipse([C-10,C-10,C+10,C+10],fill=BG)
    return im
def mask(im,shape):
    m=Image.new('L',(S,S),0); d=ImageDraw.Draw(m)
    if shape=='circle': d.ellipse([0,0,S,S],fill=255)
    else: d.rounded_rectangle([0,0,S,S],radius=230,fill=255)
    out=Image.new('RGBA',(S,S),(0,0,0,0)); out.paste(im,(0,0),m); return out
def mono_icon(fn):
    im=fn(mono=(0x2B,0x27,0x1F)); # dark glyph on cream, Samsung themed look
    im2=Image.new('RGBA',(S,S),(0xEF,0xE6,0xCF,255)); px=im.load(); p2=im2.load()
    for y in range(0,S):
        for x in range(0,S):
            if px[x,y][:3]!=BG: p2[x,y]=(0x2B,0x27,0x1F,255)
    return im2
opts=[('A · Needle hands',opt_a),('B · Compass rose',opt_b),('C · Ring and N',opt_c)]
for i,(n,fn) in enumerate(opts): fn().resize((512,512),Image.LANCZOS).save(f'option-{"abc"[i]}.png')
W,H=1230,640; sheet=Image.new('RGB',(W,H),(0,0,0)); d=ImageDraw.Draw(sheet)
fb=ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf',22); fs=ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf',12)
for row,(name,fn) in enumerate(opts):
    y0=30+row*200
    l1,l2=name.split(' · '); d.text((30,y0+60),l1,font=fb,fill=BR); d.text((30,y0+92),l2,font=ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf',17),fill=BR)
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
sheet.save('compass-options-sheet.png')
