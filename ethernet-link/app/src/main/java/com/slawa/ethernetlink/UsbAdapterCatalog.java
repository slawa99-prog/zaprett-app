package com.slawa.ethernetlink;

/** Exact USB IDs from Linux v6.12 r8152.c and ax88179_178a.c.
 * No product-name guesses and no requests to unknown devices.
 */
final class UsbAdapterCatalog {
    enum Kind {
        REALTEK_FE("Realtek RTL8152",100),
        REALTEK_GBE("Realtek RTL8153",1000),
        REALTEK_2G5("Realtek RTL8156",2500),
        REALTEK_OEM("Realtek USB Ethernet",2500),
        ASIX("ASIX AX88179 / AX88178A",1000);
        final String label;
        final int maxMbps;
        Kind(String label,int maxMbps){this.label=label;this.maxMbps=maxMbps;}
    }
    // Each row is also declared in res/xml/usb_devices.xml for Android defaults.
    static final int[][] REALTEK_IDS={
        {0x0bda,0x8050},{0x0bda,0x8053},{0x0bda,0x8152},{0x0bda,0x8153},
        {0x0bda,0x8155},{0x0bda,0x8156},
        {0x045e,0x07ab},{0x045e,0x07c6},{0x045e,0x0927},{0x045e,0x0c5e},
        {0x04e8,0xa101},
        {0x17ef,0x304f},{0x17ef,0x3054},{0x17ef,0x3062},{0x17ef,0x3069},
        {0x17ef,0x3082},{0x17ef,0x3098},{0x17ef,0x7205},{0x17ef,0x720c},
        {0x17ef,0x7214},{0x17ef,0x721e},{0x17ef,0xa387},
        {0x13b1,0x0041},{0x0955,0x09ff},{0x2357,0x0601},{0x2001,0xb301},
        {0x0b05,0x1976}
    };
    static final int[][] ASIX_IDS={
        {0x0b95,0x1790},{0x0b95,0x178a},{0x04b4,0x3610},{0x2001,0x4a00},
        {0x0df6,0x0072},{0x04e8,0xa100},{0x17ef,0x304b},{0x050d,0x0128},
        {0x0930,0x0a13},{0x0711,0x0179},{0x07c9,0x000e},{0x07c9,0x000f},
        {0x07c9,0x0010}
    };
    static Kind find(int vid,int pid){
        for(int[] id:REALTEK_IDS)if(id[0]==vid&&id[1]==pid){
            if(vid==0x0bda){
                if(pid==0x8152)return Kind.REALTEK_FE;
                if(pid==0x8153)return Kind.REALTEK_GBE;
                if(pid==0x8156)return Kind.REALTEK_2G5;
            }
            return Kind.REALTEK_OEM;
        }
        for(int[] id:ASIX_IDS)if(id[0]==vid&&id[1]==pid)return Kind.ASIX;
        return null;
    }
}
