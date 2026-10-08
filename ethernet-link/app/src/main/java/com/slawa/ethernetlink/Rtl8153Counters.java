package com.slawa.ethernetlink;

import java.util.Arrays;

/** RTL8153 PLA_TALLYCNT layout from Linux v6.12 r8152.c, get_ethtool_stats.
 * Endpoint-zero IN only; no claim, latch write, reset, PHY-page write or CRC guess.
 */
final class Rtl8153Counters {
    static final int RX_ERRORS=0,ALIGN_ERRORS=1,RX_MISSED=2,CRC_FCS=3,RX_PACKETS=4,TX_PACKETS=5,COUNT=6;
    static final int REGISTER=0xe890,INDEX=0x0100,LENGTH=64;
    static long[] unavailable(){long[] values=new long[COUNT];Arrays.fill(values,-1);return values;}
    static long[] read(Rtl8153Status.Transfer transfer,StringBuilder log){
        byte[] bytes=new byte[LENGTH];
        int n=transfer.read(0xc0,0x05,REGISTER,INDEX,bytes,LENGTH,500);
        log.append("tally.request=IN/VENDOR/DEVICE request=0x05 value=0xe890 index=0x0100 length=64\n");
        log.append("tally.bytes=").append(n).append('\n');
        if(n==LENGTH){log.append("tally.raw=");for(byte b:bytes)log.append(String.format(java.util.Locale.US,"%02x",b&255));log.append('\n');}
        long[] values=decode(bytes,n);log.append("tally.values=").append(Arrays.toString(values)).append("\ntally.crc=not_exposed\n");return values;
    }
    static long[] decode(byte[] bytes,int count){
        long[] values=unavailable();if(bytes==null||count!=LENGTH||bytes.length<LENGTH)return values;
        boolean allFF=true;for(int i=0;i<LENGTH;i++)if((bytes[i]&255)!=255){allFF=false;break;}if(allFF)return values;
        values[RX_ERRORS]=unsigned(bytes,24,4);values[ALIGN_ERRORS]=unsigned(bytes,30,2);values[RX_MISSED]=unsigned(bytes,28,2);
        values[RX_PACKETS]=unsigned(bytes,8,8);values[TX_PACKETS]=unsigned(bytes,0,8);
        // No separate CRC/FCS counter exists in this hardware tally block.
        return values;
    }
    private static long unsigned(byte[] bytes,int offset,int size){
        // Unsigned packet totals above Long.MAX_VALUE remain unavailable rather than negative.
        if(size==8&&(bytes[offset+7]&128)!=0)return -1;
        long value=0;for(int i=0;i<size;i++)value|=(long)(bytes[offset+i]&255)<<(8*i);return value;
    }
}
