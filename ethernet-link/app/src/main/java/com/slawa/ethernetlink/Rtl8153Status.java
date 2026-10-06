package com.slawa.ethernetlink;

/** RTL8153 PLA_PHYSTATUS protocol, independently implemented from Linux UAPI facts.
 * Linux v6.6 r8152.c: rtl8152_get_speed -> ocp_read_word; r8152.h constants.
 * Only one read request is exposed. There is no register-write or reset API.
 */
final class Rtl8153Status {
    static final int REQUEST_TYPE = 0xc0; // IN | VENDOR | DEVICE; endpoint zero
    static final int REQUEST = 0x05;
    static final int REGISTER = 0xe908; // PLA_PHYSTATUS; already dword-aligned
    static final int INDEX = 0x0133; // MCU_TYPE_PLA | BYTE_EN_WORD
    static final int LENGTH = 4, TIMEOUT_MS = 500;
    interface Transfer {
        int read(int requestType, int request, int value, int index, byte[] buffer, int length, int timeout);
    }
    static final class Sample {
        final int link, speed, duplex;
        final String error;
        Sample(int link,int speed,int duplex,String error) {
            this.link=link; this.speed=speed; this.duplex=duplex; this.error=error;
        }
    }
    static Sample decode(byte[] bytes,int count) {
        return decode(bytes,count,1000);
    }
    static Sample decode(byte[] bytes,int count,int maxMbps) {
        if (count != LENGTH || bytes == null || bytes.length < LENGTH)
            return unknown("incomplete transfer: "+count);
        int status=(bytes[0]&0xff)|((bytes[1]&0xff)<<8);
        if (status==0xffff) return unknown("invalid status 0xffff");
        if ((status&0x02)==0) return new Sample(0,-1,-1,"");
        int bits=status&0x041c;
        if (Integer.bitCount(bits)!=1 || (status&0x5300)!=0)
            return new Sample(1,-1,-1,"ambiguous or unsupported speed bits");
        int speed=bits==0x400?2500:bits==0x10?1000:bits==0x08?100:10;
        if(speed>maxMbps)return new Sample(1,-1,-1,"speed exceeds adapter family");
        return new Sample(1,speed,(status&1)!=0?1:0,"");
    }
    static Sample read(Transfer transfer,StringBuilder report) {
        return read(transfer,report,1000);
    }
    static Sample read(Transfer transfer,StringBuilder report,int maxMbps) {
        byte[] first=new byte[LENGTH], second=new byte[LENGTH];
        int n1=transfer.read(REQUEST_TYPE,REQUEST,REGISTER,INDEX,first,LENGTH,TIMEOUT_MS);
        int n2=transfer.read(REQUEST_TYPE,REQUEST,REGISTER,INDEX,second,LENGTH,TIMEOUT_MS);
        report.append("usb.request=IN/VENDOR/DEVICE request=0x05 value=0xe908 index=0x0133 length=4\n");
        append(report,"usb.first",first,n1);append(report,"usb.second",second,n2);
        Sample a=decode(first,n1,maxMbps), b=decode(second,n2,maxMbps);
        Sample result=combine(a,b);
        report.append("usb.link=").append(result.link).append("\nusb.speed=").append(result.speed)
              .append("\nusb.duplex=").append(result.duplex).append("\nusb.error=").append(result.error).append('\n');
        return result;
    }
    static Sample combine(Sample a,Sample b) {
        if (a.link<0 || b.link<0) return unknown(!a.error.isEmpty()?a.error:b.error);
        if (a.link==0 || b.link==0) return new Sample(0,-1,-1,a.link==b.link?"":"link changed during sampling");
        if (a.speed<0 || b.speed<0 || a.speed!=b.speed || a.duplex!=b.duplex)
            return new Sample(1,-1,-1,"speed not stable or not decoded");
        return new Sample(1,a.speed,a.duplex,"");
    }
    private static Sample unknown(String error){return new Sample(-1,-1,-1,error);}
    private static void append(StringBuilder out,String key,byte[] bytes,int count){
        out.append(key).append(".bytes=").append(count);
        if(count==LENGTH){out.append(" raw=");for(byte b:bytes)out.append(String.format(java.util.Locale.US,"%02x",b&0xff));}
        out.append('\n');
    }
}
