package com.example.portal.service;

/** 사용자에게 그대로 보여줄 수 있는 업무 오류 */
public class PortalException extends RuntimeException {
    public PortalException(String message) {
        super(message);
    }
}
