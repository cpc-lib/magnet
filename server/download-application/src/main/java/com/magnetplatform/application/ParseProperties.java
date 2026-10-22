package com.magnetplatform.application;

import com.magnetplatform.domain.torrent.TorrentMetaParser;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "magnet.parse")
public record ParseProperties(Integer timeoutSeconds, Integer perUserDailyLimit, Integer maxTorrentBytes) {

    public ParseProperties {
        if (timeoutSeconds == null) {
            timeoutSeconds = 90;
        }
        if (perUserDailyLimit == null) {
            perUserDailyLimit = 50;
        }
        if (maxTorrentBytes == null) {
            maxTorrentBytes = TorrentMetaParser.MAX_TORRENT_BYTES;
        }
    }
}
