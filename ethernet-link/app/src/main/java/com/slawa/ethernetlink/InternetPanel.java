package com.slawa.ethernetlink;

import android.app.Activity;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.net.*;
import android.net.http.SslError;
import android.os.Handler;
import android.os.Looper;
import android.view.*;
import android.webkit.*;
import android.widget.*;
import androidx.webkit.ProxyConfig;
import androidx.webkit.ProxyController;
import androidx.webkit.WebViewFeature;
import java.io.IOException;
import java.nio.channels.SocketChannel;
import java.util.ArrayDeque;

/** The official Yandex page, wholly inside the app, with Ethernet-only HTTPS. */
final class InternetPanel {
    private static final String HOME="https://yandex.ru/internet/";
    private final Activity activity;
    private final ConnectivityManager cm;
    private final Handler handler=new Handler(Looper.getMainLooper());
    final LinearLayout view;
    private final TextView status,message;
    private final ProgressBar progress;
    private final FrameLayout content;
    private WebView web;
    private BoundProxy proxy;
    private RacingConnector connector;
    private Network network;
    private boolean active;
    private int generation;
    private String lastError="";
    private String lastSession="",webViewVersion="unknown";
    private final ArrayDeque<String> webErrors=new ArrayDeque<>();

    InternetPanel(Activity activity){
        this.activity=activity;cm=(ConnectivityManager)activity.getSystemService(Activity.CONNECTIVITY_SERVICE);
        view=new LinearLayout(activity);view.setOrientation(LinearLayout.VERTICAL);
        LinearLayout toolbar=new LinearLayout(activity);toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(12),0,dp(8),0);
        status=new TextView(activity);status.setTextSize(13);status.setTextColor(0xff475569);
        toolbar.addView(status,new LinearLayout.LayoutParams(0,-2,1));
        Button reload=new Button(activity);reload.setText("Обновить");reload.setAllCaps(false);
        reload.setOnClickListener(v->{stop();if(active)start();});
        toolbar.addView(reload,new LinearLayout.LayoutParams(-2,dp(48)));view.addView(toolbar);
        progress=new ProgressBar(activity,null,android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);progress.setVisibility(View.GONE);view.addView(progress,new LinearLayout.LayoutParams(-1,dp(3)));
        content=new FrameLayout(activity);content.setBackgroundColor(Color.WHITE);
        view.addView(content,new LinearLayout.LayoutParams(-1,0,1));
        message=new TextView(activity);message.setTextSize(16);message.setTextColor(0xff64748b);
        message.setGravity(Gravity.CENTER);message.setPadding(dp(24),dp(24),dp(24),dp(24));
        content.addView(message,new FrameLayout.LayoutParams(-1,-1));
        status.setText("Яндекс Интернетометр · через Ethernet");
        message.setText("Подключи Ethernet с доступом в интернет.");
    }
    void setActive(boolean value){
        if(active==value)return;active=value;
        if(value)start();else stop();
    }
    void networksChanged(){
        if(!active)return;
        Network candidate=selectNetwork();
        if(network!=null&&!network.equals(candidate)){stop();message.setText("Ethernet отключён или изменился. Замер остановлен.");}
        if(network==null)start();
    }
    private Network selectNetwork(){
        Network selected=null;
        try{
            for(Network n:cm.getAllNetworks()){
                NetworkCapabilities c=cm.getNetworkCapabilities(n);
                if(c==null)continue;
                if(c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)){
                    lastError="Выключи VPN, чтобы измерить скорость Ethernet напрямую.";return null;
                }
                if(c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)&&c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)){
                    if(selected!=null){lastError="Оставь одно Ethernet-подключение для замера.";return null;}selected=n;
                }
            }
        }catch(RuntimeException e){lastError="Не удалось проверить Ethernet-подключение.";return null;}
        if(selected==null)lastError="Подключи Ethernet с доступом в интернет. После подключения дождись получения IP-адреса.";
        if(selected!=null){
            LinkProperties props=cm.getLinkProperties(selected);
            boolean address=false,route=false;
            if(props!=null){
                for(LinkAddress a:props.getLinkAddresses())if(!a.getAddress().isLinkLocalAddress()&&!a.getAddress().isAnyLocalAddress())address=true;
                for(RouteInfo r:props.getRoutes())if(r.isDefaultRoute())route=true;
            }
            if(!address||!route||props.getDnsServers().isEmpty()){
                lastError="Ethernet подключён. Ожидаю IP-адрес, шлюз и DNS…";return null;
            }
        }
        return selected;
    }
    private void start(){
        if(!active||web!=null||network!=null)return;
        Network selected=selectNetwork();
        if(selected==null){message.setText(lastError);message.setVisibility(View.VISIBLE);status.setText("Интернетометр · ожидание Ethernet");return;}
        final int token=++generation;
        try{
            if(!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)){
                message.setText("Обнови Android System WebView: текущая версия не позволяет закрепить замер за Ethernet.");return;
            }
            network=selected;
            // Both DNS and every outgoing socket belong to this exact Network.
            connector=new RacingConnector(selected::getAllByName,()->{
                NetworkCapabilities caps=cm.getNetworkCapabilities(selected);
                if(caps==null||!caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)||caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
                    throw new IOException("Ethernet unavailable");
                SocketChannel channel=SocketChannel.open();
                try{selected.bindSocket(channel.socket());return channel;}
                catch(IOException|RuntimeException e){channel.close();throw e;}
            });
            proxy=new BoundProxy(connector);
            lastSession="";webErrors.clear();
            ProxyConfig config=new ProxyConfig.Builder().addProxyRule("http://127.0.0.1:"+proxy.port())
                    .removeImplicitRules().build();
            message.setText("Подключаю Интернетометр через Ethernet…");message.setVisibility(View.VISIBLE);
            ProxyController.getInstance().setProxyOverride(config,r->handler.post(r),()->{
                if(!active||token!=generation||network==null)return;
                // Do not create or load WebView before the routing override is applied.
                if(!selected.equals(selectNetwork())){stop();start();return;}
                createWebView(token);
            });
        }catch(RuntimeException|IOException e){
            stop();lastError=e.getClass().getSimpleName();message.setText("Не удалось открыть Интернетометр. Нажми «Обновить». Проверь Android System WebView.");
        }
    }
    private void createWebView(int token){
        try{
            web=new WebView(activity);
            PackageInfo provider=WebView.getCurrentWebViewPackage();
            if(provider!=null)webViewVersion=provider.packageName+" "+provider.versionName;
            WebSettings settings=web.getSettings();settings.setJavaScriptEnabled(true);settings.setDomStorageEnabled(true);
            settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
            settings.setGeolocationEnabled(false);settings.setJavaScriptCanOpenWindowsAutomatically(false);
            settings.setSupportMultipleWindows(false);settings.setMediaPlaybackRequiresUserGesture(true);
            settings.setCacheMode(WebSettings.LOAD_DEFAULT);
            CookieManager.getInstance().setAcceptThirdPartyCookies(web,false);
            web.setWebViewClient(new WebViewClient(){
                @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest request){
                    // No ACTION_VIEW, external browser, app intent or native JavaScript bridge.
                    if("https".equals(request.getUrl().getScheme()))return false;
                    Toast.makeText(activity,"Эта ссылка не поддерживается внутри Интернетометра",Toast.LENGTH_SHORT).show();return true;
                }
                @Override public void onPageFinished(WebView v,String url){
                    if(token!=generation)return;progress.setVisibility(View.GONE);
                }
                @Override public void onReceivedError(WebView v,WebResourceRequest req,WebResourceError error){
                    if(token!=generation)return;
                    recordError("web.error="+error.getErrorCode()+" host="+req.getUrl().getHost()+" main="+req.isForMainFrame());
                    if(!req.isForMainFrame())return;
                    lastError="web.error="+error.getErrorCode();
                    message.setText("Страница не загрузилась. Проверь интернет по кабелю и нажми «Обновить».");message.setVisibility(View.VISIBLE);
                    progress.setVisibility(View.GONE);
                }
                @Override public void onReceivedHttpError(WebView v,WebResourceRequest req,WebResourceResponse response){
                    if(token==generation)recordError("http.status="+response.getStatusCode()+" host="+req.getUrl().getHost());
                }
                @Override public void onReceivedSslError(WebView v,SslErrorHandler h,SslError error){
                    h.cancel();if(token!=generation)return;
                    lastError="TLS validation failed";
                    message.setText("Не удалось проверить защищённое соединение с сайтом. Проверь дату и интернет.");message.setVisibility(View.VISIBLE);
                }
                @Override public boolean onRenderProcessGone(WebView v,RenderProcessGoneDetail detail){
                    if(token==generation){stop();message.setText("Интернетометр был закрыт системой. Нажми «Обновить».");}return true;
                }
            });
            web.setWebChromeClient(new WebChromeClient(){
                @Override public void onProgressChanged(WebView v,int value){if(token==generation){progress.setProgress(value);progress.setVisibility(value<100?View.VISIBLE:View.GONE);}}
                @Override public void onPermissionRequest(PermissionRequest request){request.deny();}
            });
            content.addView(web,0,new FrameLayout.LayoutParams(-1,-1));message.setVisibility(View.GONE);
            LinkProperties props=cm.getLinkProperties(network);
            status.setText("Яндекс · Ethernet"+(props!=null&&props.getInterfaceName()!=null?" · "+props.getInterfaceName():""));
            lastError="";web.loadUrl(HOME);
        }catch(RuntimeException e){stop();lastError=e.getClass().getSimpleName();message.setText("Не удалось запустить встроенный Интернетометр. Обнови Android System WebView.");}
    }
    private void stop(){
        generation++;network=null;
        if(connector!=null)connector.close();
        if(proxy!=null){proxy.close();lastSession=proxy.report()+(connector!=null?connector.report():"");proxy=null;}
        connector=null;
        if(web!=null){WebView old=web;web=null;old.stopLoading();content.removeView(old);old.destroy();}
        // Keep the now-closed proxy override. Late WebView requests must fail closed.
        // The next session installs a fresh override before loading any remote page.
        progress.setVisibility(View.GONE);message.setVisibility(View.VISIBLE);
    }
    boolean back(){if(web!=null&&web.canGoBack()){web.goBack();return true;}return false;}
    private void recordError(String error){if(webErrors.size()==12)webErrors.removeFirst();webErrors.addLast(error);}
    String report(){
        String session=proxy!=null?proxy.report()+(connector!=null?connector.report():""):lastSession;
        StringBuilder out=new StringBuilder("\n[Internetometer]\nactive="+active+"\nethernet_bound="+(network!=null)+
            "\nwebview="+webViewVersion+"\nerror="+lastError+"\n").append(session);
        for(String error:webErrors)out.append(error).append('\n');
        return out.toString();
    }
    private int dp(int n){return Math.round(n*activity.getResources().getDisplayMetrics().density);}
}
