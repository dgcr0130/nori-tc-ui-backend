package com.nori.tc.ui.adapter.db;

import com.nori.tc.ui.core.exception.UiBadRequestException;

import java.util.Locale;

/** PLC 레지스터 저장 경계의 순서 코드. Gateway 모듈에 의존하지 않는다. */
enum PlcRegisterOrder {
    BIG_ENDIAN,
    LITTLE_ENDIAN;

    static String normalizeOptional(final String value, final String context) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT)).name();
        } catch (IllegalArgumentException e) {
            throw new UiBadRequestException("PLC Register " + context + " 값 '" + value
                    + "'은 허용되지 않습니다. 허용값: BIG_ENDIAN, LITTLE_ENDIAN (빈값은 기본 BIG_ENDIAN).", e);
        }
    }
}
