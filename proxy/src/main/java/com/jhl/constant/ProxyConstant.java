package com.jhl.constant;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "proxy")
@Data
public class ProxyConstant {
    private int localPort;
    private String remoteHost;
    private int remotePort;
    private int maxConnections;
    private String env;
    private String queueSavePath;
    private String authPassword;

}
