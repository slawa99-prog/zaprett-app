
# SV Pocket: optional network readiness check for the reviewed Pocket v71/v72.
# This suffix is self-contained at boot; it does not call into the Android app.
sv_pocket_uptime() (
    IFS=' ' read -r t rest < /proc/uptime || exit 1
    t=${t%%.*}; case "$t" in ''|*[!0-9]*) exit 1;; esac
    printf '%s\n' "$t"
)

sv_pocket_route() (
    # Ask the kernel which route it would use. This sends no packets and uses
    # policy routing instead of accepting an arbitrary default in another table.
    for family in 4 6; do
        case "$family" in 4) dst=1.1.1.1;; 6) dst=2606:4700:4700::1111;; esac
        route=$(ip "-$family" route get "$dst" 2>/dev/null) || continue
        key=$(printf '%s\n' "$route" | awk -v dst="$dst" -v family="$family" '
            NR==1 && $1==dst {
                for (i=2;i<=NF;i++) {
                    if ($i=="dev") dev=$(i+1)
                    if ($i=="src") src=$(i+1)
                    if ($i=="linkdown") exit
                }
                if (dev !~ /^(eth[0-9]+|wlan[0-9]+|en[a-zA-Z0-9._-]+|wl[a-zA-Z0-9._-]+)$/) exit
                if (family==4 && (src !~ /^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$/ || src ~ /^(0\.|127\.|169\.254\.)/)) exit
                if (family==6 && (src !~ /:/ || src=="::" || src=="::1" || tolower(src) ~ /^fe[89ab]/)) exit
                print dev, src
            }')
        [ -n "$key" ] || continue
        link=$(ip link show dev "${key%% *}" 2>/dev/null) || continue
        flags=${link#*<}; flags=${flags%%>*}
        case ",$flags," in *,UP,*) ;; *) continue;; esac
        case ",$flags," in *,LOWER_UP,*) printf '%s\n' "$key"; exit 0;; esac
    done
    exit 1
)

sv_pocket_wait_note() (
    # No interface name, address, or network identifiers in the shared report.
    [ -d "$SERVICE_DIR" ] || exit 0
    umask 077
    IFS= read -r boot < /proc/sys/kernel/random/boot_id || exit 0
    tmp=$(mktemp "$SERVICE_DIR/.sv-wait.XXXXXX") || exit 0
    if ! printf 'boot_id=%s method=%s elapsed_s=%s\n' "$boot" "$1" "$2" > "$tmp" ||
        ! mv -f "$tmp" "$SERVICE_DIR/sv-network-wait.log"; then rm -f "$tmp"; fi
)

wait_for_internet() (
    start=$(sv_pocket_uptime) || start=''
    elapsed=0; attempt=0; previous=''; reason=deadline
    sv_pocket_wait_note waiting 0
    while [ "$elapsed" -lt 120 ]; do
        key=$(sv_pocket_route) || key=''
        if [ -n "$key" ]; then
            # A second observation avoids treating a transient boot route as ready.
            if [ "$key" = "$previous" ]; then reason=local_route; break; fi
            previous=$key
        else
            previous=''
            # Keep the original external reachability test as a fallback on
            # devices that cannot expose a usable local route; rotate endpoints.
            case $((attempt % 3)) in 0) dst=1.1.1.1;; 1) dst=8.8.8.8;; 2) dst=9.9.9.9;; esac
            attempt=$((attempt+1)); remain=$((120-elapsed)); [ "$remain" -le 2 ] || remain=2
            if timeout "$remain" nc "$dst" 443 </dev/null >/dev/null 2>&1; then reason=tcp; break; fi
        fi
        now=$(sv_pocket_uptime) || now=''
        if [ -n "$start" ] && [ -n "$now" ] && [ $((now-start)) -ge 120 ]; then break; fi
        sleep 1
        now=$(sv_pocket_uptime) || now=''
        if [ -n "$start" ] && [ -n "$now" ]; then elapsed=$((now-start))
        else elapsed=$((elapsed+1)); fi
    done
    now=$(sv_pocket_uptime) || now=''
    if [ -n "$start" ] && [ -n "$now" ]; then elapsed=$((now-start)); fi
    sv_pocket_wait_note "$reason" "$elapsed"
    # Stock Pocket also continues after its wait budget. This does not declare
    # the service healthy: supervisor startup and readiness checks still follow.
    exit 0
)
