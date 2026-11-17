#!/system/bin/sh
# SV Pocket owns only BASE. All network/firewall work is delegated to Pocket.
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
        if alive "$LOCK/owner"; then fail 'Предыдущая операция ещё выполняется. Дождитесь её завершения.'; fi
        # A missing owner may be the brief interval between mkdir and writing it.
        sleep 1
        alive "$LOCK/owner" && fail 'Предыдущая операция ещё выполняется.'
        rm -rf "$LOCK"
        mkdir "$LOCK" 2>/dev/null || fail 'Каталог управления занят.'
    fi
    identity "$$" > "$LOCK/owner"
    trap 'rm -rf "$LOCK"' EXIT
}
idle_required() {
    update_in_progress && fail 'Обновление ещё устанавливается. Дождитесь завершения.'
    [ ! -f "$STAGING/module.prop" ] || fail 'Обновление установлено. Сначала перезагрузите приставку.'
    active && fail 'Сначала остановите подбор и дождитесь восстановления сервиса.'
    # A tester opened in a terminal or WebUI must not race the application.
    if ps -A -o ARGS 2>/dev/null | grep -F "$CLI" | grep -E '(^|[[:space:]])test([[:space:]]|$)' | grep -v 'grep' >/dev/null; then
        fail 'Тестер Pocket уже запущен из терминала или WebUI. Завершите его перед этой операцией.'
    fi
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
    for file in "$directory/"*.sh; do
        [ -f "$file" ] || continue
        name="${file##*/}"; name="${name%.sh}"
        case "$name" in zapret|make-unkillable) continue ;; esac
        # The CLI uses newline-separated strategy names too.
        printf '%s\n' "$name"
    done | sort
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
    if grep -q '@@RESULT' "$CLI" && grep -q '@@TESTING' "$CLI"; then field COMPATIBLE 1; else field COMPATIBLE 0; fi
    catalog | while IFS= read -r name; do field STRATEGY "$name"; done
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
    inspect) inspect ;;
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
        printf '%s\n' "$selected" | "$SHELL_BIN" "$CLI" strategy || exit 1
        [ "$(value "$MOD/config/current-strategy")" = "$selected" ] || fail 'Pocket не сохранил выбранную стратегию.'
        "$SHELL_BIN" "$CLI" restart ;;
    *) fail 'Неизвестная команда SV Pocket.' ;;
esac
