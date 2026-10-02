const V="mg-v44";
const SHELL=["./","index.html","data.json","manifest.webmanifest","icon-192.png","icon-512.png","corsair.webp","f7c.webp","vulture.webp","exo.webp","pulse.webp","galactapedia-fr.json"];
self.addEventListener("install",e=>{e.waitUntil(caches.open(V).then(c=>c.addAll(SHELL)).then(()=>self.skipWaiting()));});
self.addEventListener("activate",e=>{e.waitUntil(caches.keys().then(ks=>Promise.all(ks.filter(k=>k!==V&&k!=="mg-fonts").map(k=>caches.delete(k)))).then(()=>self.clients.claim()));});
self.addEventListener("fetch",e=>{const u=new URL(e.request.url);if(e.request.method!=="GET")return;
  if(/fonts\.(googleapis|gstatic)\.com$|media\.starcitizen\.tools$/.test(u.hostname)){e.respondWith(caches.open("mg-fonts").then(c=>c.match(e.request).then(hit=>{const net=fetch(e.request).then(r=>{if(r.ok||r.type==="opaque")c.put(e.request,r.clone());return r;}).catch(()=>hit||Response.error());return hit||net;})));return;}
  if(u.origin!==location.origin)return;
  if(u.pathname.endsWith("data.json")||u.pathname.endsWith("/")||u.pathname.endsWith("index.html")){
    e.respondWith(fetch(new Request(e.request.url,{cache:"no-store",credentials:"same-origin"})).then(r=>{const cp=r.clone();caches.open(V).then(c=>c.put(e.request,cp));return r;}).catch(()=>caches.match(e.request,{ignoreSearch:true})));return;}
  e.respondWith(caches.match(e.request).then(r=>r||fetch(e.request)));});
