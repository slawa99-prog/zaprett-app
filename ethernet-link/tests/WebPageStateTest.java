package com.slawa.ethernetlink;

public final class WebPageStateTest {
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    public static void main(String[] args){
        String home="https://yandex.ru/internet/";WebPageState state=new WebPageState();state.started(home);
        check(!state.sslFailed("https://ads.example.org/pixel"),"an ad certificate cannot obscure the main page");
        check(!state.sslFailed("https://yandex.ru/ads/script.js"),"same-host resource is still not the main document");
        check(!state.failed()&&state.committed(home),"main page succeeds despite rejected resources");
        check(!state.sslFailed(home),"same-URL subframe request after commit cannot replace healthy main page");
        state.started(home);check(state.sslFailed(home),"invalid main certificate shows failure");
        check(!state.committed(home),"commit/finish on an error document must not erase TLS failure");
        state.started(home);check(!state.failed()&&state.committed(home),"reload clears the previous navigation error");
        state.started("https://yandex.com/internet/");check(!state.fail(home),"late callback from previous document ignored");
        check(state.sslFailed("https://yandex.com/internet/"),"redirected main-document certificate is checked");
        check(WebPageState.sameDocument("https://YANDEX.ru:443/internet/#a",home),"default HTTPS port and fragment normalized");
        check(!WebPageState.sameDocument(home,home+"?ad=1"),"query is part of request identity");
        check(!WebPageState.sameDocument(home,"http://yandex.ru/internet/"),"scheme matters");
        check(!WebPageState.sameDocument(home,null)&&!WebPageState.sameDocument(home,"%%%"),"malformed URLs do not crash");
        state.started("https://speedtest.ufanet.ru/");state.started(SpeedtestSite.HOME);
        check(!state.fail("https://speedtest.ufanet.ru/"),"old HTTPS callback after Ufanet redirect ignored");
        check(state.committed(SpeedtestSite.HOME),"Ufanet HTTP page commits normally");
        check(!state.sslFailed("https://cdn.example.org/script.js"),"HTTPS subresource does not cover HTTP speedtest");
        check(WebPageState.sameDocument("http://SPEEDTEST.UFANET.ru:80/#result",SpeedtestSite.HOME),"default HTTP port and fragment normalized");
        System.out.println("WebView main-document errors, subresources, redirects and reload state passed");
    }
}
