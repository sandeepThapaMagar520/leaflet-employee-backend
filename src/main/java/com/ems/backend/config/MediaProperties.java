package com.ems.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.media")
public class MediaProperties {
    private int userHourlyLimit = 30;
    private int ipHourlyLimit = 80;
    private int pendingLimit = 5;
    private long storedBytesPerPurpose = 104_857_600L;
    private int privateDownloadTtlSeconds = 120;
    private final Scanner scanner = new Scanner();

    public int getUserHourlyLimit() { return userHourlyLimit; }
    public void setUserHourlyLimit(int value) { this.userHourlyLimit = value; }
    public int getIpHourlyLimit() { return ipHourlyLimit; }
    public void setIpHourlyLimit(int value) { this.ipHourlyLimit = value; }
    public int getPendingLimit() { return pendingLimit; }
    public void setPendingLimit(int value) { this.pendingLimit = value; }
    public long getStoredBytesPerPurpose() { return storedBytesPerPurpose; }
    public void setStoredBytesPerPurpose(long value) { this.storedBytesPerPurpose = value; }
    public int getPrivateDownloadTtlSeconds() { return privateDownloadTtlSeconds; }
    public void setPrivateDownloadTtlSeconds(int value) { this.privateDownloadTtlSeconds = value; }
    public Scanner getScanner() { return scanner; }

    public static class Scanner {
        private boolean enabled;
        private String host = "localhost";
        private int port = 3310;
        private int connectTimeoutMs = 2000;
        private int readTimeoutMs = 15000;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
        public int getConnectTimeoutMs() { return connectTimeoutMs; }
        public void setConnectTimeoutMs(int value) { this.connectTimeoutMs = value; }
        public int getReadTimeoutMs() { return readTimeoutMs; }
        public void setReadTimeoutMs(int value) { this.readTimeoutMs = value; }
    }
}
