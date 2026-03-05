package com.bt.hz.domain.sessions.models;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SYSSE001I implements Serializable {
    private String login;
    private String loginType;
    private String userInfo;
    private String sessionId;
}
