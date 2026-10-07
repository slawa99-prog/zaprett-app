package com.slawa.ethernetlink;
final class DiagnosisRules {
    static final class Evidence {
        boolean linkUp,network,configured,vpn,tcp,dns;int httpsResponses;
        ProbeStats ping;
    }
    static String summary(Evidence e){
        if(!e.network)return e.linkUp?"Линк есть, но Android не сообщил приложению IP-подключение Ethernet. Проверь получение адреса, VLAN и требования авторизации у оператора.":"Ethernet-подключение не найдено. Проверь адаптер, кабель и порт; при неизвестном статусе линка разреши USB-доступ.";
        if(!e.configured)return "Ethernet найден, но для выхода в интернет не хватает IP-адреса или маршрута. Проверь настройки адреса и DHCP в этой сети.";
        if(e.vpn)return "Параметры Ethernet прочитаны. Для активной проверки напрямую отключи VPN и повтори запуск.";
        if(e.httpsResponses>0){
            String message=e.httpsResponses>=2?"Доступ к обоим проверяемым сайтам по HTTPS подтверждён.":"Доступ в интернет подтверждён одним сайтом. Второй не ответил — возможна проблема отдельного сервиса или маршрута.";
            if(!e.dns)message+=" Отдельная DNS-проверка не прошла; повтори её.";
            if(e.ping!=null&&e.ping.measurable()&&e.ping.timeouts>0)message+=" Есть пропуски ответов ping: повтори проверку и сравни с другим узлом. Это не CRC-ошибки.";
            return message;
        }
        if(e.tcp&&!e.dns)return "Соединение по IP устанавливается, но DNS-проверка не прошла. Проверь DNS, частный DNS и доступность DNS-серверов.";
        if(e.dns)return "DNS отвечает, но доступ к проверяемым сайтам по HTTPS не подтверждён. Проверь авторизацию, ограничения сети и доступность сайтов.";
        return "Доступ в интернет не подтверждён. Проверь настройки сети, шлюз, VLAN и необходимость авторизации у оператора. Один неответивший ping не определяет причину.";
    }
}
