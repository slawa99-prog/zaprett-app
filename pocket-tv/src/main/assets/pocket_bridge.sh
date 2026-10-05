#!/system/bin/sh
# Pocket TV owns only BASE. All network/firewall work is delegated to Pocket.
umask 077
BASE="${POCKET_TV_BASE:-/data/adb/pocket-tv}"
MOD="${POCKET_TV_MODULE:-/data/adb/modules/zapret}"
CLI="$MOD/system/bin/zapret"
SESSION="$BASE/session"
LOCK="$BASE/action.lock"
SHELL_BIN="${POCKET_TV_SHELL:-/system/bin/sh}"
RUNTIME="$BASE/runtime"
[ ! -x "$RUNTIME/curl" ] || export PATH="$RUNTIME:$PATH"

fail() { printf '%s\n' "$*" >&2; exit 1; }
field() { printf '@@%s %s\n' "$1" "$2"; }
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
    mkdir -p "$BASE" || fail 'Не удалось создать каталог Pocket TV.'
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
    active && fail 'Сначала остановите подбор и дождитесь восстановления сервиса.'
    # A tester opened in a terminal or WebUI must not race the application.
    if ps -A -o ARGS 2>/dev/null | grep -F "$CLI" | grep -E '(^|[[:space:]])test([[:space:]]|$)' | grep -v 'grep' >/dev/null; then
        fail 'Тестер Pocket уже запущен из терминала или WebUI. Завершите его перед этой операцией.'
    fi
}
catalog() {
    for file in "$MOD/strategy/"*.sh; do
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
    printf 'Pocket TV: проверка curl перед запуском\n' > "$BASE/preflight.log"
    [ -f "$MOD/common.sh" ] || fail 'Не найден common.sh модуля Pocket. Переустановите модуль.'
    [ -f "${POCKET_TV_CURL_SOURCE:-}" ] && [ -s "${POCKET_TV_CA_SOURCE:-}" ] ||
        fail 'Встроенный curl или сертификаты недоступны. Обновите APK Pocket TV.'
    mkdir -p "$RUNTIME" || fail 'Не удалось подготовить curl.'
    cp "$POCKET_TV_CURL_SOURCE" "$RUNTIME/curl.bin.tmp" &&
        chmod 700 "$RUNTIME/curl.bin.tmp" &&
        mv "$RUNTIME/curl.bin.tmp" "$RUNTIME/curl.bin" || fail 'Не удалось установить встроенный curl.'
    cp "$POCKET_TV_CA_SOURCE" "$RUNTIME/ca-bundle.pem.tmp" &&
        mv "$RUNTIME/ca-bundle.pem.tmp" "$RUNTIME/ca-bundle.pem" || fail 'Не удалось подготовить сертификаты HTTPS.'
    if ! "$RUNTIME/curl.bin" --version >> "$BASE/preflight.log" 2>&1; then
        fail 'Встроенный curl не запускается на этой приставке. Причина записана в журнале; сервис и прошлый рейтинг сохранены.'
    fi
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
    printf '\nPocket TV: curl готов: %s\n' "$resolved" >> "$BASE/preflight.log"
}
inspect() {
    require_module
    # status also performs Pocket's own layout migration from older releases.
    svc="$("$SHELL_BIN" "$CLI" status 2>/dev/null | tail -n 1)"
    field VERSION "$(sed -n 's/^version=//p' "$MOD/module.prop" | tr -d '\r')"
    field SERVICE "$svc"
    field SELECTED "$(value "$MOD/config/current-strategy")"
    field BOOT 1
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
    field ERROR "$(value "$SESSION/error")"
    grep -E '^@@(RESULT|TESTING) ' "$SESSION/test.log" 2>/dev/null || true
    printf '@@LOGTAIL\n'
    tail -n 35 "$SESSION/test.log" 2>/dev/null
    tail -n 8 "$SESSION/recovery.log" 2>/dev/null
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
    # The child's group is isolated by setsid; do not signal an unrelated process group.
    kill -TERM "$cpid" 2>/dev/null || true
    n=0
    while alive "$SESSION/child" && [ "$n" -lt 12 ]; do sleep 1; n=$((n+1)); done
    if alive "$SESSION/child"; then
        kill -TERM "-$cpid" 2>/dev/null || true
        sleep 1
        if alive "$SESSION/child"; then kill -KILL "-$cpid" 2>/dev/null || true; fi
    fi
}
run_test() {
    identity "$$" > "$SESSION/owner"
    trap 'touch "$SESSION/cancel"' INT TERM
    before="$("$SHELL_BIN" "$CLI" status 2>/dev/null | tail -n 1)"
    printf '%s\n' "$before" > "$SESSION/before"
    printf 'Pocket TV: подготовка. Предыдущее состояние: %s\n' "$before" > "$SESSION/test.log"
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
            sleep 2
        done
        wait "$child"
        result=$?
    fi
    write_state restoring
    printf 'Pocket TV: восстановление сервиса…\n' > "$SESSION/recovery.log"
    "$SHELL_BIN" "$CLI" stop >> "$SESSION/recovery.log" 2>&1
    restore=ok
    case "$before" in running|degraded)
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

[ "$(id -u)" = 0 ] || fail 'Нет root-доступа. Разрешите Pocket TV права суперпользователя в Magisk.'
case "${1:-}" in
    inspect) inspect ;;
    poll) poll ;;
    log)
        printf '\n=== preflight.log ===\n'
        tail -n 80 "$BASE/preflight.log" 2>/dev/null
        for file in test.log recovery.log runner.log; do
            printf '\n=== %s ===\n' "$file"
            tail -n 4000 "$SESSION/$file" 2>/dev/null
        done
        true ;;
    launch-test)
        command -v setsid >/dev/null 2>&1 || fail 'В системе отсутствует setsid, безопасный фоновый запуск недоступен.'
        launch_test "$2" "$3" ;;
    run-test) run_test ;;
    cancel-test)
        acquire
        active || fail 'Подбор уже завершён или прерван.'
        touch "$SESSION/cancel"
        printf 'Запрошена остановка. Дождитесь восстановления сервиса.\n' ;;
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
    *) fail 'Неизвестная команда Pocket TV.' ;;
esac
