// Public YouTube IFrame API demo clip. No extraction, credentials, ads or transport overrides.
var yt, videoId='M7lc1UVf-VE';
function onYouTubeIframeAPIReady(){
  yt=new YT.Player('player',{width:'100%',height:'100%',videoId:videoId,
    playerVars:{playsinline:1,controls:1,origin:location.origin},
    events:{
      onReady:function(){sv.ready=true;kick=function(){yt.mute();yt.playVideo();};kick();},
      onStateChange:function(e){sv.state=e.data;if(e.data===1)clearGesture();},
      onAutoplayBlocked:askForGesture,
      onError:function(e){sv.error=Number(e.data)||-1;}
    }});
  readMedia=function(){
    if(sv.ready){sv.state=yt.getPlayerState();sv.pos=yt.getCurrentTime();}
  };
}
var api=document.createElement('script');api.src='https://www.youtube.com/iframe_api';
document.head.appendChild(api);
