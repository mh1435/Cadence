import io, json, base64, sys
from PIL import Image

# slot id -> (sheet species index, name matching the new art)
ASSIGN = [
 ("oak",9,"Green Oak"),          ("cactus",17,"Cactus"),        ("cherry",4,"Cherry Blossom"),
 ("maple",5,"Autumn Maple"),     ("tulip",18,"Tulip"),          ("toadstool",22,"Toadstool"),
 ("candy",3,"Golden Grove"),     ("honeycomb",7,"Honeycomb Cap"),("blossom",15,"Rose Blossom"),
 ("bluebell",20,"Bluebell Tree"),("spotcap",14,"Spotted Cap"),  ("mistvine",16,"Mist Vine"),
 ("willow",10,"Moon Willow"),    ("crystal",13,"Crystal Bloom"),("violetpine",2,"Violet Pine"),
 ("emberfung",26,"Ember Fungus"),("crimson",1,"Crimson Curl"),  ("lagoon",24,"Lagoon Palm"),
 ("orchard",11,"Golden Orchard"),("elder",0,"Starlit Oak"),     ("embervine",12,"Ember Vine"),
 ("morel",25,"Morel Cluster"),   ("coralcap",21,"Violet Cap"),  ("sunvine",6,"Teal Lotus"),
 ("orchid",23,"Blush Cap"),      ("regalia",8,"Lantern Bloom"), ("blushcap",19,"Star Coral"),
 ("reefstalk",27,"Flame Lotus"),
]

def frames_for(sheet_id, target):
    meta={m["id"]:m for m in json.load(open("spec_meta_new.json"))}[sheet_id]
    ims=[Image.open("spec_out/s%02d_%d.png"%(sheet_id,k)).convert("RGBA") for k in range(meta["stages"])]
    if len(ims)==5:
        # all five are real stages, so drop whichever leaves the smoothest
        # size progression rather than assuming a fixed index
        w=[i.size[0] for i in ims]
        def unevenness(keep):
            r=[w[keep[i+1]]/w[keep[i]] for i in range(3)]
            m=sum(r)/3
            return sum((x-m)**2 for x in r)
        best=min(([0,1,2,3],[0,1,2,4],[0,1,3,4],[0,2,3,4]), key=unevenness)
        ims=[ims[i] for i in best]
    assert len(ims)==4, (sheet_id, len(ims))
    biggest=max(max(i.size) for i in ims)
    f=min(1.0, target/biggest)                                # one factor per species
    return [i.resize((max(1,round(i.size[0]*f)), max(1,round(i.size[1]*f))), Image.LANCZOS) for i in ims]

def enc(im,q):
    b=io.BytesIO(); im.save(b,"WEBP",quality=q,method=6,alpha_quality=100); return b.getvalue()

if __name__=="__main__":
    target=int(sys.argv[1]); q=int(sys.argv[2])
    art={}; total=0
    for slot, sheet_id, name in ASSIGN:
        uris=[]
        for im in frames_for(sheet_id,target):
            d=enc(im,q); total+=len(d)
            uris.append("data:image/webp;base64,"+base64.b64encode(d).decode())
        art[slot]=uris
    js="const PLANT_ART = {\n"+",\n".join('  "%s":[%s]'%(k,",".join('"%s"'%u for u in v)) for k,v in art.items())+"\n};"
    open("plant_art_new.js","w").write(js)
    print("target %d q%d -> webp %.0f KB, block %.0f KB"%(target,q,total/1024,len(js)/1024))
