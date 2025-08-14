package com.example.demo.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration properties for proxy settings
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "proxy")
public class ProxyConfig {
    private boolean enabled;
    private String server;
    private int port;
    private String username;
    private String password;
    
    /**
     * Returns the proxy URL in the format required by Playwright
     */
    public String getProxyUrl() {
        if (!enabled) {
            return null;
        }
        
        StringBuilder proxyUrl = new StringBuilder("http://");
        
        if (username != null && !username.isEmpty() && password != null) {
            proxyUrl.append(username).append(":").append(password).append("@");
        }
        
        proxyUrl.append(server).append(":").append(port);
        
        return proxyUrl.toString();
    }
}