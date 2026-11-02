package com.drawingcourse.backend.service.settlement;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SettlementTimeoutProperties.class)
class SettlementTimeoutConfiguration { }
