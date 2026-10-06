package com.slawa.ethernetlink;

/** Read-only AX88179/AX88178A PHY status; Linux v6.12 ax88179_178a.c.
 * No PHY-page writes, software-MII switches, interface claims or resets.
 */
final class AsixStatus {
    static Rtl8153Status.Sample decode(byte[] bytes,int count) {
        if(count!=2 || bytes==null || bytes.length<2)
            return new Rtl8153Status.Sample(-1,-1,-1,"incomplete ASIX transfer: "+count);
        int status=(bytes[0]&255)|((bytes[1]&255)<<8);
        if(status==0xffff)return new Rtl8153Status.Sample(-1,-1,-1,"invalid PHY status 0xffff");
        if((status&0x0400)==0)return new Rtl8153Status.Sample(0,-1,-1,"");
        int mode=status&0xc000;
        if(mode==0xc000)return new Rtl8153Status.Sample(1,-1,-1,"reserved ASIX speed bits");
        return new Rtl8153Status.Sample(1,mode==0x8000?1000:mode==0x4000?100:10,
                (status&0x2000)!=0?1:0,"");
    }
    static Rtl8153Status.Sample read(Rtl8153Status.Transfer transfer,StringBuilder out) {
        int pageBefore=page(transfer);
        if(pageBefore!=0){out.append("usb.phy_page_before=").append(pageBefore).append('\n');return new Rtl8153Status.Sample(-1,-1,-1,"PHY page is not zero or unreadable");}
        byte[] a=new byte[2],b=new byte[2];
        int n=transfer.read(0xc0,0x02,0x0003,0x0011,a,2,500);
        int m=transfer.read(0xc0,0x02,0x0003,0x0011,b,2,500);
        int pageAfter=page(transfer);
        out.append("usb.phy_page_before=").append(pageBefore).append("\nusb.phy_page_after=").append(pageAfter).append('\n');
        out.append("usb.request=IN/VENDOR/DEVICE request=0x02 value=0x0003 index=0x0011 length=2\n");
        out.append("usb.first.bytes=").append(n).append(" raw=").append(hex(a,n)).append('\n');
        out.append("usb.second.bytes=").append(m).append(" raw=").append(hex(b,m)).append('\n');
        Rtl8153Status.Sample r=pageAfter==0?Rtl8153Status.combine(decode(a,n),decode(b,m))
            :new Rtl8153Status.Sample(-1,-1,-1,"PHY page changed during sampling");
        out.append("usb.link=").append(r.link).append("\nusb.speed=").append(r.speed)
           .append("\nusb.duplex=").append(r.duplex).append("\nusb.error=").append(r.error).append('\n');
        return r;
    }
    private static int page(Rtl8153Status.Transfer transfer){
        byte[] b=new byte[2];int n=transfer.read(0xc0,0x02,3,0x1f,b,2,500);
        return n==2?(b[0]&255)|((b[1]&255)<<8):-1;
    }
    private static String hex(byte[] b,int n){return n==2?String.format(java.util.Locale.US,"%02x%02x",b[0]&255,b[1]&255):"";}
}
