package com.example.portal.web;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** 템플릿에서 ${@fmt.t(instant)} 로 사용 */
@Component("fmt")
public class Fmt {
    private static final DateTimeFormatter F = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Seoul"));

    public String t(Instant i) {
        return i == null ? "-" : F.format(i);
    }
}
