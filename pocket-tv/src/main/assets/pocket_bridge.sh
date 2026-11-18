#!/system/bin/sh
# Except for the reversible fast-start setting on known modules, SV Pocket
# writes its own state under BASE. Pocket owns all network/firewall operations.
umask 077
BASE="${POCKET_TV_BASE:-/data/adb/pocket-tv}"
MOD="${POCKET_TV_MODULE:-/data/adb/modules/zapret}"
CLI="$MOD/system/bin/zapret"
SESSION="$BASE/session"
LOCK="$BASE/action.lock"
SHELL_BIN="${POCKET_TV_SHELL:-/system/bin/sh}"
RUNTIME="$BASE/runtime"
UPDATE="$BASE/update"
STAGING="${POCKET_TV_STAGING:-/data/adb/modules_update/zapret}"
MAGISK_BIN="${POCKET_TV_MAGISK:-magisk}"
[ ! -x "$RUNTIME/curl" ] || export PATH="$RUNTIME:$PATH"

fail() { printf '%s\n' "$*" >&2; exit 1; }
# Distinguish genuine contention from denied access, unsupported files and write errors.
busy_module() { printf '%s\n' "$*" >&2; exit 75; }
field() { printf '@@%s %s\n' "$1" "$2"; }
phase() { printf '@@PHASE %s\n' "$1" >&2; }
value() { cat "$1" 2>/dev/null; }
boot_id() { cat /proc/sys/kernel/random/boot_id; }
start_token() { sed 's/.*) //' "/proc/$1/stat" 2>/dev/null | awk '{print $20}'; }
identity() { printf '%s %s %s\n' "$1" "$(start_token "$1")" "$(boot_id)"; }
alive() {
    [ -f "$1" ] || return 1
    read -r apid atoken aboot < "$1"
    case "$apid" in ''|*[!0-9]*) return 1 ;; esac
    [ "$aboot" = "$(boot_id)" ] && [ -n "$atoken" ] &&
        [ "$atoken" = "$(start_token "$apid")" ] &&
        [ "$(sed 's/.*) //' "/proc/$apid/stat" 2>/dev/null | awk '{print $1}')" != Z ] &&
        kill -0 "$apid" 2>/dev/null
}
write_state() { printf '%s\n' "$1" > "$SESSION/state.tmp"; mv "$SESSION/state.tmp" "$SESSION/state"; }
active() {
    case "$(value "$SESSION/state")" in starting|running|restoring|cancelling) alive "$SESSION/owner" ;; *) return 1 ;; esac
}
require_module() {
    [ -f "$MOD/module.prop" ] && [ -f "$CLI" ] || fail 'Не найден Zapret Pocket. Установите модуль sevcator через Magisk и перезагрузите приставку.'
    grep -qi 'author=.*sevcator' "$MOD/module.prop" || fail 'В каталоге zapret установлен другой модуль. Нужен Zapret Pocket от sevcator.'
    [ ! -e "$MOD/disable" ] && [ ! -e "$MOD/remove" ] || fail 'Модуль Pocket отключён или ожидает удаления в Magisk. Включите его и перезагрузите приставку.'
}
acquire() {
    mkdir -p "$BASE" || fail 'Не удалось создать каталог SV Pocket.'
    if ! mkdir "$LOCK" 2>/dev/null; then
        if alive "$LOCK/owner"; then busy_module 'Предыдущая операция ещё выполняется. Дождитесь её завершения.'; fi
        # A missing owner may be the brief interval between mkdir and writing it.
        sleep 1
        alive "$LOCK/owner" && busy_module 'Предыдущая операция ещё выполняется.'
        rm -rf "$LOCK"
        mkdir "$LOCK" 2>/dev/null || busy_module 'Каталог управления занят.'
    fi
    identity "$$" > "$LOCK/owner"
    trap 'rm -rf "$LOCK"' EXIT
}
idle_required() {
    update_in_progress && busy_module 'Обновление ещё устанавливается. Дождитесь завершения.'
    [ ! -f "$STAGING/module.prop" ] || busy_module 'Обновление установлено. Сначала перезагрузите приставку.'
    active && busy_module 'Сначала остановите подбор и дождитесь восстановления сервиса.'
    # A Magisk boot/start or a tester opened outside the app may not own our lock.
    # Only match the actual script and its first argument, never a shell -c string.
    snapshot=$(ps -A -o PID,ARGS 2>/dev/null) || fail 'Не удалось проверить, не запускается ли уже модуль.'
    pending=$(printf '%s\n' "$snapshot" | awk -v cli="$CLI" -v boot="$MOD/service.sh" '
        $1 ~ /^[0-9]+$/ {
            i=2
            if ($i ~ /(^|\/)(busybox|toybox)$/ && $(i+1) ~ /^(ash|sh)$/) i++
            if ($i ~ /(^|\/)(sh|ash|bash|mksh)$/) i++
            if ($i==cli && $(i+1)=="test") testing=1
            if ($i==boot || ($i==cli && ($(i+1)=="start" || $(i+1)=="restart"))) starting=1
        }
        END { if (testing) print "test"; else if (starting) print "start" }')
    case "$pending" in
        test) busy_module 'Тестер Pocket уже запущен из терминала или WebUI. Завершите его перед этой операцией.' ;;
        start) busy_module 'Модуль уже запускается. Дождитесь запуска и обновите состояние.' ;;
    esac
}
stock_common=24498c2653ba009e6b090418d6d4f788650ef156d4a935332e38b8e375b41e36
fast_common=368bc0d99c816790251048c74bd2d1782534af42267e4d629a3b062021a47a44
file_hash() { sha256sum "$1" 2>/dev/null | awk '{print $1}'; }
fast_start_mode() {
    case "$(version_code "$MOD")" in 71|72) ;; *) echo unsupported; return;; esac
    if [ ! -f "$MOD/common.sh" ] || [ -L "$MOD/common.sh" ]; then echo unsupported; return; fi
    case "$(file_hash "$MOD/common.sh")" in
        "$stock_common") echo off;; "$fast_common") echo on;; *) echo unsupported;;
    esac
}
set_fast_start() (
    # The original file remains byte-for-byte as the prefix. Both directions
    # verify the entire input and output. An upstream update never gets rolled back.
    case "$1" in on|off) ;; *) fail 'Неизвестный режим запуска.';; esac
    mode=$(fast_start_mode)
    [ "$mode" != "$1" ] || exit 0
    [ "$mode" != unsupported ] || fail 'Быстрый запуск доступен только для оригинального Pocket 71 или 72. Файлы не изменены.'
    common="$MOD/common.sh"
    tmp=$(mktemp "$MOD/.sv-pocket-common.XXXXXX") || fail 'Не удалось подготовить изменение.'
    trap 'rm -f "$tmp"' EXIT
    cp -p "$common" "$tmp" || fail 'Не удалось сохранить права файла модуля.'
    if [ "$1" = on ]; then
        from=$stock_common; to=$fast_common
        [ -f "${POCKET_TV_FAST_SOURCE:-}" ] && cat "$POCKET_TV_FAST_SOURCE" >> "$tmp" || fail 'Компонент быстрого запуска недоступен.'
    else
        from=$fast_common; to=$stock_common
        head -c 39097 "$common" > "$tmp" || fail 'Не удалось восстановить оригинальный файл.'
    fi
    [ "$(file_hash "$tmp")" = "$to" ] && "$SHELL_BIN" -n "$tmp" || fail 'Проверка изменения не пройдена. Модуль оставлен без изменений.'
    attrs=$(stat -c '%u:%g:%a' "$common") && [ -n "$attrs" ] && [ "$attrs" = "$(stat -c '%u:%g:%a' "$tmp")" ] || fail 'Права файла не совпали. Модуль оставлен без изменений.'
    if [ -f /sys/fs/selinux/enforce ]; then
        label=$(ls -Zd "$common" | awk '{print $1}')
        case "$label" in *:*:*:*) ;; *) fail 'Не удалось прочитать контекст файла модуля.';; esac
        if [ "$(ls -Zd "$tmp" | awk '{print $1}')" != "$label" ]; then
            chcon "$label" "$tmp" && [ "$(ls -Zd "$tmp" | awk '{print $1}')" = "$label" ] || fail 'Не удалось сохранить контекст файла.'
        fi
    fi
    [ ! -e "$MOD/disable" ] && [ ! -e "$MOD/remove" ] && [ ! -e "$STAGING/module.prop" ] && [ ! -L "$common" ] && [ "$(file_hash "$common")" = "$from" ] ||
        fail 'Модуль изменился во время операции. Обновите состояние.'
    mv -f "$tmp" "$common" || fail 'Не удалось сохранить режим запуска.'
)
auto_fast_start() {
    if ! (require_module) || [ ! -f "$MOD/service.sh" ]; then field FAST_AUTO unavailable; return; fi
    # An update can replace even an already patched file. Retry against the final module.
    if update_in_progress || [ -f "$STAGING/module.prop" ]; then field FAST_AUTO deferred; return; fi
    case "$(fast_start_mode)" in
        on) field FAST_AUTO already_on; return ;;
        unsupported) field FAST_AUTO unsupported; return ;;
    esac
    # Release the operation lock on every exit, including transient contention.
    ( acquire; idle_required; require_module
      [ -f "$MOD/service.sh" ] || exit 1
      set_fast_start on || exit 1
      [ "$(fast_start_mode)" = on ] )
    case "$?" in
        0) field FAST_AUTO applied ;;
        75) field FAST_AUTO deferred ;;
        *) fail 'Не удалось применить быстрый запуск. Обновите состояние модуля.' ;;
    esac
}
catalog_id() {
    # Preserve the existing fingerprint, but start sha256sum once for the whole
    # catalog instead of hundreds of separate processes on a TV box.
    directory="$(catalog_directory)"
    set --
    for f in "$directory/"*.sh; do [ ! -f "$f" ] || set -- "$@" "$f"; done
    { sed -n 's/^versionCode=//p' "$MOD/module.prop"; [ "$#" -eq 0 ] || sha256sum "$@"; } | sha256sum | awk '{print $1}'
}
version_code() { [ ! -f "$1/module.prop" ] || sed -n 's/^versionCode=//p' "$1/module.prop" | tr -d '\r'; }
update_in_progress() { [ "$(value "$UPDATE/state")" = installing ] && alive "$UPDATE/owner"; }
update_state() { printf '%s\n' "$1" > "$UPDATE/state.tmp"; mv "$UPDATE/state.tmp" "$UPDATE/state"; }
update_status() {
    state="$(value "$UPDATE/state")"
    target="$(value "$UPDATE/version")"
    if [ "$state" = installing ] && ! alive "$UPDATE/owner"; then
        state="$(value "$UPDATE/state")"
        if [ "$state" = installing ]; then
            if [ "$(value "$UPDATE/exit")" = 0 ] && [ -n "$target" ] && [ "$(version_code "$STAGING")" = "$target" ]; then state=pending_reboot
            else state=interrupted; fi
        fi
    fi
    if [ "$state" = pending_reboot ] && [ "$(value "$UPDATE/boot")" != "$(boot_id)" ]; then
        if [ -n "$target" ] && [ "$(version_code "$MOD")" = "$target" ] && [ ! -f "$STAGING/module.prop" ]; then state=completed
        elif [ ! -f "$STAGING/module.prop" ]; then state=failed; fi
    fi
    # Also detect an update staged independently in the Magisk app.
    if { [ -z "$state" ] || [ "$state" = idle ] || [ "$state" = completed ]; } && [ -f "$STAGING/module.prop" ]; then
        state=pending_reboot; target="$(version_code "$STAGING")"
    fi
    field UPDATE_STATE "${state:-idle}"
    field UPDATE_VERSION "${target:-0}"
    if [ -f "$STAGING/module.prop" ]; then field UPDATE_STAGED 1; else field UPDATE_STAGED 0; fi
    printf '@@UPDATE_LOG\n'
    tail -n 100 "$UPDATE/install.log" 2>/dev/null
    return 0
}
install_update() {
    require_module; acquire; idle_required
    package="${1:-}"; target="${2:-}"; digest="${3:-}"
    case "$target" in ''|*[!0-9]*) fail 'Некорректная версия обновления.' ;; esac
    case "$digest" in ''|*[!a-f0-9]*) fail 'Некорректная контрольная сумма.' ;; esac
    [ "${#digest}" = 64 ] || fail 'Некорректная контрольная сумма.'
    installed="$(version_code "$MOD")"
    case "$installed" in ''|*[!0-9]*) fail 'Не удалось определить версию установленного модуля.' ;; esac
    [ "$target" -gt "$installed" ] || fail 'Эта или более новая версия уже установлена.'
    [ -f "$package" ] || fail 'Файл обновления не найден. Скачайте его заново.'
    command -v "$MAGISK_BIN" >/dev/null 2>&1 || fail 'Magisk не найден. Установка обновления недоступна.'
    command -v setsid >/dev/null 2>&1 || fail 'Фоновая установка недоступна на этой приставке.'
    mkdir -p "$UPDATE" || fail 'Не удалось подготовить обновление.'
    cp "$package" "$UPDATE/package.tmp" || fail 'Не удалось сохранить файл обновления.'
    actual="$(sha256sum "$UPDATE/package.tmp" | awk '{print $1}')"
    [ "$actual" = "$digest" ] || { rm -f "$UPDATE/package.tmp"; fail 'Файл обновления повреждён. Установка отменена.'; }
    mv "$UPDATE/package.tmp" "$UPDATE/package.zip" || fail 'Не удалось сохранить обновление.'
    # Keep the complete installed module plus configuration as a private recovery checkpoint.
    # Retain one checkpoint; it is never uploaded or exposed through intents.
    tar -czf "$UPDATE/before-update.tmp.tar.gz" -C "$MOD" . || fail 'Не удалось сохранить резервную копию. Установка отменена.'
    mv "$UPDATE/before-update.tmp.tar.gz" "$UPDATE/before-update.tar.gz" || fail 'Не удалось сохранить резервную копию.'
    printf '%s\n' "$target" > "$UPDATE/version"
    printf '%s\n' "$(boot_id)" > "$UPDATE/boot"
    rm -f "$UPDATE/exit"
    cp "$0" "$UPDATE/runner.sh" || fail 'Не удалось подготовить установку.'
    printf 'Сохранена резервная копия Pocket %s. Установка v%s…\n' "$installed" "$target" > "$UPDATE/install.log"
    update_state installing
    nohup setsid "$SHELL_BIN" "$UPDATE/runner.sh" run-update >> "$UPDATE/install.log" 2>&1 < /dev/null &
    runner=$!
    identity "$runner" > "$UPDATE/owner"
    sleep 1
    update_status
}
run_update() {
    identity "$$" > "$UPDATE/owner"
    # The installer is deliberately detached: closing SV Pocket must not kill Magisk mid-install.
    "$MAGISK_BIN" --install-module "$UPDATE/package.zip"
    result=$?
    printf '%s\n' "$result" > "$UPDATE/exit"
    printf 'Magisk завершил установку: код %s.\n' "$result"
    if [ "$result" = 0 ] && [ "$(version_code "$STAGING")" = "$(value "$UPDATE/version")" ]; then
        update_state pending_reboot
        printf 'Обновление установлено. Перезагрузите приставку для завершения.\n'
    else
        update_state failed
        printf 'Обновление не завершено. Сохранена резервная копия; подробности выше.\n'
    fi
}
catalog_directory() {
    # Installation normally copies the bundled scripts into strategy/. If boot
    # has not initialized that directory yet, read the original layout directly.
    # Never invoke common.sh just to populate the UI.
    for directory in "$MOD/strategy" "$MOD/strategies" "$MOD/zapret"; do
        for file in "$directory/"*.sh; do
            [ -f "$file" ] || continue
            case "${file##*/}" in zapret.sh|make-unkillable.sh) continue ;; esac
            printf '%s\n' "$directory"
            return
        done
    done
    printf '%s\n' "$MOD/strategy"
}
catalog() {
    directory="$(catalog_directory)"
    set --
    for file in "$directory/"*.sh; do
        [ -f "$file" ] || continue
        name="${file##*/}"; name="${name%.sh}"
        case "$name" in zapret|make-unkillable) continue ;; esac
        # The CLI uses newline-separated strategy names too.
        set -- "$@" "$name"
    done
    [ "$#" -eq 0 ] || printf '%s\n' "$@" | sort
}
prepare_curl() {
    # Pocket v71 bundles a Termux curl executable without its shared libraries.
    # Install our ABI-matched Android executable into our own directory, leaving
    # the module intact. Its resolver can discover the working fallback via PATH.
    printf 'SV Pocket: проверка curl перед запуском\n' > "$BASE/preflight.log"
    [ -f "$MOD/common.sh" ] || fail 'Не найден common.sh модуля Pocket. Переустановите модуль.'
    [ -f "${POCKET_TV_CURL_SOURCE:-}" ] && [ -s "${POCKET_TV_CA_SOURCE:-}" ] ||
        fail 'Встроенный curl или сертификаты недоступны. Обновите APK SV Pocket.'
    mkdir -p "$RUNTIME" || fail 'Не удалось подготовить curl.'
    cp "$POCKET_TV_CURL_SOURCE" "$RUNTIME/curl.bin.tmp" &&
        chmod 700 "$RUNTIME/curl.bin.tmp" || fail 'Не удалось подготовить встроенный curl.'
    if ! "$RUNTIME/curl.bin.tmp" --version >> "$BASE/preflight.log" 2>&1; then
        fail 'Встроенный curl не запускается на этой приставке. Причина записана в журнале; сервис и прошлый рейтинг сохранены.'
    fi
    mv "$RUNTIME/curl.bin.tmp" "$RUNTIME/curl.bin" || fail 'Не удалось установить встроенный curl.'
    cp "$POCKET_TV_CA_SOURCE" "$RUNTIME/ca-bundle.pem.tmp" &&
        mv "$RUNTIME/ca-bundle.pem.tmp" "$RUNTIME/ca-bundle.pem" || fail 'Не удалось подготовить сертификаты HTTPS.'
    {
        printf '#!%s\n' "$SHELL_BIN"
        cat <<'CURL_WRAPPER'
runtime_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)" || exit 1
exec "$runtime_dir/curl.bin" --disable --cacert "$runtime_dir/ca-bundle.pem" "$@"
CURL_WRAPPER
    } > "$RUNTIME/curl.tmp"
    chmod 700 "$RUNTIME/curl.tmp" && mv "$RUNTIME/curl.tmp" "$RUNTIME/curl" || fail 'Не удалось подготовить команду curl.'
    export PATH="$RUNTIME:$PATH"
    if [ -f "$MOD/curl" ]; then
        printf '\nCurl модуля Pocket:\n' >> "$BASE/preflight.log"
        "$MOD/curl" --version >> "$BASE/preflight.log" 2>&1 || true
    fi
    resolved="$("$SHELL_BIN" -c 'MODPATH="$1"; . "$MODPATH/common.sh"; resolve_downloader' pocket-preflight "$MOD" 2>> "$BASE/preflight.log")"
    if [ -z "$resolved" ] || ! "$resolved" --version >> "$BASE/preflight.log" 2>&1; then
        fail 'Pocket не смог выбрать рабочий curl. Откройте журнал. Сервис и прошлый рейтинг сохранены.'
    fi
    printf '\nSV Pocket: curl готов: %s\n' "$resolved" >> "$BASE/preflight.log"
}
process_service_status() {
    # Reading state must never source common.sh or invoke the module CLI. One
    # process snapshot replaces repeated pgrep calls and layout migrations.
    processes="$(ps -A -o PID,ARGS 2>/dev/null)" || return 1
    printf '%s\n' "$processes" | awk -v mod="$MOD" '
        function basename(path) { sub(/^.*\//, "", path); return path }
        $1 ~ /^[0-9]+$/ && NF >= 2 {
            rows++
            program=basename($2)
            if (program == "nfqws" || program == "nfqws2") engine=1
            if (program == "dnscrypt-proxy") dns=1
            script=$2
            if (program == "sh" || program == "bash" || program == "mksh" || program == "busybox") {
                script=""
                for (i=3; i<=NF; i++) {
                    # A shell command string mentioning a path is not a service.
                    if ($i == "-c") break
                    if (substr($i,1,1) == "-" || $i == "sh") continue
                    script=$i; break
                }
            }
            if (script == mod "/zapret/zapret.sh" || script == mod "/zapret.sh") supervisor=1
            if (script == mod "/dnscrypt/dnscrypt.sh" || script == mod "/dnscrypt.sh") dns=1
        }
        END {
            if (!rows) exit 1
            if (supervisor && engine) print "running"
            else if (supervisor || engine || dns) print "degraded"
            else print "stopped"
        }'
}
service_status() {
    command -v timeout >/dev/null 2>&1 || { printf 'Не найдена системная команда timeout.\n' >&2; return 1; }
    timeout -s KILL 5 "$SHELL_BIN" "$0" service-state
}
inspect() {
    phase module
    require_module
    update_in_progress && fail 'Идёт установка обновления. Дождитесь её завершения.'
    phase service
    svc="$(service_status)" || fail 'Не удалось прочитать процессы модуля за 5 с. Этап: состояние сервиса. Откройте журнал.'
    case "$svc" in running|degraded|stopped) ;; *) fail 'Не удалось определить состояние процессов модуля.' ;; esac
    phase catalog
    field VERSION "$(sed -n 's/^version=//p' "$MOD/module.prop" | tr -d '\r')"
    field VERSION_CODE "$(version_code "$MOD")"
    field CATALOG_ID "$(catalog_id)"
    field SERVICE "$svc"
    field SELECTED "$(value "$MOD/config/current-strategy")"
    if [ -f "$MOD/service.sh" ]; then field BOOT 1; else field BOOT 0; fi
    field FAST_START "$(fast_start_mode)"
    if grep -q '@@RESULT' "$CLI" && grep -q '@@TESTING' "$CLI"; then field COMPATIBLE 1; else field COMPATIBLE 0; fi
    catalog | sed 's/^/@@STRATEGY /'
}
poll() {
    [ -f "$SESSION/id" ] || { field STATE idle; return; }
    field ID "$(value "$SESSION/id")"
    state="$(value "$SESSION/state")"
    case "$state" in starting|running|restoring|cancelling)
        if ! alive "$SESSION/owner"; then
            # The runner can publish its terminal state and exit during alive().
            state="$(value "$SESSION/state")"
            case "$state" in starting|running|restoring|cancelling) state=interrupted ;; esac
        fi ;;
    esac
    field STATE "$state"
    field TOTAL "$(value "$SESSION/total")"
    field STARTED "$(value "$SESSION/started")"
    field PROFILE "$(value "$SESSION/profile")"
    field EXIT "$(value "$SESSION/exit")"
    field RESTORE "$(value "$SESSION/restore")"
    if [ -f "$SESSION/cancel" ]; then field CANCEL_REQUESTED 1; else field CANCEL_REQUESTED 0; fi
    field RESTORE_STEP "$(value "$SESSION/restore-step")"
    field ERROR "$(value "$SESSION/error")"
    field MODULE_VERSION "$(value "$SESSION/module-version")"
    field CATALOG_ID "$(value "$SESSION/catalog-id")"
    grep -E '^@@(RESULT|TESTING) ' "$SESSION/test.log" 2>/dev/null || true
    printf '@@LOGTAIL\n'
    tail -n 35 "$SESSION/test.log" 2>/dev/null
    tail -n 8 "$SESSION/recovery.log" 2>/dev/null
    return 0
}
read_state() {
    # One su invocation, with explicit section boundaries so logs cannot be
    # mistaken for module/test fields. No internet probes on this path.
    phase root
    phase update
    printf '@@SECTION UPDATE\n'
    update_status
    printf '\n@@SECTION MODULE\n'
    if [ "$1" = full ] && ! update_in_progress; then inspect; fi
    printf '@@SECTION TEST\n'
    phase results
    poll
    printf '\n@@SECTION END\n'
    phase complete
}
boot_diagnostics() {
    printf '\n=== boot-status ===\n'
    printf 'boot_id: %s\n' "$(boot_id)"
    printf 'uptime: '; cat /proc/uptime 2>/dev/null
    if command -v getprop >/dev/null 2>&1; then printf 'sys.boot_completed: %s\n' "$(getprop sys.boot_completed)"; fi
    printf 'module version: %s\n' "$(version_code "$MOD")"
    printf 'selected: %s\n' "$(value "$MOD/config/current-strategy")"
    printf 'fast start: %s\n' "$(fast_start_mode)"
    if [ -r "$MOD/.service/sv-network-wait.log" ]; then
        printf 'last network wait (match boot_id above): '; head -n 1 "$MOD/.service/sv-network-wait.log"
    fi
    for name in service.sh disable remove; do
        if [ -e "$MOD/$name" ]; then printf '%s: present\n' "$name"; else printf '%s: absent\n' "$name"; fi
    done
    printf 'process state: '; service_status || printf 'unavailable\n'
    # Keep only module-related roles, never arbitrary process command lines or
    # proxy credentials. This lets a report distinguish waiting startup from no
    # startup process without executing any Pocket script.
    timeout -s KILL 5 ps -A -o PID,ARGS 2>/dev/null | awk -v mod="$MOD" '
        $1 ~ /^[0-9]+$/ {
            for (i=2;i<=NF;i++) {
                if ($i == mod "/service.sh") print "boot script pid:", $1
                if ($i == mod "/system/bin/zapret" && ($(i+1) == "start" || $(i+1) == "restart"))
                    print "startup pid:", $1, "action:", $(i+1)
            }
        }'
    for file in "$MOD/.service/debug.log" "$MOD/config/debug.log"; do
        [ -f "$file" ] || continue
        printf '\n=== %s ===\n' "${file##*/}"
        tail -n 120 "$file" 2>/dev/null
    done
    return 0
}
launch_test() {
    require_module
    acquire
    idle_required
    grep -q '@@RESULT' "$CLI" && grep -q '@@TESTING' "$CLI" ||
        fail 'Эта версия Pocket не выдаёт прогресс для приложения. Обновите модуль sevcator (совместимость проверена с v71).'
    case "$1" in youtube|all) profile="$1" ;; *) fail 'Неизвестный профиль проверки.' ;; esac
    case "$2" in ''|*[!a-zA-Z0-9-]*) fail 'Некорректный идентификатор проверки.' ;; esac
    names="$(catalog)"
    [ -n "$names" ] || fail 'Каталог Pocket пуст. Переустановите модуль и перезагрузите приставку.'
    prepare_curl
    # Keep one recoverable session; the Android app also stores the last snapshot atomically.
    rm -rf "$SESSION.previous"
    [ ! -d "$SESSION" ] || mv "$SESSION" "$SESSION.previous"
    mkdir "$SESSION" || fail 'Не удалось создать сессию.'
    printf '%s\n' "$names" > "$SESSION/names"
    printf '%s\n' "$names" | wc -l | tr -d ' ' > "$SESSION/total"
    printf '%s\n' "$2" > "$SESSION/id"
    printf '%s\n' "$profile" > "$SESSION/profile"
    sed -n 's/^version=//p' "$MOD/module.prop" > "$SESSION/module-version"
    catalog_id > "$SESSION/catalog-id"
    date +%s > "$SESSION/started"
    cp "$0" "$BASE/runner.sh" || fail 'Не удалось подготовить фоновый запуск.'
    chmod 700 "$BASE/runner.sh"
    write_state starting
    nohup "$SHELL_BIN" "$BASE/runner.sh" run-test > "$SESSION/runner.log" 2>&1 < /dev/null &
    runner=$!
    identity "$runner" > "$SESSION/owner"
    sleep 1
    if ! alive "$SESSION/owner"; then
        case "$(value "$SESSION/state")" in
            completed|failed|cancelled) ;; # A fast terminal state is a real result.
            *) cat "$SESSION/runner.log" >&2; fail 'Фоновый процесс не запустился. Откройте журнал.' ;;
        esac
    fi
    field ID "$2"
}
stop_test_child() {
    alive "$SESSION/child" || return 0
    read -r cpid ctoken cboot < "$SESSION/child"
    # Interrupt the network children immediately as well as their shell. Check
    # both session and group leadership before signalling a negative PID.
    isolated_child() {
        alive "$SESSION/child" &&
            [ "$(sed 's/.*) //' "/proc/$cpid/stat" 2>/dev/null | awk '{print $3 " " $4}')" = "$cpid $cpid" ]
    }
    if isolated_child; then kill -TERM "-$cpid" 2>/dev/null || true
    else kill -TERM "$cpid" 2>/dev/null || true; fi
    n=0
    while alive "$SESSION/child" && [ "$n" -lt 4 ]; do sleep 1; n=$((n+1)); done
    if alive "$SESSION/child"; then
        if isolated_child; then kill -KILL "-$cpid" 2>/dev/null || true
        else kill -KILL "$cpid" 2>/dev/null || true; fi
    fi
}
run_test() {
    identity "$$" > "$SESSION/owner"
    trap 'touch "$SESSION/cancel"' INT TERM
    if ! before="$(service_status 2> "$SESSION/recovery.log")"; then
        printf 'Не удалось прочитать состояние сервиса. Обход не изменён.\n' > "$SESSION/error"
        write_state failed
        return 1
    fi
    printf '%s\n' "$before" > "$SESSION/before"
    printf 'SV Pocket: подготовка. Предыдущее состояние: %s\n' "$before" > "$SESSION/test.log"
    cat "$BASE/preflight.log" >> "$SESSION/test.log" 2>/dev/null
    # Own restoration here, so cancellation uses the same cleanup path as completion.
    "$SHELL_BIN" "$CLI" stop >> "$SESSION/test.log" 2>&1
    result=1
    if [ ! -f "$SESSION/cancel" ]; then
        write_state running
        export ZAPRET_TEST_STRATEGIES="$SESSION/names"
        if [ "$(value "$SESSION/profile")" = youtube ]; then
            set -- https://www.youtube.com https://youtu.be https://i.ytimg.com https://redirector.googlevideo.com
        else set --; fi
        setsid "$SHELL_BIN" "$CLI" test "$@" >> "$SESSION/test.log" 2>&1 < /dev/null &
        child=$!
        identity "$child" > "$SESSION/child"
        while alive "$SESSION/child"; do
            if [ -f "$SESSION/cancel" ]; then
                write_state cancelling
                stop_test_child
                break
            fi
            sleep 1
        done
        wait "$child"
        result=$?
    fi
    write_state restoring
    printf 'cleanup\n' > "$SESSION/restore-step"
    printf 'SV Pocket: восстановление сервиса…\n' > "$SESSION/recovery.log"
    "$SHELL_BIN" "$CLI" stop >> "$SESSION/recovery.log" 2>&1
    restore=ok
    case "$before" in running|degraded)
        printf 'start\n' > "$SESSION/restore-step"
        "$SHELL_BIN" "$CLI" start >> "$SESSION/recovery.log" 2>&1 || restore=failed ;;
    esac
    printf '%s\n' "$restore" > "$SESSION/restore"
    printf '%s\n' "$result" > "$SESSION/exit"
    if [ -f "$SESSION/cancel" ]; then write_state cancelled
    elif [ "$result" -eq 0 ] && grep -q '^@@RESULT ' "$SESSION/test.log"; then write_state completed
    else
        if grep -q 'curl not found or not functional' "$SESSION/test.log"; then
            printf 'Не удалось запустить curl для сетевых проверок.\n' > "$SESSION/error"
        else
            printf 'Тестер Pocket завершился с кодом %s. Подробности в журнале.\n' "$result" > "$SESSION/error"
        fi
        write_state failed
    fi
}

