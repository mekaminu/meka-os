import math
from PIL import Image, ImageDraw, ImageFont
exec(open('make.py').read().split("opts=[")[0])
def tri(d,a,r_out,r_in,w,col):
    d.polygon([P(a,r_out),P(a-w,r_in),P(a+w,r_in)],fill=col)
def a1(mono=None):  # true compass: needle near north, bright N / dark S, N triangle marker
    im=base(mono is None); d=ImageDraw.Draw(im)
    for a in (90,180,270): d.line([P(a,335),P(a,380)],fill=mono or DIM,width=16)
    tri(d,0,392,322,5,mono or BRIGHT)
    needle(d,18,300,40,mono or BRIGHT,mono or DIM,tail=300)
    d.ellipse([C-28,C-28,C+28,C+28],fill=mono or BR); d.ellipse([C-10,C-10,C+10,C+10],fill=BG)
    return im
def a2(mono=None):  # thin rim + 8 ticks, needle at 30°, N diamond
    im=base(mono is None); d=ImageDraw.Draw(im)
    R=400; d.ellipse([C-R,C-R,C+R,C+R],outline=mono or BR,width=8)
    for a in range(0,360,45):
        if a==0: continue
        L=40 if a%90==0 else 22; d.line([P(a,R-14),P(a,R-14-L)],fill=mono or (BR if a%90==0 else DIM),width=12 if a%90==0 else 8)
    d.polygon([P(0,R-12),P(-4,R-50),P(0,R-88),P(4,R-50)],fill=mono or BRIGHT)
    needle(d,30,290,40,mono or BRIGHT,mono or DIM,tail=240)
    d.ellipse([C-28,C-28,C+28,C+28],fill=mono or BR); d.ellipse([C-10,C-10,C+10,C+10],fill=BG)
    return im
def a3(mono=None):  # needle + small N letter, dot markers
    im=base(mono is None); d=ImageDraw.Draw(im)
    f=ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf',92)
    d.text((C,C-345),'N',font=f,fill=mono or BRIGHT,anchor='mm')
    for a in (90,180,270):
        x,y=P(a,350); d.ellipse([x-16,y-16,x+16,y+16],fill=mono or DIM)
    needle(d,24,285,42,mono or BRIGHT,mono or DIM,tail=255)
    d.ellipse([C-28,C-28,C+28,C+28],fill=mono or BR); d.ellipse([C-10,C-10,C+10,C+10],fill=BG)
    return im
opts=[('A1 · True north',a1),('A2 · Fine bezel',a2),('A3 · Needle and N',a3)]
for i,(n,fn) in enumerate(opts): fn().resize((512,512),Image.LANCZOS).save(f'variant-a{i+1}.png')
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
sheet.save('compass-a-variants.png')
