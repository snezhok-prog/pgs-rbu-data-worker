/*
 * Copyright (c) 2016-2024 Evolenta JSC. Система исполнения электронных регламентов и межведомственного взаимодействия (СИЭР)
 */

package ru.evolenta.server;

import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 *
 * @author Майринов Дмитрий
 */
@SpringBootApplication
@EnableScheduling
@EnableFeignClients(basePackages = {"ru.evolenta.server"})
public class BusinessRbuDataWorker {

    public static void main(String[] args) {
        TimeZone timeZone = TimeZone.getTimeZone("UTC");
        TimeZone.setDefault(timeZone);
        SpringApplication.run(BusinessRbuDataWorker.class, args);
    }

}
