package com.slawa.ethernetlink;

/** Pure decision logic. Estimated Android bandwidth is deliberately not an input. */
final class LinkDecision {
    static final int UNKNOWN = -1, DOWN = 0, UP = 1;
    final int link, speed;
    final boolean conflict;
    private LinkDecision(int link, int speed, boolean conflict) {
        this.link = link; this.speed = speed; this.conflict = conflict;
    }
    static int speed(String raw) {
        try {
            long n = Long.parseLong(raw.trim());
            return n > 0 && n != 65535 && n <= Integer.MAX_VALUE ? (int)n : -1;
        } catch (RuntimeException e) { return -1; }
    }
    static LinkDecision resolve(int[] carrierSamples, boolean androidNetwork, int[] exactSpeeds) {
        boolean up = false, down = false;
        for (int c : carrierSamples) { if (c == UP) up = true; if (c == DOWN) down = true; }
        // An explicit down wins over speed cached in the driver and Android metadata.
        int link = down ? DOWN : (up || androidNetwork ? UP : UNKNOWN);
        int speed = -1;
        boolean conflict = false;
        for (int s : exactSpeeds) {
            if (s <= 0 || s == 65535) continue;
            if (speed > 0 && speed != s) conflict = true;
            speed = s;
        }
        return new LinkDecision(link, link == UP && !conflict ? speed : -1, conflict);
    }
}
