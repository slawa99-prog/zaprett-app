// Device test only. A real WebView HTMLMediaElement plays a changing canvas
// stream with real media timestamps. Only the upstream YouTube boundary is replaced.
var canvas=document.createElement('canvas');canvas.width=320;canvas.height=200;
var g=canvas.getContext('2d'), k=0;
function draw(){k++;g.fillStyle='#126356';g.fillRect(0,0,320,200);g.fillStyle='#fff';g.fillRect(k%250,50,45,50);}
draw();setInterval(draw,40);
var v=document.createElement('video');v.muted=true;v.autoplay=false;v.playsInline=true;
document.getElementById('box').replaceChildren(v);
v.srcObject=canvas.captureStream(25);
v.addEventListener('playing',function(){sv.state=1;clearGesture();});
v.addEventListener('waiting',function(){sv.state=3;});
v.addEventListener('pause',function(){sv.state=2;});
kick=function(){v.play().catch(askForGesture);};sv.ready=true;
setTimeout(kick,svIndex===2?4500:100);
readMedia=function(){sv.pos=v.currentTime;};

if(svIndex===4)sv.error=5;
