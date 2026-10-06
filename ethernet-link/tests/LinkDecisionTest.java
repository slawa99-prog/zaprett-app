package com.slawa.ethernetlink;
public final class LinkDecisionTest {
    private static void check(boolean ok,String name){if(!ok)throw new AssertionError(name);}
    public static void main(String[] args){
        for(int s:new int[]{10,100,1000,2500,10000})check(LinkDecision.resolve(new int[]{1,1},false,new int[]{s,s}).speed==s,"exact "+s);
        check(LinkDecision.resolve(new int[]{-1,-1},true,new int[]{-1}).speed==-1,"Android network is not a speed");
        check(LinkDecision.resolve(new int[]{1,0},true,new int[]{1000}).speed==-1,"unplug during sampling");
        check(LinkDecision.resolve(new int[]{0},true,new int[]{1000}).link==LinkDecision.DOWN,"down beats stale network");
        check(LinkDecision.resolve(new int[]{-1},false,new int[]{1000}).speed==-1,"cached speed without carrier");
        check(LinkDecision.resolve(new int[]{1},false,new int[]{100,1000}).conflict,"renegotiation conflict");
        check(LinkDecision.resolve(new int[]{1},false,new int[]{100,1000}).speed==-1,"conflict hides number");
        check(LinkDecision.resolve(new int[]{-1},false,new int[]{-1}).link==LinkDecision.UNKNOWN,"unknown differs from down");
        check(LinkDecision.resolve(new int[]{0},false,new int[]{-1}).speed==-1,"disconnected");
        check(LinkDecision.resolve(new int[]{1},false,new int[]{1000}).speed==1000,"offline without DHCP");
        for(String v:new String[]{null,"","-1","0","65535","4294967295","2147483648","10000000000","oops"})check(LinkDecision.speed(v)==-1,"invalid sentinel "+v);
        check(LinkDecision.speed("1000")==1000,"parse 1000");
        System.out.println("Link decision regression checks passed");
    }
}