# A video trial changes the live service, not the strategy selected for the next boot.
# Pocket 71/72 reads the selection when it starts; "strategy" alone does not restart it.
video_select() {
    [ "$(value "$MOD/config/current-strategy")" = "$1" ] && return 0
    printf '%s\n' "$1" | "$SHELL_BIN" "$CLI" strategy >/dev/null &&
        [ "$(value "$MOD/config/current-strategy")" = "$1" ]
}
video_transition() {
    action=$1; session=$2; fingerprint=$3; original=$4; wanted=$5
    case "$action" in try|commit|restore-on|restore-off) ;; *) fail 'Неизвестная операция видеотеста.' ;; esac
    require_module; acquire; idle_required
    case "$(version_code "$MOD")" in 71|72) ;; *) field VIDEO changed; return ;; esac
    if [ -z "$session" ] || [ "$(value "$SESSION/id")" != "$session" ] ||
       [ "$(value "$SESSION/state")" != completed ] || [ "$(value "$SESSION/profile")" != youtube ] ||
       [ "$(value "$SESSION/catalog-id")" != "$fingerprint" ] || [ "$(catalog_id)" != "$fingerprint" ]; then
        field VIDEO changed; return
    fi
    names=$(catalog)
    for n in "$original" "$wanted"; do
        case "$n" in ''|*/*|.|..) fail 'Недопустимое название стратегии.' ;; esac
        printf '%s\n' "$names" | grep -Fx -- "$n" >/dev/null || { field VIDEO changed; return; }
    done
    chosen=$(value "$MOD/config/current-strategy")
    if [ "$chosen" != "$original" ]; then
        case "$action" in restore-*) [ "$chosen" = "$wanted" ] || { field VIDEO changed; return; } ;;
            *) field VIDEO changed; return ;; esac
    fi
    before=$(service_status) || fail 'Не удалось проверить состояние перед видеотестом.'
    case "$before" in stopped) run=start ;; running|degraded) run=restart ;; *) fail 'Неизвестное состояние сервиса.' ;; esac
    case "$action" in
        try)
            # Best-effort cleanup on signals, and a checked cleanup on the normal path.
            ( trap 'if [ "$(value "$MOD/config/current-strategy")" = "$wanted" ]; then video_select "$original"; fi' EXIT
              trap 'exit 1' HUP INT TERM
              video_select "$wanted" || exit 1
              ok=0
              if "$SHELL_BIN" "$CLI" "$run" >/dev/null && [ "$(service_status)" = running ]; then ok=1; fi
              [ "$(value "$MOD/config/current-strategy")" = "$wanted" ] && video_select "$original" || exit 1
              if [ "$ok" = 1 ]; then field VIDEO ready; else field VIDEO no_service; fi ) ;;
        commit)
            video_select "$wanted" && "$SHELL_BIN" "$CLI" "$run" >/dev/null &&
                [ "$(service_status)" = running ] && [ "$(value "$MOD/config/current-strategy")" = "$wanted" ] ||
                fail 'Не удалось включить победителя видеотеста.'
            field VIDEO committed ;;
        restore-on|restore-off)
            video_select "$original" || fail 'Не удалось вернуть сохранённую стратегию.'
            if [ "$action" = restore-on ]; then
                "$SHELL_BIN" "$CLI" "$run" >/dev/null && [ "$(service_status)" = running ] || fail 'Не удалось восстановить прежний обход.'
            elif [ "$before" != stopped ]; then
                "$SHELL_BIN" "$CLI" stop >/dev/null && [ "$(service_status)" = stopped ] || fail 'Не удалось вернуть выключенное состояние.'
            fi
            field VIDEO restored ;;
    esac
}
[ "$(id -u)" = 0 ] || fail 'Нет root-доступа. Разрешите SV Pocket права суперпользователя в Magisk.'
case "${1:-}" in
    update-status) update_status ;;
    install-update) install_update "$2" "$3" "$4" ;;
    run-update) run_update ;;
    reboot-after-update)
        acquire
        update_in_progress && fail 'Установка ещё продолжается.'
        active && fail 'Сначала завершите подбор стратегий.'
        [ -f "$STAGING/module.prop" ] || fail 'Нет обновления, ожидающего перезагрузки.'
        reboot ;;
    video-transition) video_transition "$2" "$3" "$4" "$5" "$6" ;;
    inspect) inspect ;;
    auto-fast-start) auto_fast_start ;;
    fast-start) require_module; acquire; idle_required; set_fast_start "$2" ;;
    service-state) process_service_status ;;
    state) case "${2:-}" in full|quick) read_state "$2" ;; *) fail 'Неизвестный режим состояния.' ;; esac ;;
    poll) poll ;;
    log)
        boot_diagnostics
        printf '\n=== preflight.log ===\n'
        tail -n 80 "$BASE/preflight.log" 2>/dev/null
        for file in test.log recovery.log runner.log; do
            printf '\n=== %s ===\n' "$file"
            tail -n 4000 "$SESSION/$file" 2>/dev/null
        done
        printf '\n=== module-update.log ===\n'
        tail -n 400 "$UPDATE/install.log" 2>/dev/null
        true ;;
    launch-test)
        command -v setsid >/dev/null 2>&1 || fail 'В системе отсутствует setsid, безопасный фоновый запуск недоступен.'
        launch_test "$2" "$3" ;;
    run-test) run_test ;;
    cancel-test)
        acquire
        active || fail 'Подбор уже завершён или прерван.'
        touch "$SESSION/cancel"
        poll ;;
    start|stop|restart)
        require_module; acquire; idle_required
        "$SHELL_BIN" "$CLI" "$1" ;;
    apply)
        require_module; acquire; idle_required
        selected="${2:-}"
        case "$selected" in ''|*/*|.|..) fail 'Некорректное имя стратегии.' ;; esac
        catalog | grep -Fx -- "$selected" >/dev/null || fail 'Стратегия отсутствует в установленном каталоге Pocket.'
        before="$(service_status)" || fail 'Не удалось проверить состояние перед подключением.'
        if [ "$(value "$MOD/config/current-strategy")" != "$selected" ]; then
            printf '%s\n' "$selected" | "$SHELL_BIN" "$CLI" strategy || exit 1
        fi
        [ "$(value "$MOD/config/current-strategy")" = "$selected" ] || fail 'Pocket не сохранил выбранную стратегию.'
        if [ "$before" = stopped ]; then "$SHELL_BIN" "$CLI" start
        else "$SHELL_BIN" "$CLI" restart; fi ;;
    *) fail 'Неизвестная команда SV Pocket.' ;;
esac
